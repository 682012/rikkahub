package me.rerere.rikkahub.data.ai.learning

/**
 * 「每轮总结可复用经验」的提示词与输出解析。
 *
 * 约束：
 * - 输入只有客户端能核实的事实：终态、工具名（是否失败）、必要的短摘录；不带系统提示、不带上游历史；
 * - 输出是严格的行式 `KIND<TAB>TEXT` 文本协议，不解析 JSON——模型多写几个字不会让整次总结崩掉；
 * - 「没有可复用经验」是**首选答案**，必须在提示里明确比硬凑一条更好；
 * - 猜测一律丢弃：[ExperienceSanitizer.hasHedge] 兜底，提示词只是第一道。
 */
object ExperiencePromptBuilder {

    private const val SYNOPSIS_LIMIT = 600

    /** 总结请求的 system 提示。 */
    fun systemPrompt(): String = """
        你是经验提炼器。输入是刚刚结束的一轮任务的可观察事实，你的唯一输出是「下次能直接照做」的经验。

        只输出 0 到 ${LearningExperienceLimits.MAX_CANDIDATES} 行，每行格式严格为：
        KIND<TAB>TEXT
        其中 KIND 只能是 WORKFLOW 或 FACT，TEXT 是一句话（不超过 ${LearningExperienceLimits.MAX_LINE_CHARS} 字）。

        什么时候输出「没有」：这一轮没有出现可复用的做法或新的稳定事实，就只输出一行 NOEXPERIENCE。这比勉强凑一条更好。

        WORKFLOW（做法）的硬要求：
        - 必须能在事实里找到依据（哪个工具失败了、在哪一步停下的、什么顺序奏效），不许推测原因；
        - 写成下一步动作，例如「读大文件前先用 grep 定位，再分段读」；
        - 不要写「注意细节」「继续努力」「提高质量」这类无法执行的空话。

        FACT（事实）的硬要求：
        - 只有用户在这次任务里明确说过的稳定信息才算事实；你的推断、建议、计划都不是事实；
        - 一次性的口味、临时安排、可再查到的外部资料都不算；
        - 用户没说过的身份、健康、财务、关系、政治倾向一律不写。

        绝对禁止出现：证件号、银行卡号、账号、手机号、邮箱、密码、API key、完整姓名地址等可识别个人的信息；
        也禁止把工具返回值里的数据当成经验。含有这类内容的行会被整条丢弃。

        只输出结果行，不要标题、解释、代码块或编号。
    """.trimIndent()

    /** 把本轮事实拼成一段交给模型的输入。 */
    fun userPrompt(facts: TurnFacts): String = buildString {
        appendLine("<turn_facts>")
        appendLine("outcome: " + facts.outcome.name)
        facts.stopReason?.takeIf { it.isNotBlank() }?.let {
            appendLine("stop_reason: " + oneLine(it, 200))
        }
        if (facts.toolNames.isNotEmpty()) {
            appendLine("tools_used: " + facts.toolNames.joinToString(", "))
        }
        if (facts.failedToolNames.isNotEmpty()) {
            appendLine("tools_failed: " + facts.failedToolNames.joinToString(", "))
        } else {
            appendLine("tools_failed: none")
        }
        facts.userRequest?.takeIf { it.isNotBlank() }?.let {
            appendLine("user_request: " + oneLine(it, SYNOPSIS_LIMIT))
        }
        facts.assistantReply?.takeIf { it.isNotBlank() }?.let {
            appendLine("assistant_result: " + oneLine(it, SYNOPSIS_LIMIT))
        }
        appendLine("</turn_facts>")
        append("现在给出经验，或输出 NOEXPERIENCE。")
    }

    /**
     * 解析模型输出。
     *
     * 宽容解析但不放宽安全：格式不对的行直接跳过；清洗失败（敏感/推测/空话）的整条丢弃。
     * 结果去重、截到 [max] 条。
     */
    fun parse(
        raw: String,
        max: Int = LearningExperienceLimits.MAX_CANDIDATES,
    ): List<ExperienceCandidate> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<ExperienceCandidate>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim().trimStart('-', '*', '•').trim()
            if (trimmed.isEmpty()) continue
            val upper = trimmed.uppercase()
            if (upper.startsWith("NOEXPERIENCE") || upper == "NONE") return emptyList()
            val sep = trimmed.indexOfFirst { it == '\t' || it == ':' || it == '：' }
            if (sep <= 0) continue
            val kindToken = trimmed.take(sep).trim().uppercase()
                .removePrefix("[").removeSuffix("]").removeSuffix(":").trim()
            val kind = when (kindToken) {
                "WORKFLOW", "做法", "STEPS", "PROCESS" -> ExperienceKind.WORKFLOW
                "FACT", "事实" -> ExperienceKind.FACT
                else -> null
            } ?: continue
            val text = ExperienceSanitizer.acceptable(trimmed.substring(sep + 1)) ?: continue
            if (out.any { it.text == text || ExperienceSanitizer.isNearDuplicate(it.text, text) }) continue
            out += ExperienceCandidate(text = text, kind = kind)
            if (out.size >= max) break
        }
        return out
    }

    private fun oneLine(text: String, limit: Int): String =
        text.replace(Regex("[\\s\\u3000]+"), " ")
            .filterNot { it.isISOControl() }
            .trim()
            .take(limit)
}
