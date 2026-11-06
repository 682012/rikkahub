package me.rerere.rikkahub.data.ai.clientprofile

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.ConversationClientProfile

/**
 * 当前请求真正走的线协议。客户端兼容档只在 dialect 匹配时才能落地，
 * 不匹配就回原生 —— 绝不把一种协议的消息硬塞进另一种协议的请求体。
 */
enum class ProfileWireDialect {
    /** OpenAI /chat/completions 及其兼容网关 */
    CHAT_COMPLETIONS,

    /** OpenAI /responses */
    RESPONSES,

    /** Anthropic /messages */
    ANTHROPIC_MESSAGES,

    /** Google generateContent / interactions */
    GOOGLE_GENERATE_CONTENT,

    /** Web 账号桥接等拿不到公开协议的通道 */
    NONE,
}

/** 某个兼容档在某个 provider 上能落地多少。PARTIAL 只上不依赖协议的那一半。 */
enum class ProfileSupport { SUPPORTED, PARTIAL, UNSUPPORTED }

/**
 * 兼容档解析结果，纯数据，给 UI 和日志用。
 *
 * [effective] 与 [requested] 不同即表示手动指定的档被降级（只有降级到 NATIVE 一种情况），
 * [reason] 必须能独立读懂，不能是 "unsupported" 这种没头没尾的串。
 */
data class ClientProfileDecision(
    val requested: ConversationClientProfile,
    val effective: ConversationClientProfile,
    val dialect: ProfileWireDialect,
    val support: ProfileSupport,
    val auto: Boolean,
    val fellBack: Boolean,
    /** UI 显示用短标签，如 "Codex 风格（Responses）" */
    val label: String,
    /** 为什么是这个结果，中文，给用户看 */
    val reason: String,
    /** 同一 provider 上还能用的替代档（UNSUPPORTED 时用来提示），可为空 */
    val alternatives: List<ConversationClientProfile> = emptyList(),
)

/**
 * 加了兼容档之后的请求。
 *
 * [params] 就是要发出去的参数：`customHeaders` 已合入本档请求头（用户原有的头优先），
 * `customBody` 已合入协议层参数（用户原有的 body 优先）。调用方直接用它建请求即可。
 *
 * [providerSetting] 只是**建议**（一般为 null），永远是一份 copy，不落库、不影响其他会话。
 * [guidance] 是给系统提示追加的短引导，空串表示本档不加东西；它**不替换**用户提示词、不改身份。
 */
data class ProfiledRequest(
    val params: TextGenerationParams,
    val providerSetting: ProviderSetting?,
    val guidance: String,
    val effectiveProfile: ConversationClientProfile,
    val reason: String,
    val requestedProfile: ConversationClientProfile = effectiveProfile,
    val dialect: ProfileWireDialect = ProfileWireDialect.NONE,
    val support: ProfileSupport = ProfileSupport.SUPPORTED,
    val label: String = "",
    /** 实际加进 customHeaders 的头（已过用户优先/去重/CRLF/凭证保护） */
    val addedHeaders: List<CustomHeader> = emptyList(),
    /** 因为非法或属于凭证而被丢掉的档内头，只用于日志 */
    val droppedHeaders: List<String> = emptyList(),
    /** 实际加进 customBody 的键 */
    val addedBodyKeys: List<String> = emptyList(),
    /** 例如 "该模型可改用 Responses API 才能带上 Codex 专有参数"，仅提示，不自动改配置 */
    val suggestedApiChange: String? = null,
) {
    /** UI 直接拿这个判断是否要显示"已降级" */
    val fellBack: Boolean get() = effectiveProfile != requestedProfile

    /** 把本档结果套到另一份参数上（buildParams 里用）。用户已有的头/body 优先，可重复调用。 */
    fun applyTo(target: TextGenerationParams): TextGenerationParams = target.copy(
        customHeaders = sanitizeProfileHeaders(target.customHeaders, addedHeaders).merged,
        customBody = mergeProfileBodiesInto(
            target.customBody,
            params.customBody.filter { it.key in addedBodyKeys },
        ),
    )
}

/** 支持矩阵的一行，UI 设置页直接遍历渲染。 */
data class ClientProfileMatrixEntry(
    val profile: ConversationClientProfile,
    val label: String,
    val description: String,
    /** 该档需要的线协议，用于在 provider 不匹配时显示 "需要 X" */
    val requiresDialect: ProfileWireDialect?,
    val supportedProviders: String,
    /** 明确不做什么，避免用户以为这是官方客户端 */
    val limitation: String,
)

/** 供判断模型族用的本地提示串，不参与网络。 */
internal fun Model.familyHint(): String = "$modelId $displayName".lowercase()
