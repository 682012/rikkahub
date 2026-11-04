package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.data.datastore.characterKey

/** 请求是怎么结束的；用户停、超时、窗口关掉，状态卡里会照实写出来。 */
enum class CompanionCancelReason { USER, TIMEOUT, LEFT_WINDOW }

/**
 * 小助手的一句台词是怎么来的。
 *
 * 三种来源最终都走同一个真实模型请求（[CompanionReplySource]），区别只在触发时机和去重策略：
 * [TAP] 和 [INPUT] 是用户主动操作，[EVENT] 是应用里发生的真实事件（生成开始/结束/出错/等审批）。
 */
enum class CompanionTriggerKind { TAP, INPUT, EVENT }

/** 自动互动的来源事件。刻意只描述"发生了什么"，不带聊天内容，见 [CompanionTrigger.detail]。 */
enum class CompanionEventKind {
    GENERATION_STARTED,
    GENERATION_FINISHED,
    GENERATION_FAILED,
    APPROVAL_PENDING,
}

/**
 * 一次触发。
 *
 * [detail] 是给模型看的一小段说明：手动输入时是用户那句话本身；自动事件只有一句固定描述，
 * **绝不**包含聊天正文、标题或系统提示词。悬浮窗只把事件本身和小助手自己的近期对话发出去。
 * [eventKey] 是自动事件的去重键（同一轮生成、同一个错误、同一个工具调用各算一次），手动触发为 null。
 */
data class CompanionTrigger(
    val kind: CompanionTriggerKind,
    val event: CompanionEventKind? = null,
    val detail: String = "",
    val eventKey: String? = null,
) {
    val isAuto: Boolean get() = kind == CompanionTriggerKind.EVENT
}

/** 小助手请求的真实阶段，全部由控制器状态机写入，没有动画定时器参与。 */
enum class CompanionAiPhase { IDLE, THINKING, GENERATING, COMPLETED, FAILED, CANCELLED }

/** 明确为什么没有发请求 / 请求为什么失败；界面据此显示原因和入口，不猜也不静默换模型。 */
sealed interface CompanionFailure {
    /** AI 回应没开（[CompanionModelMode.OFF]）。 */
    data object AiDisabled : CompanionFailure

    /** 选了模型但找不到它（被删掉或还没选）。 */
    data object ModelMissing : CompanionFailure

    /** 模型还在，但所属提供商配置没了。 */
    data class ProviderMissing(val modelLabel: String) : CompanionFailure

    /** 请求本身失败：网络、鉴权、服务端错误等。 */
    data class Request(val message: String) : CompanionFailure
}

/**
 * 这一次的模型到底是从哪来的。
 *
 * [GLOBAL_DEFAULT] 只允许出现在"完全没有会话"的预览态：用户选了跟随会话，但此刻没有会话可跟。
 * 这种情况必须显式标出来（状态卡写成"全局默认"），绝不能当成会话的模型静默发请求。
 */
enum class CompanionModelOrigin {
    /** 会话自己固定的模型（跟随会话，且会话读得到）。 */
    CONVERSATION,

    /** 用户在设置里指定的那一个模型。 */
    FIXED,

    /** 没有会话时的全局默认模型：只在预览态、并且明确标出来的时候才用。 */
    GLOBAL_DEFAULT,
}

/**
 * 一次模型解析的身份快照。
 *
 * 描述（界面上显示的）和真正发出去的请求必须是同一个模型：控制器把这份快照塞进
 * [CompanionRequest.expectedModel]，真实现解析完发现不一致就拒绝发请求，而不是拿新模型发旧请求。
 */
data class CompanionModelSnapshot(
    /** `Model.id`，模型被删掉/被换掉就会变。 */
    val modelId: Uuid,
    /** `Model.modelId`（服务商那边的模型名），便于日志与比对。 */
    val modelKey: String,
    /** 真正发请求用的提供商设置 id。 */
    val providerId: Uuid,
    val label: String,
)

