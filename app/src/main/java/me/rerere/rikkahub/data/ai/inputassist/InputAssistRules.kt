package me.rerere.rikkahub.data.ai.inputassist

import java.security.MessageDigest
import kotlin.uuid.Uuid

/** 遮住代码块之后的原文：发给优化模型的是占位符，写回时必须是原样代码。 */
data class MaskedInput(
    val core: String,
    val codeBlocks: List<String>,
)

/** [InputAssistRules.parseReview] 的结构化结果。 */
data class ParsedReview(
    val verdict: InputReviewVerdict,
    val findings: List<String>,
    val suggestion: String?,
    val limits: String,
)

/**
 * 输入优化/审查里所有能离线判断的规则都放这里：不碰网络、不碰 Android、不碰 Compose。
 *
 * 三条底线写在这份文件里，因为它们最容易在"顺手改一下提示词"时被破坏：
 * - 用户原话里的数字、路径、引号内原话、行内代码和代码块**必须**原样保留，缺一个就整段作废回退原话；
 * - 优化只能把话说清楚，不许替用户新增授权、目标、范围或完成标准；
 * - 审查只能给文本意见，不许假装执行过编译/测试。
 */
object InputAssistRules {
    /** 超过这个长度的消息不优化：快速模型读不完，改坏的风险也更大 */
    const val MAX_INPUT_CHARS = 12_000

    /** 优化结果的硬上限，超过直接判失败回退原话 */
    const val MAX_OUTPUT_CHARS = 20_000

    /** 短于这个长度的消息没什么可澄清的 */
    const val MIN_INPUT_CHARS = 4

    /** 优化请求的默认超时：超时就按原话继续，不拖慢这一轮 */
    const val DEFAULT_TIMEOUT_MILLIS = 15_000L

    /** 独立审查的超时，比优化宽一点（它要看完整回复） */
    const val REVIEW_TIMEOUT_MILLIS = 30_000L

    const val CODE_PLACEHOLDER_PREFIX = "⟦代码块"

    private const val MAX_PRESERVED_TOKENS = 60
    private val HEX = "0123456789abcdef".toCharArray()
    private val PLACEHOLDER = Regex("⟦代码块(\\d+)⟧")
    private val FENCED = Regex("(?s)(`{3,}|~{3,}).*?\\1")
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val PATH = Regex("(?:[\\w.\\-]+/)+[\\w.\\-]*")
    private val QUOTED = Regex("[\"“「']([^\"”」'\\n]{2,120})[\"”」']")
    private val INLINE_CODE = Regex("`[^`\\n]{1,200}`")
    private val REFUSALS = listOf(
        "抱歉", "对不起", "很抱歉", "无法完成", "我不能", "作为ai", "作为 ai",
        "cannot", "i can't", "i cannot", "as an ai", "sorry,",
    )
    private val EMPTY_FINDINGS = setOf("无", "没有", "none", "n/a", "na", "nothing", "-")

    /** 原文指纹：缓存和"消息是否被改过"都用它判断，不存原文副本。 */
    fun inputHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * 一条用户消息的规范文本：只取真正的 Text part，按顺序用一个换行连接。
     *
     * 不能用 `UIMessage.toText()` 充当指纹来源：它给每个非文本 part 也留了一个分隔符，
     * 「文字 + 图片 + 文字」会被拼成 `a\n\nb`，而这里得到 `a\nb`。两边不一致时，
     * 定轮次算出的指纹和请求改写算出的指纹永远对不上，带附件的消息会静默地失去优化。
     * 定轮次、缓存键、请求改写三处**只能**通过这里取文本/指纹。
     */
    fun canonicalUserText(textParts: List<String>): String = textParts.joinToString("\n")

    /** 规范文本的指纹；与 [canonicalUserText] 一起构成唯一的"这段话有没有变"判断。 */
    fun canonicalTextHash(textParts: List<String>): String =
        inputHash(canonicalUserText(textParts).trim())

    /**
     * Text part 是否连成一段（中间没有夹附件）。
     *
     * 整体折叠成一段只有在连成一段时才保序：`[文字, 图片, 文字]` 折叠后第二段文字会跑到图片前面，
     * 等于改了用户消息的顺序，所以这种布局直接跳过优化（只保留零请求的自检）。
     */
    fun textPartsContiguous(isTextPart: List<Boolean>): Boolean {
        val first = isTextPart.indexOfFirst { it }
        if (first < 0) return false
        val last = isTextPart.lastIndexOf(true)
        return isTextPart.subList(first, last + 1).all { it }
    }

    /**
     * 审查缓存键：助手 + 会话 + 用户消息 id/指纹 + 回复 id/指纹。
     *
     * 少任何一段都会让"换了助手"或"换了回复"复用别人的审查结论，所以不许退回旧键。
     */
    fun reviewCacheKey(
        assistantId: Uuid,
        conversationId: Uuid,
        userMessageId: Uuid?,
        userText: String,
        replyMessageId: Uuid?,
        replyText: String,
    ): String = buildString {
        append(assistantId).append(':')
        append(conversationId).append(':')
        append(userMessageId ?: "-").append(':')
        append(inputHash(userText.trim())).append(':')
        append(replyMessageId ?: "-").append(':')
        append(inputHash(replyText.trim()))
    }

