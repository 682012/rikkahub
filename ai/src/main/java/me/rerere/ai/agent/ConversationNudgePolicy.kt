package me.rerere.ai.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * m3「会话鞭策 / 推进提醒」的**纯**判断逻辑：给定本轮用户请求和助手消息，判断要不要追加一句推进语。
 *
 * 这个文件不碰 IO、不发请求、不读时钟，所以能直接单测（见 ConversationNudgePolicyTest）。
 *
 * 诚实边界（这里和 UI 文案写的是同一件事）：
 * - 只用**局部可见证据**：会话里最后一条用户消息是不是在要求真正动手、这条回复有没有产生任何工具调用、
 *   回复是不是又重复了同一套计划。不做额外的评审模型请求，因此不产生额外费用；
 * - 它**不能**可靠识别模型"偷懒"。误判方向是保守的：宁可少提醒一次，也不打扰正常对话；
 * - 明确放过：闲聊 / 短问答、创作与写作正文、只要方案而不要动手、权限不足、等用户补资料或确认、
 *   模型明确拒绝。
 */
@kotlinx.serialization.Serializable
enum class NudgeTone {
    /** 鼓励、具体，默认档：肯定已有进展，要求挑最短未完成步骤真正执行 */
    ENCOURAGING,

    /** 中性：只讲当前状态与下一步，不带情绪词 */
    NEUTRAL,
}

/**
 * 一次判断的输入，全部来自会话里已经存在的内容，不再额外查询。
 *
 * @param userRequest 本轮最后一条用户消息的纯文本（没有则空串）
 * @param replyText 本轮助手消息的纯文本部分（只用于识别"只说了计划"和重复计划）
 * @param toolCalls 本轮助手消息里的工具调用总数
 * @param executedToolCalls 其中已经有结果的调用数
 * @param noticeMarkers 本轮助手消息里已经带上的推送 marker（用来数已用次数，恢复后不清零）
 * @param maxAttempts 这条回复允许的推送次数，1..3
 * @param customInstruction 用户自己填的附加要求；null/空 = 用默认措辞
 */
data class NudgeRequest(
    val userRequest: String,
    val replyText: String,
    val toolCalls: Int = 0,
    val executedToolCalls: Int = 0,
    val noticeMarkers: List<String> = emptyList(),
    val maxAttempts: Int = ConversationNudge.DEFAULT_MAX_ATTEMPTS,
    val tone: NudgeTone = NudgeTone.ENCOURAGING,
    val customInstruction: String? = null,
)

/** 判断结果。[prompt] 为 null 表示不推送；[reason] 只在日志和测试里用，不给模型看。 */
data class NudgeDecision(
    val prompt: NudgePrompt?,
    val reason: String,
    /** 已经用掉的推送次数（含这条消息历史上留下的） */
    val usedAttempts: Int,
)

object ConversationNudge {
    /**
     * 写进 [UIMessagePart.Notice.metadata] 的键，用来给**每条回复**单独计数。
     * 计数落在消息本身上，所以落库恢复、重新加载之后再跑都不会把次数重置。
     */
    const val MARKER_KEY = "nudge"

    /** 每条回复默认最多推送 1 次；UI 允许 1..3 */
    const val DEFAULT_MAX_ATTEMPTS = 1
    val ATTEMPT_RANGE = 1..3

    private const val NOTICE_TITLE = "推进提醒"

    fun clampAttempts(value: Int): Int = when {
        value < ATTEMPT_RANGE.first -> ATTEMPT_RANGE.first
        value > ATTEMPT_RANGE.last -> ATTEMPT_RANGE.last
        else -> value
    }

    /** 一条 part 是不是推送留下的 Notice（按 metadata 里的 marker 判断） */
    fun isNudgeNotice(part: UIMessagePart): Boolean =
        part is UIMessagePart.Notice && part.metadata?.markerOrNull() != null

    fun usedAttempts(message: UIMessage): Int = message.parts.count { isNudgeNotice(it) }

    fun markersOf(message: UIMessage): List<String> =
        message.parts.filterIsInstance<UIMessagePart.Notice>().mapNotNull { it.metadata?.markerOrNull() }

