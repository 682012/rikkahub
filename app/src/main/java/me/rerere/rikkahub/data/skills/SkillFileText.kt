package me.rerere.rikkahub.data.skills

import java.io.File

/**
 * 技能正文 / 附属文件的有界读取。
 *
 * `use_skill` 读到的内容会整段进模型上下文：技能目录里可以放任意大小的文件（数据、日志、抓下来的网页），
 * 直接 readText 一次就能把上下文打满，而对模型来说这看起来像"技能读完了"。这里读到上限就停，
 * 并把是否截断、文件真实大小交给调用方如实说明，不假装读全了。
 *
 * 上限取得足够高（256k 字符），正常的 SKILL.md / 说明文件不会碰到；只有异常的巨型文件才会被截断，
 * 需要时模型可以改用 workspace 的 `/skills/<name>/` 按段读取。
 */
internal object SkillFileText {
    const val DEFAULT_MAX_CHARS = 256 * 1024

    data class Bounded(
        val text: String,
        /** 是否因为超过上限被截断 */
        val truncated: Boolean,
        /** 文件字节数，用于如实说明截断情况 */
        val totalBytes: Long,
    )

    fun readText(file: File, maxChars: Int = DEFAULT_MAX_CHARS): Bounded = read(file, maxChars)

    /** 读 SKILL.md 正文：去掉 frontmatter，与旧实现的 extractBody(readText()) 结果一致 */
    fun readBody(file: File, maxChars: Int = DEFAULT_MAX_CHARS): Bounded {
        val bounded = read(file, maxChars)
        return bounded.copy(text = SkillFrontmatterParser.extractBody(bounded.text))
    }

    private fun read(file: File, maxChars: Int): Bounded {
        // 多读一个字符用来判断"后面还有没有"，不用把整个文件读完才知道是否截断
        val limit = maxChars.coerceAtLeast(0)
        val (text, hasMore) = file.bufferedReader().use { reader ->
            val buffer = CharArray(limit + 1)
            var filled = 0
            while (filled < buffer.size) {
                val read = reader.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled > limit) String(buffer, 0, limit) to true
            else String(buffer, 0, filled) to false
        }
        return Bounded(text = text, truncated = hasMore, totalBytes = file.length())
    }
}
