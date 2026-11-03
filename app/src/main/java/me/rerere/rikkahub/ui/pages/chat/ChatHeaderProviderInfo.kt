package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findProvider

/** 标题栏模型信息的测试标记；CLASSIC / STUDIO / FOCUS 共用同一个 tag，任何风格都常显。 */
internal const val ChatHeaderModelTag = "chat-header-model"

/** 折叠摘要行（提供商 · 模型 · TPS 一行）的标记。 */
internal const val ChatHeaderSummaryTag = "chat-header-summary"

/** 展开/收起按钮的标记；折叠与展开共用，靠 contentDescription 区分方向。 */
internal const val ChatHeaderToggleTag = "chat-header-toggle"

/** 展开态详情区（详细 TPS + 上下文）的标记，折叠时不进入组合。 */
internal const val ChatHeaderDetailTag = "chat-header-detail"

/** 整块标题栏的根标记，测试靠它比较折叠/展开高度。 */
internal const val ChatHeaderRootTag = "chat-header-root"

/** 提供商与模型之间的分隔符；只有两边都有值时才出现。 */
internal const val ChatHeaderProviderSeparator = " · "

/**
 * 标题栏常显的“提供商 + 模型”信息。
 *
 * [providerName] 为 null 表示当前模型找不到对应提供商（例如提供商已被删除），
 * 此时只显示模型，不显示空的分隔符；[modelName] 永不为空，模型没选时是“未选择”文案。
 */
internal data class ChatHeaderProviderInfo(
    val providerName: String?,
    val modelName: String,
) {
    /** 一行展示用文本，例如 `OpenAI · gpt-4o`。 */
    val text: String
        get() = if (providerName != null) "$providerName$ChatHeaderProviderSeparator$modelName" else modelName
}

/**
 * 折叠态那一行摘要的完整文本：`提供商 · 模型 · 52.0 tok/s`。
 *
 * 单行 UI 里提供商和模型是两个独立 Text（提供商限宽先省略，保证模型不会被吞），
 * 这里再拼一份完整文本给无障碍 / 点击展开后的兜底，两者用的是同一份数据与分隔符。
 */
internal fun chatHeaderSummaryText(
    info: ChatHeaderProviderInfo,
    speedText: String,
): String = info.text + ChatHeaderProviderSeparator + speedText

/** 模型名：displayName 优先，其次 modelId，两者都空才退回“未选择”。 */
internal fun chatHeaderModelName(model: Model?, unselectedLabel: String): String =
    model?.displayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: model?.modelId?.trim()?.takeIf { it.isNotEmpty() }
        ?: unselectedLabel

/** 传入已解析好的提供商名；纯函数，方便 JVM 单测直接覆盖。 */
internal fun chatHeaderProviderInfo(
    model: Model?,
    providerName: String?,
    unselectedLabel: String,
): ChatHeaderProviderInfo = ChatHeaderProviderInfo(
    // 模型没选时提供商名没有意义，一并省略，避免“某提供商 · 未选择模型”这种误导
    providerName = providerName?.trim()?.takeIf { it.isNotEmpty() && model != null },
    modelName = chatHeaderModelName(model, unselectedLabel),
)

/**
 * 按当前请求实际会用的提供商解析：与 ChatRequestFactory 一致，
 * providerOverwrite 生效，所以这里用 findProvider 的默认行为。
 */
internal fun chatHeaderProviderInfo(
    model: Model?,
    providers: List<ProviderSetting>,
    unselectedLabel: String,
): ChatHeaderProviderInfo = chatHeaderProviderInfo(
    model = model,
    providerName = model?.findProvider(providers)?.name,
    unselectedLabel = unselectedLabel,
)
