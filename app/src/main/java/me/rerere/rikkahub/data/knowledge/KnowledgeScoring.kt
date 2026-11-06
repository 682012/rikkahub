package me.rerere.rikkahub.data.knowledge

/**
 * 确定性相关性打分。没有向量、没有模型，也不是“命中率 99%”这种营销说法：
 * 分数只由 token 覆盖、词频饱和、段长、标题命中、整串短语命中五项组成，
 * 公式和权重都写在这里，测试按固定样例断言排序。
 *
 * score = 0.55 * coverage        // 查询里有多少不同 token 出现在本段
 *       + 0.25 * tfSat * lenNorm // 出现次数（饱和）+ 段长归一，长段不天然占优
 *       + 0.30 * titleHit        // 标题命中是强信号（权重有依据：用户给文档起的名字）
 *       + 0.20 * phraseHit       // 查询原文连续出现在本段
 * 取值 0..1.30，不做归一化截断（保持可比较）。
 */
object KnowledgeScoring {
    const val COVERAGE_WEIGHT = 0.55
    const val TERM_FREQUENCY_WEIGHT = 0.25
    const val TITLE_WEIGHT = 0.30
    const val PHRASE_WEIGHT = 0.20
    private const val TF_SATURATION = 1.2

    fun score(
        title: String,
        text: String,
        queryTokens: List<String>,
        queryPhrase: String? = null,
    ): Double {
        if (queryTokens.isEmpty()) return 0.0
        val lowerText = text.lowercase()
        val lowerTitle = title.lowercase()

        var matched = 0
        var tfSum = 0.0
        for (token in queryTokens) {
            val tf = countOccurrences(lowerText, token.lowercase())
            if (tf > 0) matched++
            tfSum += tf / (tf + TF_SATURATION)
        }
        val coverage = matched.toDouble() / queryTokens.size
        val tfSat = tfSum / queryTokens.size
        val lenNorm = lengthNormalization(text.length)
        val titleHit = if (queryTokens.any { it.lowercase() in lowerTitle }) 1.0 else 0.0
        val phrase = queryPhrase
        val phraseHit = if (!phrase.isNullOrBlank() && phrase.length > 1 && lowerText.contains(phrase.lowercase())) {
            1.0
        } else {
            0.0
        }
        return COVERAGE_WEIGHT * coverage +
            TERM_FREQUENCY_WEIGHT * tfSat * lenNorm +
            TITLE_WEIGHT * titleHit +
            PHRASE_WEIGHT * phraseHit
    }

    /** 段长归一：约 900 字符最“饱满”，过短/过长都打折，但不为 0。 */
    fun lengthNormalization(charCount: Int): Double {
        if (charCount <= 0) return 0.0
        val ideal = 900.0
        val ratio = if (charCount <= ideal) charCount / ideal else ideal / charCount
        return ratio.coerceIn(0.35, 1.0)
    }

    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) break
            count++
            from = i + needle.length
        }
        return count
    }

    /**
     * 命中片段：以第一个命中 token 为中心取窗口，避免把整段塞进搜索结果。
     * 没有命中时退化为段首（例如 match 来自标题）。
     */
    fun snippet(
        text: String,
        queryTokens: List<String>,
        maxChars: Int = KnowledgeLimits.SNIPPET_MAX_CHARS,
    ): String {
        val flat = text.replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()
        if (flat.isEmpty()) return ""
        val lower = flat.lowercase()
        var hit = -1
        for (token in queryTokens) {
            if (token.isBlank()) continue
            val i = lower.indexOf(token.lowercase())
            if (i >= 0 && (hit < 0 || i < hit)) hit = i
        }
        if (hit < 0) hit = 0
        val windowStart = (hit - maxChars / 3).coerceIn(0, (flat.length - 1).coerceAtLeast(0))
        var windowEnd = (windowStart + maxChars).coerceAtMost(flat.length)
        if (windowEnd < flat.length && windowEnd > windowStart) {
            // 尽量不在词中间断开：往前找一个空格（最多回退 20 字）
            val space = flat.lastIndexOf(' ', windowEnd)
            if (space > windowStart + maxChars / 2) windowEnd = space
        }
        val prefix = if (windowStart > 0) "…" else ""
        val suffix = if (windowEnd < flat.length) "…" else ""
        return prefix + flat.substring(windowStart, windowEnd).trim() + suffix
    }
}
