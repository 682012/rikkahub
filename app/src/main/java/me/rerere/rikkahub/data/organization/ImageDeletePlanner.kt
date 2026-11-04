package me.rerere.rikkahub.data.organization

import java.io.File
import java.nio.file.Files
import me.rerere.rikkahub.data.model.MediaCreationOutput

/**
 * 路径解析与引用统计。纯 JVM，不碰 Android，单测覆盖。
 */
object ImageMediaPaths {
    /**
     * 旧版图片生成页把含 `/` 的模型名直接拼进文件名，图片实际落在 `images/<createAt>_<模型名前半>/` 子目录里，
     * 而 gen_media 只存了最后一段路径。这里按同样的规则还原真实文件。
     */
    fun resolveGenMediaFile(imagesDir: File, relativePath: String, modelId: String, createAt: Long): File {
        val name = relativePath.removePrefix("images/")
        val direct = File(imagesDir, name)
        if (direct.exists() || '/' !in modelId) return direct
        return File(imagesDir, "${createAt}_${modelId.substringBeforeLast('/')}/$name")
    }

    /**
     * [file] 是否真的落在 [dir] 内部（解析 `..` 与 symlink 之后）。
     *
     * 删除前的最后一道闸：即使数据库里的相对路径被写坏（`../`、绝对路径），或者 app 目录里被放了
     * 指向外部存储的 symlink，也只认 canonical 路径在 [dir] 里面的文件。任何解析失败都按「不安全」处理。
     *
     * 纯 JVM，单测覆盖。
     */
    fun isConfinedTo(dir: File, file: File): Boolean {
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (target == root) return false
        return target.path.startsWith(root.path + File.separator)
    }

    /** `file://` 前缀去掉，相对路径按 [baseDir] 展开；其它原样返回（含 http/data，不参与本地比对）。 */
    fun normalize(path: String, baseDir: File): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }
        val withoutScheme = if (trimmed.startsWith("file://")) {
            trimmed.removePrefix("file://")
        } else {
            trimmed
        }
        return if (withoutScheme.startsWith("/")) {
            File(withoutScheme).path
        } else {
            File(baseDir, withoutScheme).path
        }
    }
}

/**
 * 「这张图是不是被别处引用」的统计。
 *
 * 输入都是原始字符串（gen_media.source_paths、媒体创作的素材/产出路径），输出是 绝对路径 → 引用次数。
 * 只要引用次数 > 0，删除就被保护，避免删掉别的记录还在用的图。
 */
object ImageReferenceIndex {
    /** [blobs] 里可能有多条路径挤在一起的字符串（生成记录用换行拼的），逐行拆开。 */
    fun build(filesDir: File, blobs: List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        blobs.forEach { blob ->
            blob.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { raw ->
                    val normalized = ImageMediaPaths.normalize(raw, filesDir) ?: return@forEach
                    counts[normalized] = (counts[normalized] ?: 0) + 1
                }
        }
        return counts
    }

    fun countFor(index: Map<String, Int>, path: String): Int = index[path] ?: 0
}

/** 删除计划：只决定「该不该删」，落盘副作用在 ViewModel 里执行。 */
data class ImageDeletePlan(
    val delete: List<ImageOrganizerItem>,
    val skipped: List<ImageDeleteSkip>,
    val warnings: List<String>,
)

object ImageDeletePlanner {
    /**
     * [selected] 里只有「明确选中 + 非保留 + 可删 + 文件还在 + 没有被别处引用」的项会进入删除列表，
     * 其余都进 [ImageDeletePlan.skipped]。
     */
    fun plan(items: List<ImageOrganizerItem>, selected: Set<String>): ImageDeletePlan {
        val byId = items.associateBy { it.id }
        val skipped = mutableListOf<ImageDeleteSkip>()
        val toDelete = mutableListOf<ImageOrganizerItem>()
        var sharedSkips = 0
        var mediaSkips = 0

        // 按 items 顺序遍历，保证同一 id 只处理一次，结果稳定
        items.forEach { item ->
            if (item.id !in selected) return@forEach
            when {
                item.protected -> skipped += ImageDeleteSkip(item.id, ImageDeleteSkipReason.PROTECTED)
                item.sharedReferences > 0 -> {
                    sharedSkips += 1
                    skipped += ImageDeleteSkip(item.id, ImageDeleteSkipReason.SHARED_REFERENCE)
                }

                // 媒体创作产出图能单独删（走 MediaCreationRepository.removeImageOutput），
                // 只有「所属记录还在生成 / 找不到归属」这类后端明确挡住的情况才不可删
                item.source == ImageSource.MEDIA_CREATION && !item.deletable -> {
                    mediaSkips += 1
                    skipped += ImageDeleteSkip(item.id, ImageDeleteSkipReason.NOT_SUPPORTED)
                }

                !item.deletable -> {
                    sharedSkips += 1
                    skipped += ImageDeleteSkip(item.id, ImageDeleteSkipReason.SHARED_REFERENCE)
                }

                !item.file.exists() -> skipped += ImageDeleteSkip(item.id, ImageDeleteSkipReason.NOT_FOUND)
                else -> toDelete += item
            }
        }

        // 请求里给了但状态里没有的 id
        selected.filter { it !in byId }
            .sorted()
            .forEach { skipped += ImageDeleteSkip(it, ImageDeleteSkipReason.NOT_SELECTED) }

        val warnings = buildList {
            if (sharedSkips > 0) {
                add("有 $sharedSkips 张图被其他生成记录或媒体创作记录引用，已跳过，删除会让那些记录里的图失效。")
            }
            if (mediaSkips > 0) {
                add("有 $mediaSkips 张媒体创作产出图暂时不能删：所属的生成记录还在进行中，等它完成后再试。")
            }
        }

        return ImageDeletePlan(delete = toDelete, skipped = skipped, warnings = warnings)
    }
}

