package me.rerere.ui.webview

/**
 * 应用内网页预览的地址策略。
 *
 * 只做三件事，全部是纯 Kotlin（不碰 android.net.Uri / WebView），这样导航决策可以在 JVM 单测里覆盖：
 *
 * - [Decision.Inline]：http/https，交给当前 WebView 加载；
 * - [Decision.Blocked]：`file:` / `content:` / `javascript:` / `data:` / `blob:` 这类会读宿主文件或
 *   绕过导航控制的 scheme，直接拒绝，**不**交给系统，也**不**自动发 intent；
 * - [Decision.External]：`mailto:` / `tel:` / `intent:` / 应用私有 scheme，只记下来，等用户在一次
 *   明确的点击里确认后才交给系统处理。
 *
 * 另外提供 [displayHost] 给界面显示“当前域名”：只显示 host（去掉 `www.` 与端口），不带 path，
 * 避免把 token/查询串显示在标题栏，也避免把长 URL 撑成多行。
 */
object WebPreviewNavigation {

    sealed interface Decision {
        /** 就在应用内 WebView 里加载 */
        data object Inline : Decision

        /** 明确拒绝（本地文件、脚本、内联数据），不加载也不外发 */
        data object Blocked : Decision

        /** 需要外部应用处理；调用方必须先经过用户确认 */
        data class External(val scheme: String) : Decision
    }

    private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    private val INLINE_SCHEMES = setOf("http", "https")

    /**
     * 这些 scheme 一律不加载：
     * `file` / `content` 读宿主文件，`javascript` 在文档上下文里执行，
     * `data` / `blob` 可以伪装成任意 origin 绕过域名判断。
     */
    private val BLOCKED_SCHEMES = setOf(
        "file", "content", "javascript", "data", "blob",
        "jar", "resource", "filesystem", "about", "chrome", "view-source",
    )

    /** 取小写 scheme；没有 scheme 或格式明显不对时返回 null */
    fun schemeOf(url: String): String? {
        val value = sanitize(url) ?: return null
        // 冒号前出现 / ? # 说明是相对地址，不是 scheme
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        if (value.take(colon).any { it == '/' || it == '?' || it == '#' }) return null
        return SCHEME_PATTERN.find(value)?.groupValues?.get(1)?.lowercase()
    }

    fun decide(url: String): Decision {
        val value = sanitize(url) ?: return Decision.Blocked
        val scheme = schemeOf(value) ?: return Decision.Blocked
        return when (scheme) {
            in INLINE_SCHEMES -> Decision.Inline
            in BLOCKED_SCHEMES -> Decision.Blocked
            else -> Decision.External(scheme)
        }
    }

    fun isWebUrl(url: String): Boolean = decide(url) == Decision.Inline

    /**
     * 标题栏显示的域名，例如 `example.com`。
     * 解析不出来（本地内容、畸形地址）时返回 null，调用方用自己的标题兜底。
     */
    fun displayHost(url: String): String? {
        val value = sanitize(url) ?: return null
        val scheme = schemeOf(value) ?: return null
        if (scheme !in INLINE_SCHEMES) return null

        var rest = value.substringAfter(':', "")
        rest = rest.trimStart('/')
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null

        // 去掉 user:pass@，只保留主机名
        var host = authority.substringAfterLast('@')
        host = if (host.startsWith("[")) {
            // IPv6 字面量：[::1]:8080
            host.substringBefore(']').let { if (it.isEmpty()) return null else "$it]" }
        } else {
            host.substringBefore(':')
        }
        host = host.removePrefix("www.")
        return host.lowercase().ifEmpty { null }
    }

    /** 去掉首尾空白；含控制字符或内部空白的地址直接判为不可用 */
    private fun sanitize(url: String): String? {
        val value = url.trim()
        if (value.isEmpty()) return null
        if (value.any { it.isISOControl() }) return null
        // URL 里的裸空格会被 WebView 当成非法地址，宁可拒绝也不猜
        if (value.any { it == ' ' || it == '\n' || it == '\t' }) return null
        return value
    }
}
