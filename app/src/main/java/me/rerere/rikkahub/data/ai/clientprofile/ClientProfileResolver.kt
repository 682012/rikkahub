package me.rerere.rikkahub.data.ai.clientprofile

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.ConversationClientProfile

/**
 * 客户端协议兼容档的选择与落地。
 *
 * 全部是纯函数：同样的 (provider 设置, 模型, 用户选择) 永远给同一个结果，
 * 不发探测请求、不调模型、不读磁盘、不看网络 —— 同一轮对话里多次调用也稳定，
 * AUTO 也不会为了"挑一个"而多花一次请求。
 *
 * 能落地的只有公开线协议层面的东西：非凭证请求头、协议层请求体参数、工具用法引导。
 * 不伪造账户 / API Key / 签名 / origin 权限，也不对服务端验证做任何承诺。
 */
object ClientProfileResolver {

    /** 主入口：把选中的档套到要发出去的参数上。 */
    fun applyProfile(
        params: TextGenerationParams,
        setting: ProviderSetting,
        profile: ConversationClientProfile,
    ): ProfiledRequest {
        val decision = resolve(setting, params.model, profile)
        val effective = decision.effective
        if (effective == ConversationClientProfile.NATIVE) {
            return ProfiledRequest(
                params = params,
                providerSetting = null,
                guidance = "",
                effectiveProfile = effective,
                reason = decision.reason,
                requestedProfile = profile,
                dialect = decision.dialect,
                support = decision.support,
                label = decision.label,
            )
        }

        val dialect = decision.dialect
        val merge = sanitizeProfileHeaders(
            userHeaders = params.customHeaders,
            profileHeaders = profileHeadersFor(effective, dialect),
        )
        val profileBodies = profileBodyParams(effective, dialect, params.sessionId)
        val bodies = mergeProfileBodiesInto(params.customBody, profileBodies)
        val addedBodies = bodies.drop(params.customBody.size)

        return ProfiledRequest(
            params = params.copy(customHeaders = merge.merged, customBody = bodies),
            // 永远不自动换 provider：改 API 类型是用户配置层面的决定，
            // 换了会让只支持 /chat/completions 的网关直接 404，所以只给文字建议
            providerSetting = null,
            guidance = guidanceFor(effective),
            effectiveProfile = effective,
            reason = decision.reason,
            requestedProfile = profile,
            dialect = dialect,
            support = decision.support,
            label = decision.label,
            addedHeaders = merge.added,
            droppedHeaders = merge.dropped,
            addedBodyKeys = addedBodies.map { it.key },
            suggestedApiChange = suggestedApiChange(setting, effective, dialect),
        )
    }

    /** 这一步/这一轮最终用哪个档；不修改任何参数，给 UI 显示和日志用。 */
    fun resolve(
        setting: ProviderSetting,
        model: Model,
        requested: ConversationClientProfile,
    ): ClientProfileDecision {
        val dialect = dialectOf(setting)

        if (requested == ConversationClientProfile.AUTO) {
            val (pick, why) = autoSelect(setting, model)
            val pickSupport = support(setting, pick)
            return ClientProfileDecision(
                requested = requested,
                effective = pick,
                dialect = dialect,
                support = pickSupport,
                auto = true,
                fellBack = false,
                label = labelFor(pick, pickSupport),
                reason = why,
            )
        }

        if (requested == ConversationClientProfile.NATIVE) {
            return ClientProfileDecision(
                requested = requested,
                effective = ConversationClientProfile.NATIVE,
                dialect = dialect,
                support = ProfileSupport.SUPPORTED,
                auto = false,
                fellBack = false,
                label = labelFor(ConversationClientProfile.NATIVE, ProfileSupport.SUPPORTED),
                reason = "原生协议：不改请求头、不改请求体、不加工具引导。",
            )
        }

        val current = support(setting, requested)
        if (current == ProfileSupport.UNSUPPORTED) {
            // 不硬塞：协议对不上就老老实实按原生发，并把原因说清楚
            return ClientProfileDecision(
                requested = requested,
                effective = ConversationClientProfile.NATIVE,
                dialect = dialect,
                support = ProfileSupport.UNSUPPORTED,
                auto = false,
                fellBack = true,
                label = labelFor(ConversationClientProfile.NATIVE, ProfileSupport.SUPPORTED),
                reason = fallbackReason(requested, dialect),
                alternatives = alternativesFor(setting),
            )
        }

        return ClientProfileDecision(
            requested = requested,
            effective = requested,
            dialect = dialect,
            support = current,
            auto = false,
            fellBack = false,
            label = labelFor(requested, current),
            reason = supportedReason(requested, current),
        )
    }

