package me.rerere.rikkahub.data.ai.subagent

import me.rerere.ai.core.TokenUsage
import kotlin.uuid.Uuid

enum class SubagentStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT,
}

data class SubagentTask(
    val id: String,
    val prompt: String,
    val modelId: Uuid?,
    val conversationId: Uuid?,
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
    val steps: Int = 0,
    val resultPath: String? = null,
    val logPath: String? = null,
) {
    val isFinished: Boolean
        get() = status in setOf(
            SubagentStatus.COMPLETED,
            SubagentStatus.FAILED,
            SubagentStatus.CANCELLED,
            SubagentStatus.TIMEOUT,
        )
}
