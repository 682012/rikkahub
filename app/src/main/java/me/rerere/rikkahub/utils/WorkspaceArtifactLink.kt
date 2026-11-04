package me.rerere.rikkahub.utils

/**
 * 聊天正文里出现的链接的解析结果。
 *
 * 模型经常把工作区里的产物写成 `sandbox:/workspace/out.png`、`/workspace/报告 1.pdf`
 * 这种形式，而 Markdown 渲染层会把链接地址做百分号编码。这里负责把三种东西分清楚：
 *
 * - [WorkspaceFile] 当前对话绑定工作区里的文件，交给应用内预览页，**不能**甩给浏览器；
 * - [Web] http/https，仍然走 CustomTabs；
 * - [ExternalScheme] 其它自定义 scheme，走 ACTION_VIEW 并捕获没有 handler 的情况；
 * - [Rejected] 明确拒绝（越界、格式错、目录等），调用方显示 [Rejected.reason] 的提示。
 *
 * 纯 Kotlin，不依赖 android.net.Uri / File：路径解码与越界判断都能在 JVM 单测里覆盖。
 *
 * ### 只解码一次
 *
 * 链接地址在正文里已经被 Markdown 解析器编码过一次（空格 → `%20`，中文 → `%E4%B8%AD`），
 * 所以这里对 path 做**恰好一次** [percentDecode]。绝不能解码两次：`%252e%252e` 解一次是字面量
 * `%2e%2e`（安全），解两次就变成 `..`（可以跳出工作区）。因此 `..` 检查放在唯一一次解码之后。
 *
 * ### query / fragment
 *
 * 先按 `#`、再按 `?` 切开，再解码 path，锚点与查询串不会被当成文件名的一部分；
 * 它们单独放在 [WorkspaceFile.fragment] / [WorkspaceFile.query] 里。
 */
sealed interface WorkspaceArtifactLink {
    val raw: String

    /** 需要应用内打开的工作区文件 */
    data class WorkspaceFile(
        override val raw: String,
        /** 解码一次后的环境内绝对路径，如 `/workspace/报告 1.pdf` */
        val path: String,
        val query: String?,
        val fragment: String?,
    ) : WorkspaceArtifactLink

    /** http/https，仍交给 CustomTabs */
    data class Web(override val raw: String) : WorkspaceArtifactLink

    /** 其它自定义 scheme（mailto:、mqqopensdkapi: 等），走 ACTION_VIEW */
    data class ExternalScheme(override val raw: String, val scheme: String) : WorkspaceArtifactLink

    /** 拒绝打开，调用方显示 [reason] 的提示，不要交给系统浏览器 */
    data class Rejected(override val raw: String, val reason: Reason) : WorkspaceArtifactLink

    enum class Reason(val message: String) {
        MALFORMED("链接格式不正确"),
        OUTSIDE_WORKSPACE("这个链接指向工作区之外的路径，已阻止打开"),
        NOT_A_FILE("这个链接不是工作区里的文件"),
        NO_WORKSPACE("当前对话没有绑定工作区，无法打开工作区文件"),
        NOT_FOUND("工作区里找不到这个文件"),
    }

    companion object {
        fun scaleToWorkspace(path: String, root: String): String? {
            if(path != "/workspace" && !path.startsWith("/workspace/")) return null
            return root.trimEnd('/') + path.removePrefix("/workspace")
        }

        const val SANDBOX_SCHEME = "sandbox"

        /** 工作区文件区的环境内绝对路径前缀；也只有这个前缀的文件允许被链接打开 */
        const val WORKSPACE_DIR = "/workspace"

        private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")

        /**
         * 解析一个链接。
         *
         * 接受：`sandbox:/workspace/a`、`sandbox:///workspace/a`、`sandbox://workspace/a`、
         * 裸路径 `/workspace/a`、`http(s)://...`、其它 `scheme:...`。
         */
        fun parse(raw: String): WorkspaceArtifactLink {
            val value = raw.trim()
            if (value.isEmpty()) return Rejected(raw, Reason.MALFORMED)

            val scheme = detectScheme(value)
            return when {
                scheme == null -> {
                    // 没有 scheme 的裸路径：只有 /workspace/... 才当成工作区文件
                    if (!value.startsWith("/")) return Rejected(raw, Reason.MALFORMED)
                    workspaceFile(raw, value)
                }

                scheme == "http" || scheme == "https" -> Web(raw)

                scheme == SANDBOX_SCHEME -> {
                    val rest = value.substringAfter(':').let { stripAuthoritySlash(it) }
                    val absolute = if (rest.startsWith("/")) rest else "/$rest"
                    workspaceFile(raw, absolute)
                }

                // file:// 直接读宿主文件，绕过工作区边界，一律不收
                scheme == "file" -> Rejected(raw, Reason.OUTSIDE_WORKSPACE)

                else -> ExternalScheme(raw, scheme)
            }
        }

        /** `///workspace/x` / `//workspace/x` 都归到 `/workspace/x`；单斜杠保持原样 */
        private fun stripAuthoritySlash(value: String): String {
            var rest = value
            while (rest.startsWith("//")) rest = rest.substring(1)
            return rest
        }

        private fun detectScheme(value: String): String? {
            val colon = value.indexOf(':')
            if (colon <= 0) return null
            val candidate = value.substring(0, colon)
            if (!SCHEME_PATTERN.matches(candidate)) return null
            // `/workspace/a:b.png` 这类路径里的冒号不是 scheme
            if (value.startsWith("/")) return null
            return candidate.lowercase()
        }

        private fun workspaceFile(raw: String, absolute: String): WorkspaceArtifactLink {
            val parts = splitQueryAndFragment(absolute)
            val decoded = percentDecode(parts.path)
            if (decoded.any { it == '\u0000' || it == '\\' }) {
                return Rejected(raw, Reason.OUTSIDE_WORKSPACE)
            }

            val segments = decoded.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == ".." }) return Rejected(raw, Reason.OUTSIDE_WORKSPACE)
            val normalized = "/" + segments.filter { it != "." }.joinToString("/")

