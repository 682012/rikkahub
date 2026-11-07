package me.rerere.rikkahub.data.ai.inputassist

/** Conversation-local role instructions, kept separate from task/output contracts. */
object AssistRolePrompts {
    const val MAX_LENGTH = 8000
    fun normalize(value: String): String = value.trim().take(MAX_LENGTH)
    fun compose(base: String, role: String): String {
        val custom = normalize(role)
        if (custom.isEmpty()) return base
        return "$base\n\n用户配置的角色与偏好：\n$custom\n\n角色只补充职责和表达方式。仍须遵守前述任务边界与输出格式，不新增事实，不执行工具，也不声称未经验证的执行结果。"
    }
    /** Keep legacy default cache hits; different explicit models/roles cannot share results. */
    fun cacheInput(original: String, role: String, modelId: String?): String {
        val custom = normalize(role)
        if (custom.isEmpty() && modelId.isNullOrBlank()) return original
        return original + "\u0000optimizer-role=" + custom + "\u0000optimizer-model=" + modelId.orEmpty()
    }
}