    /** 某个档在当前 provider 上能落地多少。 */
    fun support(setting: ProviderSetting, profile: ConversationClientProfile): ProfileSupport =
        when (profile) {
            ConversationClientProfile.NATIVE,
            ConversationClientProfile.AUTO,
            -> ProfileSupport.SUPPORTED

            // Codex 走 OpenAI Responses 语义；chat/completions 上只剩工具用法可对齐
            ConversationClientProfile.CODEX -> when (setting) {
                is ProviderSetting.OpenAI ->
                    if (setting.useResponseApi) ProfileSupport.SUPPORTED else ProfileSupport.PARTIAL

                is ProviderSetting.ChatGPT -> ProfileSupport.SUPPORTED
                else -> ProfileSupport.UNSUPPORTED
            }

            // Claude Code 就是 Anthropic Messages；别的一律不冒充
            ConversationClientProfile.CLAUDE_CODE -> when (setting) {
                is ProviderSetting.Claude -> ProfileSupport.SUPPORTED
                else -> ProfileSupport.UNSUPPORTED
            }

            // Pi 的 pi-ai 同时覆盖 Chat Completions 和 Messages，这两种协议都能用
            ConversationClientProfile.PI -> when (setting) {
                is ProviderSetting.OpenAI,
                is ProviderSetting.Claude,
                is ProviderSetting.Grok,
                -> ProfileSupport.SUPPORTED

                else -> ProfileSupport.UNSUPPORTED
            }
        }

    /** 当前 provider 实际走的线协议。 */
    fun dialectOf(setting: ProviderSetting): ProfileWireDialect = when (setting) {
        is ProviderSetting.OpenAI ->
            if (setting.useResponseApi) ProfileWireDialect.RESPONSES else ProfileWireDialect.CHAT_COMPLETIONS

        is ProviderSetting.Claude -> ProfileWireDialect.ANTHROPIC_MESSAGES
        is ProviderSetting.Google -> ProfileWireDialect.GOOGLE_GENERATE_CONTENT
        is ProviderSetting.ChatGPT -> ProfileWireDialect.RESPONSES
        is ProviderSetting.Grok -> ProfileWireDialect.CHAT_COMPLETIONS
        is ProviderSetting.WebAccount -> ProfileWireDialect.NONE
    }