    /**
     * 判断要不要推送。**默认什么都不做**：任何一条守卫不满足就返回 null prompt，
     * 调用方行为与没有这个功能时完全一致。
     */
    fun decide(request: NudgeRequest): NudgeDecision {
        val used = request.noticeMarkers.count { it.isNotBlank() }
        val budget = clampAttempts(request.maxAttempts)
        if (used >= budget) return NudgeDecision(null, "attempts-exhausted", used)
        // 已经有真实工具进展：这一轮本来就动了手，不是"只说不做"
        if (request.toolCalls > 0 || request.executedToolCalls > 0) {
            return NudgeDecision(null, "tool-progress", used)
        }
        val task = when (val t = classify(request.userRequest)) {
            is UserTask.Action -> t
            is UserTask.Chat -> return NudgeDecision(null, "not-an-action-request:${t.why}", used)
        }
        val repeatOnly = used > 0
        val reply = classifyReply(request.replyText)
        if (reply is ReplySignal.NoPlan && !repeatOnly) {
            return NudgeDecision(null, "reply-is-not-plan-only:${reply.why}", used)
        }
        val repeated = reply is ReplySignal.RepeatedPlan
        if (repeatOnly && !repeated) {
            // 已经提醒过一次，模型换了说法但还是没动手：第二次及以后只在重复同一套计划时才继续
            return NudgeDecision(null, "plan-changed-after-nudge", used)
        }
        return NudgeDecision(
            prompt = NudgePrompt(
                noticeTitle = NOTICE_TITLE,
                noticeText = instruction(task, request.tone, request.customInstruction, repeated = repeated),
                marker = "attempt-${used + 1}",
            ),
            reason = if (repeated) "repeated-plan" else "plan-only-no-tool",
            usedAttempts = used,
        )
    }

    /**
     * 用户这句话是不是在要求"真正动手"。命中显式行动动词才算；否定、澄清、讨论、创作类一律先拦下来。
     */
    fun classify(raw: String): UserTask {
        val text = raw.trim()
        if (text.isEmpty()) return UserTask.Chat("empty")
        // 创作/写作正文：即便出现"写/生成"也是在要文章，不是在要执行
        if (CREATIVE.any { it.containsMatchIn(text) }) return UserTask.Chat("creative")
        // 明确只要方案 / 先说别动手
        if (PLAN_ONLY_REQUEST.any { it.containsMatchIn(text) }) return UserTask.Chat("asked-for-plan-only")
        // 闲聊/问答类
        if (CHITCHAT_OPENING.any { it.containsMatchIn(text) }) return UserTask.Chat("chitchat")
        val verb = ACTION_VERB.firstOrNull { it.containsMatchIn(text) }
            ?: return UserTask.Chat(if (text.length <= 24) "short-chat" else "no-action-verb")
        // 有行动动词，但同时是在问"能不能/怎么样/为什么"：仍然是讨论
        if (DISCUSSION.any { it.containsMatchIn(text) }) return UserTask.Chat("discussion")
        return UserTask.Action(task = verb.find(text)?.value ?: "当前任务")
    }

    /**
     * 回复是不是"只说了计划、没有兑现"。
     *
     * 判定口径按证据强弱分三档：
     * - [ReplySignal.RepeatedPlan]：标题式计划（"计划""步骤""# Plan"）或连续出现多个步骤连接词
     *   （接下来/然后/下一步/最后…），这是"在复述计划"最强的证据；
     * - [ReplySignal.FutureOnly]：有未来时承诺（我会/我这就/let me…）且没有任何完成时证据；
     * - [ReplySignal.NoToolPlan]：没有工具调用、也没有规划措辞，但仍是够长的"这轮没动手"回复。
     *   这条最弱，只有第一次机会（[NudgeRequest.maxAttempts] 范围内）才会用到；
     * - 太短、有理由地停手（拒绝 / 要权限 / 要用户补资料）→ [ReplySignal.NoPlan]，一律不推。
     */
    fun classifyReply(raw: String): ReplySignal {
        val text = raw.trim()
        if (text.isEmpty()) return ReplySignal.NoPlan("empty-reply")
        if (text.length < MIN_PLAN_LENGTH) return ReplySignal.NoPlan("too-short")
        // 明确拒绝 / 需要用户补充资料 / 等确认 / 权限不足：有理由的停手，不推
        if (REASONED_STOP.any { it.containsMatchIn(text) }) return ReplySignal.NoPlan("reasoned-stop")
        if (PLAN_HEADING.any { it.containsMatchIn(text) } || stepChainCount(text) >= STEP_CHAIN_THRESHOLD) {
            return ReplySignal.RepeatedPlan(text.normalizePlan())
        }
        val future = FUTURE_PLAN.any { it.containsMatchIn(text) }
        val done = COMPLETION_EVIDENCE.any { it.containsMatchIn(text) }
        if (future && !done) return ReplySignal.FutureOnly
        return ReplySignal.NoToolPlan
    }