/**
 * 当前会用的模型；[failure] 非空时表示用不了，[label] 为空表示压根没选。
 *
 * [origin] 说明这份模型是怎么定下来的；[conversationMissing] 表示"用户选了跟随会话，但那个会话
 * 读不到"——此时 [failure] 是明确的 [CompanionFailure.ModelMissing]，**不会**换成全局默认模型。
 * [snapshot] 是这个模型的解析快照，会被带进请求里做一致性校验。
 */
data class CompanionModelDescription(
    val label: String? = null,
    val source: CompanionModelMode? = null,
    val failure: CompanionFailure? = null,
    val origin: CompanionModelOrigin? = null,
    val conversationMissing: Boolean = false,
    val snapshot: CompanionModelSnapshot? = null,
) {
    val available: Boolean get() = label != null && failure == null
}

/** 被策略挡下的记录，显示在状态卡里，方便解释"为什么这次没说话"。 */
data class CompanionSkip(val reason: CompanionSkipReason, val atMillis: Long)

enum class CompanionSkipReason {
    /** 悬浮窗不可见（用户关掉了或回到前台）。 */
    NOT_VISIBLE,

    /** 已经有一个请求在跑。 */
    BUSY,

    /** AI 回应没开。 */
    AI_OFF,

    /** 自动互动开关没开。 */
    AUTO_EVENTS_OFF,

    /** 距离上一次同类自动互动还没到冷却时间。 */
    COOLDOWN,

    /** 同一个事件已经说过了。 */
    DUPLICATE_EVENT,

    /** 这一小时的自动互动预算用完了。 */
    BUDGET,
}

/** 界面要显示的一切：当前阶段、模型、最近一次触发、正文/思考、错误。 */
data class CompanionAiState(
    val phase: CompanionAiPhase = CompanionAiPhase.IDLE,
    val model: CompanionModelDescription = CompanionModelDescription(),
    val trigger: CompanionTrigger? = null,
    val text: String = "",
    val thinking: String = "",
    val failure: CompanionFailure? = null,
    val cancelReason: CompanionCancelReason? = null,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val skipped: CompanionSkip? = null,
) {
    val busy: Boolean get() = phase == CompanionAiPhase.THINKING || phase == CompanionAiPhase.GENERATING
}

/**
 * 一条历史记录。
 *
 * 只保留小助手自己说过的话和触发的来源，不复制聊天内容，面板里能直接看到"实际发生了什么"。
 */
data class CompanionInteraction(
    val trigger: CompanionTrigger,
    val phase: CompanionAiPhase,
    val text: String = "",
    val failure: CompanionFailure? = null,
    val modelLabel: String? = null,
    val atMillis: Long = 0L,
)

/** 记忆窗口里的一问一答；只存小助手自己的对话，不存聊天正文。 */
data class CompanionExchange(val prompt: String, val reply: String)

/** 小窗口记忆：最近几轮小助手自己的对话，超出容量就丢掉最旧的。 */
class CompanionMemory(private val capacity: Int = CompanionLimits.MEMORY_EXCHANGES) {
    private val exchanges = ArrayDeque<CompanionExchange>()

    fun record(prompt: String, reply: String) {
        if (capacity <= 0) return
        val trimmedPrompt = prompt.trim()
        val trimmedReply = reply.trim()
        if (trimmedPrompt.isEmpty() || trimmedReply.isEmpty()) return
        exchanges.addLast(CompanionExchange(trimmedPrompt, trimmedReply))
        while (exchanges.size > capacity) exchanges.removeFirst()
    }

    fun snapshot(): List<CompanionExchange> = exchanges.toList()

    val size: Int get() = exchanges.size

    fun clear() = exchanges.clear()
}

/** 小助手的硬限制，界面、控制器、测试共用。 */
object CompanionLimits {
    /** 记忆窗口：最近 4 轮小助手自己的对话。 */
    const val MEMORY_EXCHANGES = 4

    /** 用户一次能说的字数上限。 */
    const val MAX_INPUT_CHARS = 240

    /**
     * 单句台词的硬上限沿用160字，避免升级截断原本能显示的输入回应。
     *
     * 这是发现“写得比想要的长”的兜底，不是给模型的写作目标；目标是下面那个区间。
     */
    const val MAX_REPLY_CHARS = 160