    /**
     * AUTO 的选择表。只看本地已知信息：provider 类型、OpenAI 的 Responses 开关、模型名族。
     * 判不出来就回原生 —— 猜错协议比不模拟更糟。
     */
    internal fun autoSelect(
        setting: ProviderSetting,
        model: Model,
    ): Pair<ConversationClientProfile, String> {
        val hint = model.familyHint()
        return when (setting) {
            is ProviderSetting.OpenAI -> when {
                // 只有 Responses 才谈得上 Codex 语义；chat/completions 选 Pi（两种协议都覆盖，不踩空）
                !setting.useResponseApi -> ConversationClientProfile.PI to
                    "provider 走 /chat/completions，Codex 专有参数发不出去，自动选 Pi 风格（协议不变，只调工具用法）。"

                looksOpenAiFrontier(hint) -> ConversationClientProfile.CODEX to
                    "模型 $hint 属于 OpenAI 前沿系列且 provider 走 Responses，自动选 Codex 风格。"

                else -> ConversationClientProfile.PI to
                    "provider 走 Responses，但模型 $hint 不像 OpenAI 自家系列，自动选 Pi 风格（更通用）。"
            }

            is ProviderSetting.Claude -> if (hint.contains("claude") || model.modelId.isBlank()) {
                ConversationClientProfile.CLAUDE_CODE to
                    "Anthropic Messages 协议 + Claude 模型，自动选 Claude Code 风格。"
            } else {
                ConversationClientProfile.PI to
                    "Anthropic Messages 协议但模型名不像 Claude，自动选 Pi 风格（协议不变）。"
            }

            is ProviderSetting.ChatGPT -> ConversationClientProfile.CODEX to
                "ChatGPT 账号本身就走 Responses，自动选 Codex 风格。"

            is ProviderSetting.Grok -> ConversationClientProfile.PI to
                "Grok 走 chat/completions 兼容协议，自动选 Pi 风格。"

            is ProviderSetting.Google -> ConversationClientProfile.NATIVE to
                "Google generateContent 与这些客户端的公开协议不同，自动保持原生。"

            is ProviderSetting.WebAccount -> ConversationClientProfile.NATIVE to
                "Web 账号桥接没有公开协议可对齐，自动保持原生。"
        }
    }

    private fun looksOpenAiFrontier(hint: String): Boolean =
        listOf("gpt", "codex", "chatgpt", "o1", "o3", "o4").any { hint.contains(it) }

    /**
     * CODEX 在 Responses 上的协议层参数。
     *
     * chat/completions 上不发：`store` 这类字段对只支持 chat completions 的网关是未知字段，
     * 有的直接 400，为了一个风格开关把请求打挂不值得。
     */
    private fun profileBodyParams(
        profile: ConversationClientProfile,
        dialect: ProfileWireDialect,
        sessionId: String?,
    ): List<CustomBody> = when {
        profile == ConversationClientProfile.CODEX && dialect == ProfileWireDialect.RESPONSES -> buildList {
            add(CustomBody("store", JsonPrimitive(false)))
            // 同一会话固定 key，服务端前缀缓存才命中；sessionId 本来就是这个会话的 id
            if (!sessionId.isNullOrBlank()) add(CustomBody("prompt_cache_key", JsonPrimitive(sessionId)))
        }

        else -> emptyList()
    }

    private fun suggestedApiChange(
        setting: ProviderSetting,
        profile: ConversationClientProfile,
        dialect: ProfileWireDialect,
    ): String? {
        if (profile != ConversationClientProfile.CODEX) return null
        if (setting !is ProviderSetting.OpenAI || dialect == ProfileWireDialect.RESPONSES) return null
        return "Codex 风格在 /chat/completions 上只能对齐工具用法：store / prompt_cache_key 属于 Responses 专有参数，本次没有发送。" +
            "想用完整 Codex 语义，可以在该 provider 设置里改用 Responses API（不会自动改，也不影响其他会话）。"
    }
}

/**
 * 顶层入口：`applyProfile(params, setting, profile)`。
 *
 * 返回的 [ProfiledRequest.params] 直接拿去发请求；[ProfiledRequest.guidance] 非空时
 * 追加到系统提示末尾（不要替换原提示词）。
 */
fun applyProfile(
    params: TextGenerationParams,
    setting: ProviderSetting,
    profile: ConversationClientProfile,
): ProfiledRequest = ClientProfileResolver.applyProfile(params, setting, profile)

/** 只解析不落地，给 UI 显示"自动选中了哪个 + 为什么"。 */
fun resolveClientProfile(
    setting: ProviderSetting,
    model: Model,
    requested: ConversationClientProfile,
): ClientProfileDecision = ClientProfileResolver.resolve(setting, model, requested)
