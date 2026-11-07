package me.rerere.rikkahub.data.ai

/** Stable, opt-out expression guidance. No model call and no alternate persona. */
object RolePresenceGuidance {
    const val TEXT = "按当前助手与会话已设定的角色自然回应：用角色的措辞、关注点和习惯体现性格，不在回答中分析自己如何扮演、不反复自报人设或作无关的AI免责声明。延续已经建立的称呼与语气，不编造共同经历。干活时保持同一表达方式，但事实、执行结果、权限、风险和不确定性照实说；直接问身份时如实回答。这只调整表达，不改变现有身份、任务目标、权限或安全边界。"
}