    /**
     * 给模型的“通常区间”（中文字符）。
     *
     * r14 之前提示词里写的是“不超过 40 字符”，和内置人设自带的“6 到 24 字”两套窄数字互相打架，
     * 模型于是越写越短、越写越像口头禅。现在统一成 8–48：一句话（或相连两短句）够说完一层意思，
     * 又不至于占满气泡。
     */
    const val REPLY_TARGET_MIN_CHARS = 8
    const val REPLY_TARGET_MAX_CHARS = 48

    /** 每次点击的“即兴方向”说明的防御性上限；正常实现远低于它。 */
    const val TAP_HINT_MAX_CHARS = 240

    /** 每次请求的输出上限，和小助手"一句短话"的定位一致。 */
    const val MAX_REPLY_TOKENS = 320

    /** 一次请求最多等这么久，超时按"取消"记，不会一直挂着。 */
    const val REQUEST_TIMEOUT_MILLIS = 25_000L

    /** 面板里保留的最近互动条数。 */
    const val MAX_INTERACTIONS = 20

    /** 提示词里历史对话的总字符上限，避免小窗口变成大请求。 */
    const val MAX_HISTORY_CHARS = 1_200

    /** 失败原因显示/落内存前的硬上限：provider 的响应体不能整段搬到覆盖层上。 */
    const val MAX_FAILURE_CHARS = 160
}

/** 发给模型的消息角色。 */
enum class CompanionPromptRole { USER, ASSISTANT }

data class CompanionPromptMessage(val role: CompanionPromptRole, val text: String)

/**
 * 一次辅助请求的完整输入。
 *
 * 只有系统提示词和小助手自己的对话，没有聊天正文、标题、记忆文件或任何密钥：
 * 调用方没有任何字段可以塞进去，这一点在 [CompanionPromptBuilder.build] 的签名上就固定了。
 */
data class CompanionRequest(
    val systemPrompt: String,
    val history: List<CompanionPromptMessage>,
    val userPrompt: String,
    val sessionId: Uuid,
    /**
     * 这次请求必须用的模型快照（来自 [CompanionModelDescription.snapshot]）。
     *
     * 真实现（`CompanionReplyGenerator`）在发请求前会重新解析一次模型并和这份快照比对：
     * 用户在两次读取之间换了/删了模型或换了会话就拒绝发请求，不会"界面写 A、账单记 B"。
     * 为 null 时不校验（只给测试/第三方实现用）。
     */
    val expectedModel: CompanionModelSnapshot? = null,
    /**
     * r16：这一次请求实际用的角色解析结果（来源/版本/hash/全文）。
     *
     * 界面"查看当前生效提示词"和请求构造共用 [CompanionPersonas.resolve]，
     * 这里只是把它随请求带下去，方便日志、状态卡和排障核对，不会被另行改写。
     * 为 null 表示调用方没有传（只给测试/第三方实现用）。
     */
    val resolvedPersona: ResolvedCompanionPersona? = null,
)

/** 内置角色提示：选了立绘就能直接用，不需要手写。 */
object CompanionPersonas {
    /**
     * AI 提示词里用的兜底名字，和界面共用 [CompanionCatalog] 那一份：
     * 界面叫"小鲸"，提示词里就不能还自称 Marin。
     * 旧枚举 `CAT`/`DRAGON` 仍是小鲸/小白龙，`CUSTOM`/`LOGO` 没有内置角色。
     */
    fun defaultName(preset: FloatingCompanionPreset): String =
        CompanionCatalog.profile(preset)?.displayName ?: when (preset) {
            FloatingCompanionPreset.LOGO -> "Hub"
            else -> "Companion"
        }

    /** r16：只读内置目录的当前 revision；界面观察它，目录换版本时主动让旧 run 失效。 */
    val revision: StateFlow<Long> get() = CompanionPersonaCatalogStore.revision

    /**
     * 安装只读内置目录。只在 `Application.onCreate` 从 assets 装一次；测试可注入自己的一份。
     * 返回新的 revision，便于日志和测试断言。
     */
    fun install(catalog: CompanionPersonaCatalog): Long = CompanionPersonaCatalogStore.install(catalog)