            if (normalized == WORKSPACE_DIR || normalized == "/") {
                return Rejected(raw, Reason.NOT_A_FILE)
            }
            if (!normalized.startsWith("$WORKSPACE_DIR/")) {
                return Rejected(raw, Reason.OUTSIDE_WORKSPACE)
            }
            return WorkspaceFile(
                raw = raw,
                path = normalized,
                query = parts.query,
                fragment = parts.fragment,
            )
        }

        data class PathParts(val path: String, val query: String?, val fragment: String?)

        /** 切成 path / query / fragment。先切 `#` 再切 `?`，锚点里带 `?` 也不会切错 */
        fun splitQueryAndFragment(value: String): PathParts {
            var path = value
            var fragment: String? = null
            var query: String? = null
            val hash = path.indexOf('#')
            if (hash >= 0) {
                fragment = path.substring(hash + 1).ifEmpty { null }
                path = path.substring(0, hash)
            }
            val mark = path.indexOf('?')
            if (mark >= 0) {
                query = path.substring(mark + 1).ifEmpty { null }
                path = path.substring(0, mark)
            }
            return PathParts(path, query, fragment)
        }

        /**
         * 单次百分号解码（UTF-8）。不合法的 `%` 序列原样保留，`+` 不当作空格（路径语义）。
         */
        fun percentDecode(value: String): String {
            if (value.indexOf('%') < 0) return value
            val result = StringBuilder(value.length)
            val pending = java.io.ByteArrayOutputStream()
            fun flushPending() {
                if (pending.size() > 0) {
                    result.append(String(pending.toByteArray(), Charsets.UTF_8))
                    pending.reset()
                }
            }
            var i = 0
            while (i < value.length) {
                val high = if (value[i] == '%' && i + 2 < value.length) Character.digit(value[i + 1], 16) else -1
                val low = if (high >= 0) Character.digit(value[i + 2], 16) else -1
                if (high >= 0 && low >= 0) {
                    pending.write((high shl 4) or low)
                    i += 3
                } else {
                    flushPending()
                    result.append(value[i])
                    i++
                }
            }
            flushPending()
            return result.toString()
        }

        /** `/workspace/a/b.txt` → `a/b.txt`；不在工作区内时原样返回 */
        fun relativeToWorkspace(path: String): String {
            if (path == WORKSPACE_DIR) return ""
            if (!path.startsWith("$WORKSPACE_DIR/")) return path
            return path.removePrefix("$WORKSPACE_DIR/")
        }

        /** 文件类型决定预览方式；只按扩展名判断，不做内容嗅探 */
        fun kindOf(path: String): WorkspaceArtifactKind = WorkspaceArtifactKind.of(path)
    }
}

/** 工作区产物的预览方式 */
enum class WorkspaceArtifactKind {
    /** 应用内可缩放图片 */
    IMAGE,

    /** 应用内只读文本 */
    TEXT,

    /** 应用内网页预览：随机不透明 origin + 父目录受限的本地资源读取，外网默认关闭 */
    HTML,

    /** 应用内不渲染，交给用户手动用其他应用打开 */
    PDF,

    /** 同上 */
    OTHER;

    companion object {
        private val IMAGE = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
        )
        private val TEXT = setOf(
            "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf",
            "cfg", "properties", "env", "csv", "tsv", "log", "css", "scss", "sass", "less", "js",
            "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
            "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
            "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql",
            "proto", "diff", "patch", "srt", "vtt", "mdown", "mkd", "ipynb", "ndjson", "jsonl", "rst", "adoc", "ps1", "bat", "cmake", "tex",
        )
        private val HTML = setOf("html", "htm", "xhtml")

        fun of(path: String): WorkspaceArtifactKind {
            val name = path.substringAfterLast('/')
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                extension.isEmpty() -> OTHER
                extension in HTML -> WorkspaceArtifactKind.HTML
                extension in IMAGE -> WorkspaceArtifactKind.IMAGE
                extension in TEXT -> WorkspaceArtifactKind.TEXT
                extension == "pdf" -> PDF
                else -> OTHER
            }
        }
    }
}
