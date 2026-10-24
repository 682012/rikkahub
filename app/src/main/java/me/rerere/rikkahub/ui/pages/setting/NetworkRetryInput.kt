package me.rerere.rikkahub.ui.pages.setting

/** "自动重试次数"的上限，与设置页 UI 和 Settings.retryPolicy() 的 coerceIn 保持一致。 */
internal const val MAX_RETRIES_LIMIT = 100

/**
 * 一次输入收敛后的结果。
 *
 * @property text 应该显示在输入框里的文本
 * @property value 应该落库的值；null 表示这次输入只是"清空待重输"，不改动已保存的设置
 */
internal data class MaxRetriesInput(val text: String, val value: Int?)

/**
 * 把输入框的原始文本收敛成"显示 == 保存"的一对值。
 *
 * fork.34 的问题：输入 999 时框里显示 999，实际存进去的是 100（coerceIn(0,100) 只作用在
 * 保存值上），界面和设置对不上。这里让显示文本和保存值出自同一个数。
 *
 * - 只保留数字，最多 3 位（防止粘贴一长串）
 * - 超过 [MAX_RETRIES_LIMIT] 按上限显示并保存
 * - 空串（或全是非数字）允许存在，此时不落库，方便用户清空后重新输入
 */
internal fun sanitizeMaxRetriesInput(raw: String): MaxRetriesInput {
    val digits = raw.filter { it.isDigit() }.take(3)
    if (digits.isEmpty()) return MaxRetriesInput(text = "", value = null)
    val value = digits.toInt().coerceIn(0, MAX_RETRIES_LIMIT)
    return MaxRetriesInput(text = value.toString(), value = value)
}