    /**
     * **当前请求与界面共用的唯一解析入口**（r16）。
     *
     * 顺序固定：非空的自写覆盖整段优先（不 trim、不截断、不叠内置性格）；
     * 否则用当前渲染角色（[FloatingCompanionSettings.effectivePreset]）的完整内置卡；
     * 再没有就沿用 `CUSTOM`/`LOGO` 原来的短兜底。默认名字永远来自内置卡，
     * 不受另一只的自定义影响。
     */
    fun resolve(settings: FloatingCompanionSettings): ResolvedCompanionPersona {
        val owner = settings.characterKey
        val effectivePreset = settings.effectivePreset
        val entry = CompanionPersonaCatalogStore.catalog.entryForPreset(effectivePreset)
        // r17: UI 与请求统一使用当前正式角色名；非空的用户自定义名完全保留。
        val name = settings.name.trim().ifBlank { defaultName(effectivePreset) }
        val override = settings.personaOverrideOf(owner)
        if (override.isNotBlank()) {
            // 用户写的是这一整段：只做一次 {{char}}/{{user}} 替换，不 append 内置人设。
            val prompt = CompanionPersonaText.render(override, name)
            return ResolvedCompanionPersona(
                id = entry?.id,
                source = PersonaSource.USER_OVERRIDE,
                version = entry?.version.orEmpty(),
                hash = CompanionPersonaText.sha256(prompt),
                prompt = prompt,
                name = name,
                ownerCharacterKey = owner,
            )
        }
        if (entry != null) {
            val prompt = CompanionPersonaText.render(entry.promptTemplate, name)
            return ResolvedCompanionPersona(
                id = entry.id,
                source = PersonaSource.BUILTIN,
                version = entry.version,
                hash = CompanionPersonaText.sha256(prompt),
                prompt = prompt,
                name = name,
                ownerCharacterKey = owner,
            )
        }
        val fallback = builtIn(effectivePreset)
        return ResolvedCompanionPersona(
            id = null,
            source = PersonaSource.FALLBACK,
            version = "",
            hash = CompanionPersonaText.sha256(fallback),
            prompt = fallback,
            name = name,
            ownerCharacterKey = owner,
        )
    }

    /**
     * 内置卡在默认名字下的完整全文（不受用户自定义/改名影响）。
     *
     * 设置页"内置人设全文"和"以此为基础编辑"的初稿都取这一份；hash 与请求侧同一口径。
     */
    fun builtInResolved(preset: FloatingCompanionPreset): ResolvedCompanionPersona {
        val entry = CompanionPersonaCatalogStore.catalog.entryForPreset(preset)
        val owner = preset.characterKey
        if (entry != null) {
            val name = defaultName(preset)
            val prompt = CompanionPersonaText.render(entry.promptTemplate, name)
            return ResolvedCompanionPersona(
                id = entry.id,
                source = PersonaSource.BUILTIN,
                version = entry.version,
                hash = CompanionPersonaText.sha256(prompt),
                prompt = prompt,
                name = name,
                ownerCharacterKey = owner,
            )
        }
        val name = defaultName(preset)
        val fallback = builtIn(preset)
        return ResolvedCompanionPersona(
            id = null,
            source = PersonaSource.FALLBACK,
            version = "",
            hash = CompanionPersonaText.sha256(fallback),
            prompt = fallback,
            name = name,
            ownerCharacterKey = owner,
        )
    }

    /**
     * 完整个性提示。
     *
     * 六只都取 JSON 原文（见 [CompanionCatalog]），再叠一层 [CompanionRoleplayProfiles]
     * 的第二层人设（想要什么、小毛病、习惯、关系节奏）。原文的身份、名字和外观不变，
     * 用户自己写的 `aiPersonaPrompt` 仍然整段优先——那种情况下 `builtIn` 根本不会被调用。
     *
     * r16 起请求和界面走 [resolve]/[builtInResolved] 的完整卡，不再叠这层旧短人设；
     * 这里保留给旧调用点和 `CUSTOM`/`LOGO` 兜底，行为不变。
     */
    fun builtIn(preset: FloatingCompanionPreset): String {
        val base = CompanionCatalog.profile(preset)?.persona ?: when (preset) {
            FloatingCompanionPreset.LOGO ->
                "You are a friendly little assistant. You are practical, brief and cheerful."

            else ->
                "You are a friendly companion. You are warm, brief and attentive to the user."
        }
        val overlay = builtInOverlay(preset)
        return if (overlay.isBlank()) base.trim() else base.trimEnd() + "\n\n" + overlay
    }

