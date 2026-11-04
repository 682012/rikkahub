package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings

/**
 * 人设编辑草稿的纯逻辑（r16）。
 *
 * 刻意不碰 Compose、不碰 Android：长度口径、能不能保存、保存到哪一格都在这里说得清，
 * 界面只负责显示，单元测试直接跑这一份。
 *
 * 三条硬规则：
 * 1. 长度就是 Kotlin `String.length`（UTF-16 code units），不是字符数也不是模型 token 数；
 * 2. 超限不截断——只是明确地不允许保存，草稿原样留着；
 * 3. 保存认打开编辑器时记下的 [ownerCharacterKey] 那一格，不认"此刻选中的角色"。
 */
object CompanionPersonaDraft {
    /** 和保存入口同一份上限（`FloatingCompanionLimits.MAX_PERSONA_LENGTH`）。 */
    val limit: Int get() = FloatingCompanionLimits.MAX_PERSONA_LENGTH

    /** UTF-16 长度；emoji 这类补充平面字符会算 2。 */
    fun length(text: String): Int = text.length

    fun isOverLimit(text: String, limit: Int = this.limit): Boolean = text.length > limit

    /** 超了多少；没超返回 0。 */
    fun overBy(text: String, limit: Int = this.limit): Int = (text.length - limit).coerceAtLeast(0)

    /**
     * 能不能保存：非空且不超限。
     *
     * 空正文不是"恢复内置"——恢复内置有单独的确认入口，避免误删被人当成保存成功。
     */
    fun canSave(text: String, limit: Int = this.limit): Boolean =
        text.isNotBlank() && !isOverLimit(text, limit)

    /**
     * 保存到 [ownerCharacterKey] 那一格。
     *
     * 会先自己判一次 [canSave]：超限时抛 [IllegalArgumentException]，**绝不 take**，
     * 也不 trim——用户的第一行和末尾换行都按原样存。其他角色、名字、图片、模型、开关一律不动。
     */
    fun apply(
        companion: FloatingCompanionSettings,
        ownerCharacterKey: String,
        draft: String,
    ): FloatingCompanionSettings {
        require(canSave(draft)) {
            "persona draft is ${draft.length} UTF-16 units (limit $limit); it is not saved or truncated"
        }
        return companion.withPersonaOverride(ownerCharacterKey, draft)
    }

    /** 恢复内置：只清这一格的覆盖，值清空但不删其它字段。 */
    fun clear(companion: FloatingCompanionSettings, ownerCharacterKey: String): FloatingCompanionSettings =
        companion.withPersonaOverride(ownerCharacterKey, "")

    /** 打开编辑器时记下的基线，用来判断关掉时要不要确认。 */
    fun isDirty(baseline: String, draft: String): Boolean = baseline != draft
}
