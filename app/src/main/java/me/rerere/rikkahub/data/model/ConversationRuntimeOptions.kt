package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Per-conversation opt-ins. OFF/defaults keep the old one-request path and permissions.
 *
 * 知识库从 m3 起**默认开启**：新会话不解码任何旧值，直接拿到 `true`；
 * 老会话的配置里已经显式写了 `knowledgeEnabled=false` 的继续按 false 走（不动用户已有的选择），
 * 没写过这个键的老数据解码时落到默认值 true —— 这正是"新会话默认开、旧数据能兼容"的口径。
 */
@Serializable
data class ConversationRuntimeOptions(
    val optimizeUserInput: Boolean = false,
    val reviewMode: ConversationReviewMode = ConversationReviewMode.OFF,
    val messageDelivery: RunningMessageDelivery = RunningMessageDelivery.QUEUE,
    val clientProfile: ConversationClientProfile = ConversationClientProfile.NATIVE,
    val knowledgeEnabled: Boolean = true,
    val efficientTools: Boolean = true,
    /**
     * 「优化我的原话」单独用的模型；null = **跟随本会话的聊天模型**（不再回退全局快速模型）。
     *
     * 和回复审查各选各的，互不影响：用户想用便宜/快的模型做改写，同时用更强的模型做审查，不需要被迫共用一条。
     * 选了但模型已删除时这一步按失败处理（原话照常发送），不会偷偷换成别的模型。
     */
    val optimizerModelId: Uuid? = null,
    /** 「回复审查」独立模型；null = 跟随本会话的聊天模型（不再回退全局快速模型）。只在 INDEPENDENT 下有意义。 */
    val reviewerModelId: Uuid? = null,
)

@Serializable
enum class ConversationReviewMode { OFF, SELF_CHECK, INDEPENDENT }

@Serializable
enum class RunningMessageDelivery { QUEUE, NEXT_STEP }

/** Compatibility presets, never credentials or a claim to be an official client. */
@Serializable
enum class ConversationClientProfile { NATIVE, AUTO, CODEX, CLAUDE_CODE, PI }