    /** 内置角色在 [CompanionRoleplayProfiles] 里的 id；`CUSTOM`/`LOGO` 没有。 */
    fun roleplayId(preset: FloatingCompanionPreset): String? =
        CompanionCatalog.profile(preset)?.id

    /** 只有叠加层，不含 Catalog 原文；给测试和设置页预览用，未知角色返回空串。 */
    fun builtInOverlay(preset: FloatingCompanionPreset): String =
        CompanionRoleplayProfiles.textFor(roleplayId(preset))

    /** 设置页展示内置人设用的短标签（走资源）。 */
    fun summaryKey(preset: FloatingCompanionPreset): String = when (preset) {
        FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.DRAGON -> "dragon_girl"
        FloatingCompanionPreset.SUN_READER -> "sun_reader"
        FloatingCompanionPreset.WHALE_MAID, FloatingCompanionPreset.CAT -> "whale_maid"
        FloatingCompanionPreset.GEMINI_CAT -> "gemini_cat"
        FloatingCompanionPreset.GLM_FOX -> "glm_fox"
        FloatingCompanionPreset.QWEN_GIRL -> "qwen_girl"
        FloatingCompanionPreset.LOGO -> "logo"
        FloatingCompanionPreset.CUSTOM -> "custom"
    }
}

/**
 * 组装系统提示词和这一轮的用户消息。
 *
 * 刻意只接受个性、名字、触发和记忆窗口：聊天正文不在参数里，所以构造不出"把全文聊天发给另一个模型"。
 */
object CompanionPromptBuilder {
    /**
     * @param tapImprovHint 只在 [CompanionTriggerKind.TAP] 时生效：这一次点击的“即兴方向”
     *   （由 [CompanionTapImagination] 轮换给出，不含任何可播放台词）。默认 null，
     *   此时行为与 r14 一致，老调用点不用改。
     */
    fun build(
        persona: String,
        name: String,
        trigger: CompanionTrigger,
        memory: List<CompanionExchange>,
        sessionId: Uuid,
        languageTag: String,
        tapImprovHint: String? = null,
        useBuiltInStyle: Boolean = true,
        /** r16：这次解析出来的人设，随请求带下去便于核对；不参与系统提示词拼装。 */
        resolvedPersona: ResolvedCompanionPersona? = null,
    ): CompanionRequest {
        val system = buildSystemPrompt(persona, name, languageTag, useBuiltInStyle)
        // 预算是“从最新往旧装”的连续窗口：最近一轮优先留下，装不下就停，
        // 不会因为一条很长的旧对话把刚刚那一句挤掉（r14 是从旧往新装，最近一轮反而会丢）。
        val history = ArrayList<CompanionPromptMessage>()
        val kept = ArrayDeque<CompanionExchange>()
        var usedChars = 0
        val window = memory.takeLast(CompanionLimits.MEMORY_EXCHANGES)
        for (exchange in window.asReversed()) {
            val cost = exchange.prompt.length + exchange.reply.length
            if (usedChars + cost > CompanionLimits.MAX_HISTORY_CHARS) break
            kept.addFirst(exchange)
            usedChars += cost
        }
        kept.forEach { exchange ->
            history += CompanionPromptMessage(CompanionPromptRole.USER, exchange.prompt)
            history += CompanionPromptMessage(CompanionPromptRole.ASSISTANT, exchange.reply)
        }
        return CompanionRequest(
            systemPrompt = system,
            history = history,
            userPrompt = userPromptOf(trigger, tapImprovHint),
            sessionId = sessionId,
            resolvedPersona = resolvedPersona,
        )
    }

