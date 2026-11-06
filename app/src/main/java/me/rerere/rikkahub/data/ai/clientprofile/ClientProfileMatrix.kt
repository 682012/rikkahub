package me.rerere.rikkahub.data.ai.clientprofile

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.ConversationClientProfile

/**
 * 支持矩阵与用户可见文案。
 *
 * 这里写的是**实况**：能做多少就写多少，不出现"支持任何客户端"这种话。
 * 某个档在某个 provider 上做不到，就说明需要什么协议、当前是什么协议、为什么不能硬塞。
 */
fun clientProfileMatrix(): List<ClientProfileMatrixEntry> = listOf(
    ClientProfileMatrixEntry(
        profile = ConversationClientProfile.NATIVE,
        label = "原生",
        description = "按 provider 自己的协议原样发送，什么都不加。",
        requiresDialect = null,
        supportedProviders = "全部",
        limitation = "不模拟任何客户端。",
    ),
    ClientProfileMatrixEntry(
        profile = ConversationClientProfile.AUTO,
        label = "自动",
        description = "按 provider 类型、API 类型和模型名本地挑一个能真正落地的档，本轮固定。",
        requiresDialect = null,
        supportedProviders = "全部（最终档位见结果）",
        limitation = "纯本地判断，不探测服务端能力，也不额外发请求。",
    ),
    ClientProfileMatrixEntry(
        profile = ConversationClientProfile.CODEX,
        label = "Codex 风格",
        description = "OpenAI Responses 语义：originator 标识、store=false、prompt_cache_key，" +
            "加上工作区最小改动的工具引导。",
        requiresDialect = ProfileWireDialect.RESPONSES,
        supportedProviders = "OpenAI（Responses）、ChatGPT 账号；OpenAI chat/completions 为部分支持",
        limitation = "不带官方版本号 User-Agent，不伪造账户、额度或组织标识。",
    ),
    ClientProfileMatrixEntry(
        profile = ConversationClientProfile.CLAUDE_CODE,
        label = "Claude Code 风格",
        description = "Anthropic Messages 语义：x-app / user-agent 标识，" +
            "加上多步计划、破坏性操作先问的工具引导。",
        requiresDialect = ProfileWireDialect.ANTHROPIC_MESSAGES,
        supportedProviders = "Claude（Anthropic API）",
        limitation = "不会把 OpenAI 格式的消息硬塞成 Anthropic 格式；不覆盖 anthropic-version。",
    ),
    ClientProfileMatrixEntry(
        profile = ConversationClientProfile.PI,
        label = "Pi 风格",
        description = "通用工具编排：批量调用、静态前缀利于缓存、优先按技能执行；不改协议参数。",
        requiresDialect = null,
        supportedProviders = "OpenAI（两种 API）、Claude、Grok",
        limitation = "pi-ai 不带产品标识头，所以本档也不编一个；Google 通道未验证。",
    ),
)

internal fun labelFor(profile: ConversationClientProfile, support: ProfileSupport): String {
    val base = when (profile) {
        ConversationClientProfile.NATIVE -> "原生协议"
        ConversationClientProfile.AUTO -> "自动"
        ConversationClientProfile.CODEX -> "Codex 风格（Responses）"
        ConversationClientProfile.CLAUDE_CODE -> "Claude Code 风格（Messages）"
        ConversationClientProfile.PI -> "Pi 风格（通用编排）"
    }
    return if (support == ProfileSupport.PARTIAL) "$base · 部分" else base
}

internal fun supportedReason(
    profile: ConversationClientProfile,
    support: ProfileSupport,
): String = when {
    profile == ConversationClientProfile.CODEX && support == ProfileSupport.PARTIAL ->
        "Codex 风格（部分）：工具引导按 Codex 习惯走，但当前 provider 走 /chat/completions，Responses 专有参数不发。"

    profile == ConversationClientProfile.CODEX ->
        "Codex 风格：加 originator 标识，Responses 请求带 store=false 与 prompt_cache_key，工具引导改成工作区最小改动。"

    profile == ConversationClientProfile.CLAUDE_CODE ->
        "Claude Code 风格：加 x-app / user-agent 标识，工具引导改成多步计划与破坏性操作先确认；" +
            "anthropic-version 仍由 provider 原样发送。"

    profile == ConversationClientProfile.PI ->
        "Pi 风格：不改协议与请求头，只把工具引导换成批量调用、静态前缀、优先按技能执行。"

    else -> "保持原生协议。"
}

internal fun fallbackReason(
    profile: ConversationClientProfile,
    dialect: ProfileWireDialect,
): String {
    val need = when (profile) {
        ConversationClientProfile.CLAUDE_CODE -> "Anthropic Messages 协议（Claude provider）"
        ConversationClientProfile.CODEX -> "OpenAI Responses 或 ChatGPT 账号"
        ConversationClientProfile.PI -> "OpenAI / Claude / Grok 这类公开 chat 协议"
        else -> "对应的公开协议"
    }
    val actual = when (dialect) {
        ProfileWireDialect.CHAT_COMPLETIONS -> "OpenAI chat/completions"
        ProfileWireDialect.RESPONSES -> "OpenAI Responses"
        ProfileWireDialect.ANTHROPIC_MESSAGES -> "Anthropic Messages"
        ProfileWireDialect.GOOGLE_GENERATE_CONTENT -> "Google generateContent"
        ProfileWireDialect.NONE -> "Web 账号桥接"
    }
    val consequence = if (profile == ConversationClientProfile.CLAUDE_CODE) {
        "把 OpenAI 的消息与工具调用硬塞成 Anthropic 格式会直接破坏历史里 tool_call 的对应关系，所以不做。"
    } else {
        "换一套请求格式会破坏工具调用与历史消息的对应关系，所以不做。"
    }
    return "「${labelFor(profile, ProfileSupport.UNSUPPORTED)}」需要 $need，当前 provider 是 $actual；" +
        consequence + "本次已按原生协议发送。"
}

internal fun alternativesFor(setting: ProviderSetting): List<ConversationClientProfile> =
    listOf(
        ConversationClientProfile.CODEX,
        ConversationClientProfile.CLAUDE_CODE,
        ConversationClientProfile.PI,
    ).filter { ClientProfileResolver.support(setting, it) != ProfileSupport.UNSUPPORTED }
