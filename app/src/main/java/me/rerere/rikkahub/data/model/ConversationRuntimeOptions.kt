package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Per-conversation opt-ins. OFF/defaults keep the old one-request path and permissions. */
@Serializable
data class ConversationRuntimeOptions(
    val optimizeUserInput: Boolean = false,
    val reviewMode: ConversationReviewMode = ConversationReviewMode.OFF,
    val messageDelivery: RunningMessageDelivery = RunningMessageDelivery.QUEUE,
    val clientProfile: ConversationClientProfile = ConversationClientProfile.NATIVE,
    val knowledgeEnabled: Boolean = false,
    val efficientTools: Boolean = true,
)

@Serializable
enum class ConversationReviewMode { OFF, SELF_CHECK, INDEPENDENT }

@Serializable
enum class RunningMessageDelivery { QUEUE, NEXT_STEP }

/** Compatibility presets, never credentials or a claim to be an official client. */
@Serializable
enum class ConversationClientProfile { NATIVE, AUTO, CODEX, CLAUDE_CODE, PI }