    /**
     * App 固定的系统边界 + 选中人设（r16）。
     *
     * 界面“展开完整生效提示词”直接调它，和 [build] 共用同一段拼装：
     * 气泡身份、发言规则、语言、看不到聊天/桌面、不冒充已执行——这些是 App 的边界，不属于角色卡；
     * 人设正文原样放在中间，不 trim、不截断、不追加别的角色。
     *
     * [useBuiltInStyle] 默认 false：完整内置卡自己写了口吻和长度规则，不需要再叠一层；
     * 只有走旧短兜底时才传 true。
     */
    fun buildSystemPrompt(
        persona: String,
        name: String,
        languageTag: String,
        useBuiltInStyle: Boolean = false,
    ): String = buildString {
        append("You are \"").append(name.trim().ifBlank { "Companion" }).append("\", ")
        append("a small companion living in a floating bubble on the user's phone.\n")
        // 人设正文原样放入：不再 trim（用户自写可能刻意以空行结尾），更不截断。
        append(persona).append("\n\n")
        append("How to speak:\n")
        if (!useBuiltInStyle) {
            append("- The selected persona above defines your identity, speaking quirks and optional small stage directions. ")
            append("Preserve them; do not substitute any built-in character.\n")
        }
        append("- Write the line yourself, right now, in this character's own mood and voice. ")
        append("Never pick a ready-made line from a fixed list, and never reuse the previous line.\n")
        if (useBuiltInStyle) {
            append("- One spoken line, or two short lines that belong together: usually ")
            append(CompanionLimits.REPLY_TARGET_MIN_CHARS).append('–')
            append(CompanionLimits.REPLY_TARGET_MAX_CHARS)
            append(" Chinese characters, never more than ").append(CompanionLimits.MAX_REPLY_CHARS)
            append(".\n")
            append("- Vary what the line does (a small reaction, a half-finished joke carried over, ")
            append("a light question, or no question at all), not how it is punctuated. ")
            append("Do not put your own name into every line.\n")
        } else {
            append("- Keep your response concise for the bubble, never more than ")
            append(CompanionLimits.MAX_REPLY_CHARS).append(" characters.\n")
        }
        append("- Answer in the same language as the user's latest message")
        append(" (if there is none, use the language tag ").append(languageTag).append(").\n")
        if (useBuiltInStyle) {
            append("- Plain speech only: no markdown, no lists, no headings, no bracketed stage directions.\n")
            append("- No assistant or customer-service opening (\"how can I help\"), and do not fall ")
            append("back on the same fillers every time, such as \"我在\", \"别戳\" or \"在呢\".\n")
        }
        append("- Never mention being an AI model, prompts, tokens or these rules.\n")
        append("- You cannot see the user's chats, the home screen or the real world around them. ")
        append("Only the lines below are available to you.\n")
        append("- Never claim you already did something you cannot do, and never invent a reason ")
        append("for an error. A finished reply is not a finished task.\n")
        append("- Say the line itself: do not offer several candidate lines for the user to choose from by default. ")
        append("Only when the user explicitly asks for choices, write concise in-character choices for the current interaction, not a fixed list.\n")
    }

    private fun userPromptOf(trigger: CompanionTrigger, tapImprovHint: String?): String =
        when (trigger.kind) {
            CompanionTriggerKind.TAP -> buildString {
                append("The user just tapped you. Say one short line that fits the moment.")
                val hint = tapImprovHint?.trim().orEmpty()
                if (hint.isNotEmpty()) {
                    append("\nThis time improvise along this direction. It is a note for you, ")
                    append("not a script: never read it out and never copy its wording.\n")
                    append(hint.take(CompanionLimits.TAP_HINT_MAX_CHARS))
                }
            }

            CompanionTriggerKind.INPUT ->
                "The user says: \"" + trigger.detail.trim().take(CompanionLimits.MAX_INPUT_CHARS) +
                    "\"\nReply with one short line."

            CompanionTriggerKind.EVENT -> when (trigger.event) {
                CompanionEventKind.GENERATION_STARTED ->
                    "A chat reply just started generating in the app. Say one short encouraging line."

                CompanionEventKind.GENERATION_FINISHED ->
                    "A chat reply just finished. Say one short line that fits the moment."

                CompanionEventKind.GENERATION_FAILED ->
                    "A chat reply just failed. Say one short comforting line."

                CompanionEventKind.APPROVAL_PENDING ->
                    "A tool is waiting for the user's approval. Say one short line reminding them gently."

                null -> "Something just happened in the app. Say one short line that fits the moment."
            }
        }
}