/**
 * 删「一张媒体创作产出图」时的纯 JVM 校验，和 `MediaCreationRepository.removeImageOutput` 共用同一套规则。
 *
 * 这里只做判断（路径能不能碰、这条产出还能不能被摘掉、有没有被别处引用），落盘和数据库操作在仓库里。
 * 不依赖 Android，单测覆盖。
 */
object MediaOutputDeleteRules {
    /** 通过校验的目标：真实存在的普通文件，且落在它所属记录的目录里。 */
    data class Target(val file: File, val canonicalPath: String)

    enum class Rejection {
        /** 路径是空的。 */
        EMPTY_PATH,

        /** 不是本地的普通文件路径：绝对路径、网络地址、符号链接、解析失败。 */
        UNSAFE_PATH,

        /** canonical 之后不在记录目录里（`..` 逃逸、目录被换成 symlink）。 */
        NOT_IN_RECORD_DIR,

        /** 文件不存在或者是目录，不是一张图。 */
        NOT_A_FILE,
    }

    sealed interface TargetCheck {
        data class Ok(val target: Target) : TargetCheck

        data class Rejected(val reason: Rejection, val message: String) : TargetCheck
    }

    /**
     * [relativePath] → 可安全删除的目标文件。
     *
     * 要求：相对 filesDir 的路径；canonical 之后父目录**正好是** [recordDir]（产出图平铺在记录目录里，
     * 不认子目录，也就是不认任何形式的「再下一层」）；本身不是 symlink；确实是个普通文件。
     */
    fun resolveTarget(filesDir: File, recordDir: File, relativePath: String): TargetCheck {
        val path = relativePath.trim()
        if (path.isEmpty()) return TargetCheck.Rejected(Rejection.EMPTY_PATH, "没有给出要删除的产出路径")
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true) ||
            path.startsWith("data:", ignoreCase = true)
        ) {
            return TargetCheck.Rejected(Rejection.UNSAFE_PATH, "这不是本地文件，不能删")
        }
        val withoutScheme = path.removePrefix("file://")
        if (withoutScheme.startsWith("/")) {
            return TargetCheck.Rejected(Rejection.UNSAFE_PATH, "只接受相对应用目录的路径")
        }

        val file = File(filesDir, withoutScheme)
        val canonical = runCatching { file.canonicalFile }.getOrNull()
            ?: return TargetCheck.Rejected(Rejection.UNSAFE_PATH, "路径解析失败，已拒绝删除")
        val root = runCatching { recordDir.canonicalFile }.getOrNull()
            ?: return TargetCheck.Rejected(Rejection.UNSAFE_PATH, "记录目录解析失败，已拒绝删除")
        if (canonical.parent != root.path) {
            return TargetCheck.Rejected(Rejection.NOT_IN_RECORD_DIR, "文件不在它所属记录的目录里，已拒绝删除")
        }
        // 末尾一层必须是真文件：解析失败按「是 symlink」处理，宁可拒绝
        if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)) {
            return TargetCheck.Rejected(Rejection.UNSAFE_PATH, "文件是符号链接，已拒绝删除")
        }
        if (!canonical.isFile) {
            return TargetCheck.Rejected(Rejection.NOT_A_FILE, "文件已经不在，未改动记录")
        }
        return TargetCheck.Ok(Target(file = file, canonicalPath = canonical.path))
    }

    /**
     * 从 [outputs] 里摘掉 [path] 那一条；没有这一条时返回 null（调用方按「记录里已经没有它」处理）。
     * 只按 path 精确匹配，其余产出原样保留，记录本身不动。
     */
    fun withoutOutput(outputs: List<MediaCreationOutput>, path: String): List<MediaCreationOutput>? {
        if (outputs.none { it.path == path }) return null
        return outputs.filterNot { it.path == path }
    }

    /**
     * [rawPath] 是否就是 [canonicalTarget] 指向的那个文件。
     *
     * 相对路径按 [filesDir] 展开，两边都取 canonical 再比，`file://` 前缀和重复分隔符不会造成漏判；
     * `http(s)://` 与 `data:` 不参与本地比对（返回 false）。
     */
    fun sameFile(filesDir: File, rawPath: String, canonicalTarget: String): Boolean {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return false
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true)
        ) {
            return false
        }
        val withoutScheme = raw.removePrefix("file://")
        val file = if (withoutScheme.startsWith("/")) File(withoutScheme) else File(filesDir, withoutScheme)
        val canonical = runCatching { file.canonicalFile.path }.getOrNull() ?: return false
        return canonical == canonicalTarget
    }

    /** [references] 里有没有任何一条还指着 [canonicalTarget]。[references] 里不该包含被删的那条产出自己。 */
    fun isReferenced(filesDir: File, canonicalTarget: String, references: Iterable<String>): Boolean =
        references.any { sameFile(filesDir, it, canonicalTarget) }
}

