package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.learning.LearningTurn
import kotlin.uuid.Uuid

/** Only numeric observations cross into the learning store; no content, arguments or reasoning. */
object LearningTurnMetrics {
    fun from(
        assistantId: Uuid,
        conversationId: Uuid,
        modelId: Uuid,
        message: UIMessage,
        retries: Int,
        reason: AgentFinishReason?,
    ): LearningTurn {
        val tools = message.getTools().filter { it.isExecuted }
        return LearningTurn(
            assistantId = assistantId.toString(),
            conversationId = conversationId.toString(),
            messageId = message.id.toString(),
            modelId = modelId.toString(),
            steps = message.stepCount ?: 0,
            toolCalls = tools.size,
            toolErrors = tools.count { tool -> tool.output.any(::hasStructuredError) },
            retries = retries,
            completed = reason == AgentFinishReason.Completed,
            stepLimitReached = reason == AgentFinishReason.StepLimitReached,
            firstContentMs = message.generationTiming?.firstContentMs,
            requestMs = message.generationTiming?.requestMs,
        )
    }

    private fun hasStructuredError(part: UIMessagePart): Boolean {
        val text = (part as? UIMessagePart.Text)?.text ?: return false
        if (text.length > 64_000 || !text.trimStart().startsWith('{')) return false
        val payload = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return payload["error"]?.let { it != JsonNull } == true
    }
}