    private fun instruction(
        task: UserTask.Action,
        tone: NudgeTone,
        custom: String?,
        repeated: Boolean,
    ): String {
        val head = when (tone) {
            NudgeTone.ENCOURAGING ->
                "Nudge (not from the user). Your goal and the permissions you already have are unchanged; " +
                    "the work so far is fine to build on."

            NudgeTone.NEUTRAL ->
                "Nudge (not from the user). Current state: plan only, no tool call has run for this task."
        }
        val body = buildString {
            append(head)
            append(" The request \"")
            append(task.trimmedForNotice())
            append("\" asks for real work, but this reply is ")
            append(if (repeated) "another restatement of the same plan." else "only a plan or an intention.")
            append(" Do not restate the plan. Pick the single shortest remaining step you can actually do now")
            append(" and do it with your existing tools, then report the real result.")
            append(" If a step genuinely needs a permission you do not have, information only the user can supply,")
            append(" or explicit approval before a side effect, say that in one line and stop.")
        }
        val extra = custom?.trim().orEmpty()
        return if (extra.isEmpty()) body else "$body User's own extra instruction: $extra"
    }

    /** 抓一句短的请求原文放进提示语：太长会淹掉真正的指令 */
    private fun UserTask.Action.trimmedForNotice(): String {
        val oneLine = task.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 120) oneLine else oneLine.take(120) + "…"
    }
}

/** 用户这句话的性质：要动手，还是只是聊天 */
sealed interface UserTask {
    data class Action(val task: String) : UserTask
    data class Chat(val why: String) : UserTask
}

/** 回复的性质；只用于决定"要不要再给一次机会"，不代表对回复质量的评价 */
sealed interface ReplySignal {
    /** 有未来时承诺、没有完成证据 */
    data object FutureOnly : ReplySignal

    /** 保守口径：没有工具调用，先按"计划型回复"处理 */
    data object NoToolPlan : ReplySignal

    /** 标题式计划 / 步骤链：明显是在复述计划 */
    data class RepeatedPlan(val planKey: String) : ReplySignal

    /** 不是计划型回复：短文、提问、拒绝、需要资料 */
    data class NoPlan(val why: String) : ReplySignal
}

// 创作/写作要产出文字，不是要执行工具，先拦下来
private val CREATIVE = listOf(
    Regex("(写|创作|续写)(一|两|几)?(篇|首|段|个)?(诗|小说|文章|故事|文案|歌词|散文|稿|邮件|信)"),
    Regex("润色|改写|翻译|扩写|缩写|取个?(名字|标题)|起个?(名字|标题)|帮我写(一段|一篇|个)"),
    Regex(
        "\\b(write|draft|compose|polish|rewrite|translate)\\b[^.\\n]{0,20}" +
            "\\b(poem|story|essay|article|email|letter|copy|caption|lyrics?)\\b",
        RegexOption.IGNORE_CASE,
    ),
)

// 用户明确只要方案 / 先说别动手
private val PLAN_ONLY_REQUEST = listOf(
    Regex("(先|只)说(说)?(方案|思路|计划)|不要(动手|执行|改)|别(动手|执行|改)|先(给我)?(看|出)(个)?(方案|计划)"),
    Regex(
        "\\b(plan only|just (the )?plan|don't (run|execute|change)|do not (run|execute|change))\\b",
        RegexOption.IGNORE_CASE,
    ),
)

private val CHITCHAT_OPENING = listOf(
    Regex("^(你好|在吗|谢谢|早上好|晚上好|hi|hello|hey|thanks?)\\b", RegexOption.IGNORE_CASE),
    Regex("(聊聊|随便聊|你觉得呢|是什么意思|是什么|为什么|要不要)"),
)

