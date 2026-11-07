package me.rerere.rikkahub.data.ai.learning

import java.security.MessageDigest

/**
 * 经验文本的清洗、脱敏、去重判定。纯函数，不碰磁盘、不碰 Android，方便单测。
 *
 * 三道闸门，顺序不能换：
 * 1. **结构**：压成单行、去控制符/零宽字符、限长——防止把多行内容塞成假的多条或假指令；
 * 2. **脱敏**：命中证件号、银行卡、手机号、邮箱、密钥-like 串时**整条丢弃**，
 *    不试图「打码后保留」——被截断的号码仍是敏感信息；
 * 3. **事实与可复用性**：含猜测/不确定措辞的一律不收（不把模型的推测当用户事实），
 *    少于最短长度、只有「继续努力」这类空话的也不收。
 *
 * 去重用**归一化文本的 SHA-256 前 16 字节**：落盘只有指纹，没有第二条原文副本。
 */
object ExperienceSanitizer {

    /** 一条经验的最短有效长度：更短的多半是「继续努力」这类空话 */
    const val MIN_LINE_CHARS: Int = 8

    /** 宁可少记，也不收含糊说法。这些词出现即判定为推测，不是事实 */
    private val HEDGE_WORDS = listOf(
        "可能", "也许", "大概", "似乎", "好像", "或许", "估计", "推测", "猜测", "疑似", "或许",
        "probably", "maybe", "perhaps", "might be", "seems", "appears to", "presumably", "i think",
        "likely", "unclear", "not sure",
    )

    /** 空话/自我表功：没有可复用的做法 */
    private val FILLER = listOf(
        "继续努力", "更加注意", "保持耐心", "提高质量", "注意细节", "做得更好",
        "do better", "be careful", "pay attention", "improve quality",
    )

    private val WHITESPACE = Regex("[\\s\\u3000]+")

    // --- 敏感内容：命中即整条丢弃 ---
    private val CHINESE_ID =
        Regex("""(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    /** 手机号（中国 11 位） */
    private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    /** 较长的连续数字：卡号/账号 */
    private val LONG_DIGITS = Regex("""(?<!\d)\d{12,}(?!\d)""")
    private val SSN = Regex("""(?<!\d-)\d{3}-\d{2}-\d{4}(?!\d)""")
    /** 各类密钥/token 的常见形态 */
    private val SECRET = Regex(
        """(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{12,}|""" +
            """eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|""" +
            """(?:password|passwd|api[_-]?key|token|secret|口令|密码)\s*[:=：]\s*\S+)""",
        RegexOption.IGNORE_CASE,
    )
    /** 绝对路径里的用户名、主目录一类可识别身份的信息 */
    private val USER_HOME = Regex("""(?:/home/|/Users/|[A-Za-z]:\\Users\\)([^/\s\\]+)""")

    /** 归一化：压单行、去不可见字符、限长。空串表示这条不可用。 */
    fun normalize(raw: String): String {
        val flat = raw
            .filterNot { it.isInvisible() }
            .replace(WHITESPACE, " ")
            .trim()
            .trimStart('-', '*', '•', '·', ' ', '\t')
            .trim()
        if (flat.isEmpty()) return ""
        if (flat.length <= LearningExperienceLimits.MAX_LINE_CHARS) return flat
        val max = LearningExperienceLimits.MAX_LINE_CHARS
        val cut = if (flat[max - 1].isHighSurrogate()) max - 1 else max
        return flat.take(cut).trimEnd()
    }

    /**
     * 清洗 + 判定。
     *
     * @return 可用时返回归一化后的文本；不可用时返回 null（调用方直接跳过，不写任何东西）
     */
    fun acceptable(raw: String): String? {
        val text = normalize(raw)
        if (text.length < MIN_LINE_CHARS) return null
        if (containsSensitive(text)) return null
        if (hasHedge(text)) return null
        if (isFiller(text)) return null
        return text
    }

    /** 是否含禁止存储的敏感内容（证件号、卡号/账号、手机号、邮箱、密钥、用户主目录身份）。 */
    fun containsSensitive(text: String): Boolean =
        CHINESE_ID.containsMatchIn(text) ||
            SSN.containsMatchIn(text) ||
            EMAIL.containsMatchIn(text) ||
            PHONE.containsMatchIn(text) ||
            LONG_DIGITS.containsMatchIn(text) ||
            SECRET.containsMatchIn(text) ||
            USER_HOME.containsMatchIn(text)

    /** 是否含推测性措辞：是则不能当事实写进记忆或知识库。 */
    fun hasHedge(text: String): Boolean {
        val lower = text.lowercase()
        return HEDGE_WORDS.any { lower.contains(it) }
    }

    private fun isFiller(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('。', '.', '！', '!')
        return FILLER.any { lower == it }
    }

    /**
     * 去重指纹：归一化（小写、只留字母数字）后取 SHA-256 前 16 字节的十六进制。
     *
     * 归一化让「先读报错再重试」和「先读报错，再重试。」归为同一条。
     */
    fun fingerprint(text: String): String {
        val canonical = text.lowercase().filter { it.isLetterOrDigit() }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * 近似重复：两条经验归一化字符集的重合度 ≥ 阈值即视为同一条。
     *
     * 只用于同一轮里两条候选互相重复的判定，不拿它当语义相似度——那不是这个函数能保证的事。
     */
    fun isNearDuplicate(a: String, b: String, threshold: Double = 0.85): Boolean {
        val left = canonicalSet(a)
        val right = canonicalSet(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val overlap = left.count { it in right }.toDouble()
        val smaller = minOf(left.size, right.size).toDouble()
        return overlap / smaller >= threshold
    }

    private fun canonicalSet(text: String): Set<Int> =
        text.lowercase().filter { it.isLetterOrDigit() }.map { it.code }.toSet()

    private fun Char.isInvisible(): Boolean {
        val code = code
        return isISOControl() || code == 0xFEFF || code in 0x200B..0x200F || code in 0x202A..0x202E
    }
}
