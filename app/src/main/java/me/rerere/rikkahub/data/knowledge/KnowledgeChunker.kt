package me.rerere.rikkahub.data.knowledge

/** 一段切片；start/end 是归一化正文里的 UTF-16 偏移，用来回溯原文。 */
data class KnowledgeChunkSpec(
    val ordinal: Int,
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * 定长 + 重叠的分段器。
 *
 * 先按句读（。！？!?；;\n）尽量在句子边界收尾；每段不超过 targetChars，
 * 下一段从上一段结尾回退 overlapChars 开始，并把起点前移到句首（不会越过上一段结尾，
 * 所以不会有字被跳过）。没有语义模型参与，结果完全由字符决定，测试可断言。
 */
object KnowledgeChunker {
    private val BOUNDARIES = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n', '：', ':')

    fun split(
        text: String,
        targetChars: Int = KnowledgeLimits.CHUNK_TARGET_CHARS,
        overlapChars: Int = KnowledgeLimits.CHUNK_OVERLAP_CHARS,
    ): List<KnowledgeChunkSpec> {
        require(targetChars > 0) { "targetChars must be > 0" }
        require(overlapChars >= 0 && overlapChars < targetChars) {
            "overlapChars must be in [0, targetChars)"
        }
        if (text.isEmpty()) return emptyList()

        val chunks = ArrayList<KnowledgeChunkSpec>()
        var start = 0
        while (start < text.length) {
            var end = (start + targetChars).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = lastBoundaryBefore(text, start + targetChars / 2, end)
                if (boundary > start) end = boundary
            }
            val range = trimmedRange(text, start, end)
            if (range != null) {
                chunks.add(
                    KnowledgeChunkSpec(
                        ordinal = chunks.size,
                        text = text.substring(range.first, range.second),
                        start = range.first,
                        end = range.second,
                    )
                )
            }
            if (end >= text.length) break
            var next = (end - overlapChars).coerceAtLeast(start + 1)
            next = snapToSentenceStart(text, next, end)
            next = trimmedRange(text, next, end)?.first ?: next
            start = next.coerceAtLeast(start + 1)
        }
        return chunks
    }

    /** 在 [from, limit) 里找最后一个句读位置（返回它之后）。 */
    private fun lastBoundaryBefore(text: String, from: Int, limit: Int): Int {
        var i = limit - 1
        while (i >= from) {
            if (text[i] in BOUNDARIES) {
                var j = i + 1
                while (j < limit && text[j].isWhitespace()) j++
                if (j > i + 1) return j
                return i + 1
            }
            i--
        }
        return -1
    }

    /** 在 [from, limit) 里把起点挪到“句读后的第一个非空白字符”。 */
    private fun snapToSentenceStart(text: String, from: Int, limit: Int): Int {
        if (from <= 0) return from
        var i = from
        while (i < limit) {
            val prev = text[i - 1]
            if (prev in BOUNDARIES || prev == ' ') {
                var j = i
                while (j < limit && text[j].isWhitespace()) j++
                if (j < limit) return j
            }
            i++
        }
        return from
    }

    private fun trimmedRange(text: String, start: Int, end: Int): Pair<Int, Int>? {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        // 不要从代理对中间切
        if (s in 1 until text.length && text[s].isLowSurrogate() && text[s - 1].isHighSurrogate()) s++
        if (e in 1 until text.length && text[e].isLowSurrogate() && text[e - 1].isHighSurrogate()) e--
        return if (e > s) s to e else null
    }
}