// 要真正动手的显式动词（中英）
private val ACTION_VERB = listOf(
    Regex("修(一下|复|好|改)?|改(一下|成|掉)|实现|加上|添加|新增|删除|删掉|移除|重构|替换|升级|降级"),
    Regex("执行|跑(一下|一遍|起来|测试)|运行|构建|编译|打包|发布|部署|上线|安装|卸载|提交|合并|回滚|迁移|接入|接通"),
    Regex("写(代码|个|一下|段代码|一个)|写(脚本|函数|接口|测试)|创建|新建|生成(代码|文件|配置)|配置|接线"),
    Regex("检查|排查|调试|定位|查一下|看一下(日志|代码|文件)|扫描|测量|验证"),
    Regex(
        "\\b(fix|implement|add|remove|delete|refactor|rename|replace|upgrade|migrate|install|uninstall|create|generate|wire|configure)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        "\\b(run|exec|execute|build|compile|release|deploy|ship|commit|merge|rollback|debug|inspect|verify|check)\\b",
        RegexOption.IGNORE_CASE,
    ),
)

private val DISCUSSION = listOf(
    Regex("(能不能|可不可以|是否|怎么样|如何评价|有什么区别)|\\?|？"),
    Regex("\\b(should i|is it possible|what do you think|how about|explain|why)\\b", RegexOption.IGNORE_CASE),
)

// 回复里出现这些说明是有理由地停手，不推
private val REASONED_STOP = listOf(
    Regex("(我没有|没有)(权限|授权|访问)|权限不足|需要你(先)?(提供|补充|确认|授权)|缺少(必要)?(信息|权限)"),
    Regex("(无法|不能)(执行|继续|完成|操作)|做不到|需要(你|用户)(补充|提供|确认)"),
    Regex(
        "\\b(need (more )?information|need your (input|approval|confirmation)|do not have (the )?permission|" +
            "cannot|unable to|can't)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\b(i (won't|will not)|refuse|decline)\\b", RegexOption.IGNORE_CASE),
)

// 未来时承诺：只有这些出现、且没有完成证据时才算"只说了计划"
private val FUTURE_PLAN = listOf(
    Regex("(我会|我将|我这就|我马上|接下来|下一步|然后|首先|计划|打算|准备|即将)"),
    Regex("\\b(i will|i'll|i'm going to|next i|let me|going to|first i)\\b", RegexOption.IGNORE_CASE),
)

// 完成时证据：有它就不算"只说了计划"
private val COMPLETION_EVIDENCE = listOf(
    Regex("(已完成|已经完成|已修改|已执行|已运行|已创建|已添加|已删除|已提交|已部署|结果是|输出如下)"),
    Regex(
        "\\b(done|completed|finished|already (ran|executed|changed|created|added)|the result is|output:)\\b",
        RegexOption.IGNORE_CASE,
    ),
)

// 明显是列清单 / 计划的标题式回复
private val PLAN_HEADING = listOf(
    Regex("^\\s*(计划|步骤|方案|接下来要做的)"),
    Regex("^\\s*#{1,6}\\s*(plan|steps)\\b", RegexOption.IGNORE_CASE),
)

private val STEP_CHAIN = Regex("(接下来|然后|下一步|其次|最后|next|then|finally)", RegexOption.IGNORE_CASE)

/** 计划型回复最少要有这么长：太短的是正常短答，不打扰 */
private const val MIN_PLAN_LENGTH = 40

/** 命中多少个"步骤连接词"才算在列步骤链 */
private const val STEP_CHAIN_THRESHOLD = 3

private fun stepChainCount(text: String): Int = STEP_CHAIN.findAll(text).take(STEP_CHAIN_THRESHOLD).count()

private fun String.normalizePlan(): String =
    replace(Regex("[\\s*\\-\\d.、，,。:：]+"), "").lowercase().take(200)

private fun JsonObject.markerOrNull(): String? =
    (this[ConversationNudge.MARKER_KEY] as? JsonPrimitive)
        ?.takeUnless { it.isString && it.content.isBlank() }
        ?.content

/** 把 marker 写成 Notice 的 metadata：保存和恢复都靠它计数 */
fun nudgeMetadata(marker: String): JsonObject = buildJsonObject {
    put(ConversationNudge.MARKER_KEY, marker)
}

/** 把 [NudgePrompt] 变成真正要追加的 [UIMessagePart.Notice] */
fun NudgePrompt.toNoticePart(): UIMessagePart = UIMessagePart.Notice(
    title = noticeTitle,
    text = noticeText,
    metadata = nudgeMetadata(marker),
)
