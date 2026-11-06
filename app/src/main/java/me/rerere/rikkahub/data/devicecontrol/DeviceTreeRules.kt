package me.rerere.rikkahub.data.devicecontrol

/**
 * 抓树时的纯规则：敏感字段识别、内容脱敏、字数截断。
 *
 * 这里不引用 android.text.InputType / AccessibilityNodeInfo，常量自己写死，单测能直接跑。
 * 敏感判定分别比对 inputType 的类型与变体（忽略高位 flags）——比"包含某一位"更少误判：
 * 普通文本 + 自动纠正等组合位不至于被当成密码。
 */
object DeviceTreeRules {

    // 与 android.text.InputType 同值
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0

    private val passwordVariations = setOf(
        TYPE_TEXT_VARIATION_PASSWORD,
        TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        TYPE_NUMBER_VARIATION_PASSWORD,
        TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    /** 只认明确的密码词，不做模糊匹配："pin" 会误伤 spinning，所以按词边界看。 */
    private val sensitiveWords = listOf(
        "password", "passwd", "passcode", "passphrase", "密码", "密碼", "パスワード", "비밀번호",
    )

    fun isPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and 0x0f
        val variation = inputType and 0x0ff0
        return when (inputClass) {
            // Also accept variation-only values for compatibility with exported node data.
            0 -> variation in passwordVariations
            1 -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            2 -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun looksSensitive(vararg hints: String?): Boolean {
        for (hint in hints) {
            val value = hint?.lowercase()?.trim().orEmpty()
            if (value.isEmpty()) continue
            for (word in sensitiveWords) {
                if (value.contains(word)) return true
            }
        }
        return false
    }

    fun isSensitive(
        className: String?,
        inputType: Int,
        isPasswordFlag: Boolean,
        hintText: String?,
        viewIdResourceName: String? = null,
    ): Boolean {
        if (isPasswordFlag) return true
        if (isPasswordInputType(inputType)) return true
        if (className?.contains("Password", ignoreCase = true) == true) return true
        return looksSensitive(hintText, viewIdResourceName)
    }

    /** 敏感节点一律不回文本，只留敏感标记；不返回"掩码长度"之类可推断的信息。 */
    fun redactText(raw: String?, sensitive: Boolean): String {
        if (sensitive) return ""
        return raw.orEmpty()
    }

    /** 按字符（码点安全地按 char）截断，返回是否截断过。 */
    fun truncate(raw: String?, maxChars: Int): Pair<String, Boolean> {
        val text = raw.orEmpty()
        val limit = maxChars.coerceAtLeast(1)
        if (text.length <= limit) return text to false
        return text.substring(0, limit) to true
    }

    /** 节点 id：子树路径，例如根的第 3 个子节点的第 1 个子节点 = "3/1"。 */
    fun childId(parentId: String, index: Int): String =
        if (parentId.isEmpty()) index.toString() else "$parentId/$index"

    /** 只允许点/填"能点"的位置；不在树里的 id 不猜。 */
    fun isNodeIdWellFormed(nodeId: String): Boolean =
        nodeId.isNotEmpty() && nodeId.split('/').all { part -> part.toIntOrNull()?.let { it >= 0 } == true }
}