    /** 这段文本值不值得为它发一次优化请求。 */
    fun optimizeEligible(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_INPUT_CHARS || trimmed.length > MAX_INPUT_CHARS) return false
        // 全是代码块的消息没有可澄清的散文，优化只会糟蹋代码
        val prose = PLACEHOLDER.replace(maskCodeBlocks(trimmed).core, "").trim()
        return prose.length >= MIN_INPUT_CHARS
    }

    /** 把围栏代码块换成占位符；块本身完整留在 [MaskedInput.codeBlocks] 里。 */
    fun maskCodeBlocks(text: String): MaskedInput {
        val blocks = mutableListOf<String>()
        val core = StringBuilder(text.length)
        var last = 0
        for (match in FENCED.findAll(text)) {
            if (match.range.first < last) continue
            core.append(text, last, match.range.first)
            blocks += match.value
            core.append(CODE_PLACEHOLDER_PREFIX).append(blocks.size).append('⟧')
            last = match.range.last + 1
        }
        core.append(text, last, text.length)
        return MaskedInput(core.toString(), blocks)
    }

    /**
     * 把占位符还原成原代码块。
     *
     * 占位符数量或编号对不上（模型删了、加了、改了编号）就返回 null：宁可用原话，
     * 也不能让模型手里那版半截代码成为用户真正发出去的内容。
     */
    fun restoreCodeBlocks(optimized: String, masked: MaskedInput): String? {
        val found = PLACEHOLDER.findAll(optimized).map { it.groupValues[1] }.toList()
        if (masked.codeBlocks.isEmpty()) return if (found.isEmpty()) optimized else null
        val expected = (1..masked.codeBlocks.size).map { it.toString() }
        if (found.size != expected.size) return null
        if (found.sorted() != expected.sorted()) return null
        var result = optimized
        for (index in expected.indices) {
            val token = CODE_PLACEHOLDER_PREFIX + expected[index] + "⟧"
            result = result.replace(token, masked.codeBlocks[index])
        }
        return result
    }

    /** 必须逐字出现在优化结果里的片段：数字、路径、引号内原话、行内代码。 */
    fun preservedTokens(text: String): List<String> {
        val core = maskCodeBlocks(text).core
        val tokens = LinkedHashSet<String>()
        NUMBER.findAll(core).forEach { tokens += it.value }
        PATH.findAll(core).forEach { tokens += it.value }
        QUOTED.findAll(core).forEach { tokens += it.groupValues[1].trim() }
        INLINE_CODE.findAll(core).forEach { tokens += it.value }
        return tokens
            .filter { token -> token.isNotEmpty() && (token[0].isDigit() || token.length >= 3) }
            .take(MAX_PRESERVED_TOKENS)
    }

    /**
     * 守卫优化结果。返回 null = 这一版不能用在请求里。
     *
     * 只做"是否可信"的判断，不做润色：调用方拿到非 null 后才知道能替换。
     */
    fun sanitizeOptimized(raw: String, original: String, masked: MaskedInput): String? {
        var text = stripOuterFence(raw.trim())
        if (text.isEmpty()) return null
        if (text.length > MAX_OUTPUT_CHARS) return null
        val head = text.take(80).lowercase()
        if (REFUSALS.any { head.contains(it) }) return null
        val restored = restoreCodeBlocks(text, masked) ?: return null
        val missing = preservedTokens(original).filterNot { restored.contains(it) }
        if (missing.isNotEmpty()) return null
        return restored.trim().ifEmpty { null }
    }

    private fun stripOuterFence(text: String): String {
        val marker = when {
            text.startsWith("```") -> "```"
            text.startsWith("~~~") -> "~~~"
            else -> return text
        }
        val end = text.trimEnd()
        if (!end.endsWith(marker) || end.length <= marker.length * 2) return text
        val inner = end.removeSuffix(marker)
        val newline = inner.indexOf('\n')
        if (newline == -1) return text
        val body = inner.substring(newline + 1)
        // 里面还有围栏说明这是真正的代码块，不是"把整段包起来"的包装
        if (body.contains(marker)) return text
        return body.trim()
    }

    /**
     * SELF_CHECK 的尾部提示：跟着当轮请求走，不额外发请求，也不要求模型改输出格式。
     *
     * 它只让同一个模型在回答前对一遍用户的硬要求，因此**不是**独立验证。
     */
    fun selfCheckTail(): String = buildString {
        append("【本轮自检·同一模型，不是独立验证】")
        append("回答前先自己核对：")
        append("1) 用户这条消息里的全部要求、数字、文件路径、限制是否都被照顾到；")
        append("2) 有没有把没验证过的结论说成\"已测试/已运行/已确认\"，有就改成实际状态；")
        append("3) 信息不足时直接说明缺什么，不要编造。")
        append("不要因为这段提示改变回答格式，也不要在回答里复述这段要求。")
    }

    /** 优化请求的系统提示词：只澄清不改意，不新增授权/目标。 */
    fun optimizationSystemPrompt(): String = buildString {
        appendLine("你是「用户消息改写器」。把用户这一条消息改写成更明确、结构化、可直接执行的请求，交给另一个模型执行。")
        appendLine("规则：")
        appendLine("1. 只做澄清与结构化：把歧义处说清楚，必要时拆成目标/约束/验收点，不改变原意。")
        appendLine("2. 必须逐字保留：所有数字与单位、文件路径、引号内的原话、专有名词、限制条件和否定句。")
        appendLine("3. 不得替用户新增授权、目标、范围或完成标准；不得替用户答应任何事；不得编造事实、数据或接口。")
        appendLine("4. 代码块已替换成 ⟦代码块N⟧ 占位符：每个占位符原样保留一次，不得修改、删除、新增占位符。")
        appendLine("5. 不要解释、不要加开场白或结尾、不要写\"以下是改写\"，直接输出改写后的消息正文。")
        appendLine("6. 原话已经足够明确时，原样输出。")
        append("7. 不要输出任何关于你自己或本任务的元信息。")
    }

    /** 优化请求的用户消息：只有正文（代码已遮住），附件一律不进这条请求。 */
    fun optimizationUserPrompt(masked: MaskedInput): String = buildString {
        appendLine("【用户消息】")
        append(masked.core.trim())
    }

    /** 独立审查的提示词：只读文本、只给意见，不执行、不重放、不改写。 */
    fun reviewPrompt(userText: String, replyText: String): String = buildString {
        appendLine("你是独立的对话结果审查者。只读下面两段文本，判断助手是否真正满足了用户这条消息的要求。")
        appendLine("你不执行任何工具、不重放任务、不替助手补做工作、不改写助手的回答。")
        appendLine("严格按下面格式输出，不要输出其它内容：")
        appendLine("VERDICT: OK | CONCERN | RISK")
        appendLine("FINDINGS:")
        appendLine("- 每条一行，指出具体风险或没被满足的要求；没有就写 - 无")
        appendLine("SUGGESTION: 一句话给出最小修正建议；没有就写 无")
        appendLine("LIMITS: 用一句话说明你只看了文本，无法确认是否真的编译/运行/测试过")
        appendLine()
        appendLine("【用户要求】")
        appendLine(userText.trim())
        appendLine()
        appendLine("【助手回答】")
        appendLine(replyText.trim())
    }

    /** 解析审查输出；一个标记都认不出来时返回 null（调用方按失败处理，不编结论）。 */
    fun parseReview(raw: String): ParsedReview? {
        val lines = raw.lines().map { it.trim().trimStart('*', '#').trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        var verdict = InputReviewVerdict.UNKNOWN
        val findings = mutableListOf<String>()
        val suggestion = StringBuilder()
        val limits = StringBuilder()
        var section = ""
        var sawMarker = false
        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.startsWith("VERDICT") -> {
                    sawMarker = true
                    section = "verdict"
                    verdict = verdictOf(afterLabel(line))
                }

                upper.startsWith("FINDINGS") -> {
                    sawMarker = true
                    section = "findings"
                    afterLabel(line).takeIf { it.isNotEmpty() }?.let { findings += cleanFinding(it) }
                }

                upper.startsWith("SUGGESTION") -> {
                    sawMarker = true
                    section = "suggestion"
                    suggestion.append(afterLabel(line))
                }

                upper.startsWith("LIMITS") -> {
                    sawMarker = true
                    section = "limits"
                    limits.append(afterLabel(line))
                }

                line.startsWith("-") || line.startsWith("•") || line.startsWith("*") -> {
                    if (section == "findings") findings += cleanFinding(line.drop(1).trim())
                }

                else -> when (section) {
                    "findings" -> findings += cleanFinding(line)
                    "suggestion" -> suggestion.append(' ').append(line)
                    "limits" -> limits.append(' ').append(line)
                    else -> Unit
                }
            }
        }
        val cleaned = findings
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in EMPTY_FINDINGS }
            .distinct()
            .take(6)
        if (!sawMarker && cleaned.isEmpty()) return null
        return ParsedReview(
            verdict = verdict,
            findings = cleaned,
            suggestion = suggestion.toString().trim().take(600)
                .takeIf { it.isNotEmpty() && it.lowercase() !in EMPTY_FINDINGS },
            limits = limits.toString().trim().take(600),
        )
    }

    private fun verdictOf(value: String): InputReviewVerdict {
        val text = value.uppercase()
        return when {
            text.contains("RISK") || text.contains("风险") -> InputReviewVerdict.RISK
            text.contains("CONCERN") || text.contains("注意") -> InputReviewVerdict.CONCERN
            text.contains("OK") || text.contains("通过") || text.contains("满足") -> InputReviewVerdict.OK
            else -> InputReviewVerdict.UNKNOWN
        }
    }

    private fun afterLabel(line: String): String {
        val index = line.indexOfFirst { it == ':' || it == '：' }
        return if (index == -1) "" else line.substring(index + 1).trim()
    }

    private fun cleanFinding(text: String): String = text.trim().take(400)
}