/** 把模型输出收成一句台词：最多两行、去引号、限长。 */
fun cleanCompanionLine(raw: String): String {
    val joined = raw.replace("\r", "")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString(" ")
        .trim()
        .trim('"', '\'', '“', '”', '「', '」', '*')
        .trim()
    return if (joined.length > CompanionLimits.MAX_REPLY_CHARS) {
        // 不要把一个代理对切成两半（会渲染成方块）
        var cut = CompanionLimits.MAX_REPLY_CHARS
        if (joined[cut - 1].isHighSurrogate()) cut -= 1
        joined.take(cut)
    } else {
        joined
    }
}

/**
 * `FOLLOW_CONVERSATION` 该怎么定模型：纯决策，没有任何 Android/IO 依赖，单测直接钉住决策表。
 *
 * 规则只有一条重要的：**有会话 ID 却读不到会话（或它的模型）时明确失败，绝不换模型**。
 * 只有在"压根没有会话"（预览态，比如刚把悬浮窗拖到桌面）时才允许用全局默认，
 * 且调用方必须把它标成 [CompanionModelOrigin.GLOBAL_DEFAULT]，让界面写清"这是全局默认"。
 */
enum class CompanionFollowDecision {
    /** 用会话自己的模型。 */
    CONVERSATION_MODEL,

    /** 完全没有会话：明确使用全局默认（预览态）。 */
    GLOBAL_DEFAULT_PREVIEW,

    /** 有会话 ID 但会话/它的模型读不到：明确失败。 */
    CONVERSATION_MISSING,

    /** 连全局默认都没有。 */
    MODEL_MISSING,
}

object CompanionFollowPolicy {
    fun decide(
        conversationId: Uuid?,
        conversationFound: Boolean,
        conversationModelFound: Boolean,
        globalDefaultFound: Boolean,
    ): CompanionFollowDecision = when {
        conversationId != null && (!conversationFound || !conversationModelFound) ->
            CompanionFollowDecision.CONVERSATION_MISSING

        conversationId != null -> CompanionFollowDecision.CONVERSATION_MODEL
        globalDefaultFound -> CompanionFollowDecision.GLOBAL_DEFAULT_PREVIEW
        else -> CompanionFollowDecision.MODEL_MISSING
    }
}

/**
 * 失败原因的脱敏与限长。
 *
 * provider 的异常 message 经常直接带上游响应体、完整 URL 甚至 `Bearer …`（比如
 * `Failed to get response: 401 {…}`）。这段文本会显示在悬浮于其他应用之上的窗口里，
 * 也会进内存里的互动记录，所以统一在这里过一遍：
 * - 换行/连续空白压成单空格；
 * - Bearer 令牌、`Authorization: …`、`api_key=…`、`sk-…` 一律打码；
 * - URL 里 key/token/signature 这类查询参数打码（`request_id` 之类保留，排障要用）；
 * - 超长 base64/十六进制块替换掉；
 * - 最后按 [CompanionLimits.MAX_FAILURE_CHARS] 截断，HTTP 状态码保留在文本开头。
 */
fun sanitizeCompanionFailureMessage(
    raw: String?,
    maxChars: Int = CompanionLimits.MAX_FAILURE_CHARS,
): String {
    val text = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (text.isEmpty()) return "request failed"
    var sanitized = text
    SECRET_PATTERNS.forEach { (pattern, replacement) ->
        sanitized = sanitized.replace(pattern, replacement)
    }
    sanitized = sanitized.trim()
    if (sanitized.isEmpty()) sanitized = "request failed"
    return if (sanitized.length > maxChars) {
        sanitized.take(maxChars - 1).trimEnd() + "…"
    } else {
        sanitized
    }
}

private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=\-]{8,}""") to "$1 [redacted]",
    Regex("""(?i)\b(authorization|proxy-authorization)\b\s*[:=]\s*[^\s,;]{4,}""") to "$1: [redacted]",
    Regex("""(?i)\b(sk|rk|pk|ak|gsk|hf)-[A-Za-z0-9_\-]{6,}""") to "[redacted-key]",
    Regex(
        """(?i)\b(api[-_]?key|x-api-key|access[-_]?token|refresh[-_]?token|client[-_]?secret|token|secret|password)\b\s*[:=]\s*"?[^\s,;"'}\]]{3,}""",
    ) to "$1=[redacted]",
    Regex("""(?i)([?&](?:key|api[-_]?key|token|access_token|sig|signature|secret)=)[^&\s)"']+""") to "$1[redacted]",
    Regex("""[A-Za-z0-9+/=_\-]{80,}""") to "[redacted]",
)

/** 自动互动的节流参数。默认值就是产品上的"不会一直花钱"。 */
data class CompanionPolicyConfig(
    /** 手动点击之间的最短间隔（挡住手抖连点；气泡本身还有 1.1 秒节流）。 */
    val manualCooldownMillis: Long = 1_000L,

    /** 两次自动互动之间至少隔这么久。 */
    val autoCooldownMillis: Long = 45_000L,

    /** 同一个事件（同一轮生成/同一个错误/同一个工具调用）多久内不重复说。 */
    val sameEventCooldownMillis: Long = 5 * 60_000L,

    /** 一小时内最多几次自动互动；超出后安静下来，等用户下次主动点它。 */
    val maxAutoPerHour: Int = 12,
)

/**
 * 触发闸门：去重、冷却、预算、在途只有一个。
 *
 * 纯内存、纯时间参数，单测可以直接推时间。它不产生任何定时器：
 * 只在"确实有事件发生"时被问一次，所以不存在后台轮询或空转花费。
 */
class CompanionTriggerPolicy(private val config: CompanionPolicyConfig = CompanionPolicyConfig()) {
    private var lastManualAt: Long? = null
    private var lastAutoAt: Long? = null
    private val lastAutoByKey = LinkedHashMap<String, Long>()
    private val recentAutoTimes = ArrayDeque<Long>()

    fun evaluate(
        trigger: CompanionTrigger,
        now: Long,
        visible: Boolean,
        aiEnabled: Boolean,
        autoEnabled: Boolean,
        busy: Boolean,
    ): CompanionSkipReason? {
        if (!visible) return CompanionSkipReason.NOT_VISIBLE
        if (!aiEnabled) return CompanionSkipReason.AI_OFF
        if (busy) return CompanionSkipReason.BUSY
        if (!trigger.isAuto) {
            val last = lastManualAt
            return if (last != null && now - last < config.manualCooldownMillis) {
                CompanionSkipReason.COOLDOWN
            } else {
                null
            }
        }
        if (!autoEnabled) return CompanionSkipReason.AUTO_EVENTS_OFF
        lastAutoAt?.let { last ->
            if (now - last < config.autoCooldownMillis) return CompanionSkipReason.COOLDOWN
        }
        trigger.eventKey?.let { key ->
            lastAutoByKey[key]?.let { last ->
                if (now - last < config.sameEventCooldownMillis) return CompanionSkipReason.DUPLICATE_EVENT
            }
        }
        pruneAutoTimes(now)
        if (recentAutoTimes.size >= config.maxAutoPerHour) return CompanionSkipReason.BUDGET
        return null
    }

    fun record(trigger: CompanionTrigger, now: Long) {
        if (!trigger.isAuto) {
            lastManualAt = now
            return
        }
        lastAutoAt = now
        val key = trigger.eventKey
        if (key != null) {
            lastAutoByKey[key] = now
            // 键会一直增长，顺手丢掉过期的
            val expired = lastAutoByKey.filterValues { now - it >= config.sameEventCooldownMillis }.keys
            expired.forEach(lastAutoByKey::remove)
        }
        pruneAutoTimes(now)
        recentAutoTimes.addLast(now)
    }

    fun reset() {
        lastManualAt = null
        lastAutoAt = null
        lastAutoByKey.clear()
        recentAutoTimes.clear()
    }

    private fun pruneAutoTimes(now: Long) {
        while (recentAutoTimes.isNotEmpty() && now - recentAutoTimes.first() >= HOUR_MILLIS) {
            recentAutoTimes.removeFirst()
        }
    }

    private companion object {
        const val HOUR_MILLIS = 60 * 60 * 1_000L
    }
}
