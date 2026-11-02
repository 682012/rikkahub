package me.rerere.ai.agent

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.StreamChunk

/**
 * Generation timing and observed throughput metrics for an LLM response.
 *
 * Note: Token counts are provider-reported output tokens (including provider-defined reasoning tokens where
 * applicable) rather than client-tokenized counts. Speed reflects client-observed stream throughput across the
 * active generation window (first content delta to last content delta), excluding network setup / TTFT, trailing
 * usage metadata delays, and server-side tool execution pauses.
 */
@Serializable
data class GenerationTiming(
    val firstContentMs: Long? = null,
    val generationMs: Long? = null,
    val requestMs: Long = 0L,
    val outputTokens: Long = 0L,
    val measuredSteps: Int = 0,
    val totalSteps: Int = 0,
) {
    /**
     * Client-observed stream throughput in tokens per second.
     *
     * Note: Token counts are provider-reported output tokens (including provider-defined reasoning tokens where
     * applicable), not independently tokenized on the client. Speed represents client-observed stream throughput.
     *
     * Returns null unless all steps were successfully measured, generation duration is at least 200 milliseconds,
     * and output tokens is a finite positive number.
     */
    val speedTokensPerSecond: Double?
        get() {
            if (totalSteps <= 0 || measuredSteps != totalSteps) return null
            val duration = generationMs ?: return null
            if (duration < 200L || outputTokens <= 0L) return null
            val speed = (outputTokens.toDouble() * 1000.0) / duration.toDouble()
            return if (speed.isFinite() && speed > 0.0) speed else null
        }

    /**
     * Merges this timing with another step. Merges request duration, known generation periods,
     * and output tokens, keeps the latest firstContentMs, and aggregates step counts.
     */
    fun add(other: GenerationTiming): GenerationTiming {
        val mergedGenMs = when {
            this.generationMs != null && other.generationMs != null -> this.generationMs + other.generationMs
            this.generationMs != null -> this.generationMs
            other.generationMs != null -> other.generationMs
            else -> null
        }
        return GenerationTiming(
            firstContentMs = other.firstContentMs,
            generationMs = mergedGenMs,
            requestMs = (this.requestMs + other.requestMs).coerceAtLeast(0L),
            outputTokens = (this.outputTokens + other.outputTokens).coerceAtLeast(0L),
            measuredSteps = this.measuredSteps + other.measuredSteps,
            totalSteps = this.totalSteps + other.totalSteps,
        )
    }

    operator fun plus(other: GenerationTiming): GenerationTiming = add(other)
}

/**
 * Monotonic timer tracking stream chunks for generation timing and client-observed stream throughput.
 *
 * Records timestamps only on non-empty TextDelta, ReasoningDelta, or ToolCallDelta (with non-empty input or tool name).
 * Metadata, Usage, Start, End, empty payloads, image snapshots, and server-side tool waits do not advance content time.
 * Server-side tool execution invalidates pure generation TPS for the request to avoid skewing metrics.
 */
class GenerationTimer(
    val nanoTime: () -> Long = System::nanoTime,
) {
    private val startNano: Long = nanoTime()
    private var firstPayloadNano: Long? = null
    private var lastPayloadNano: Long? = null
    private var contentPayloadCount: Int = 0
    private var hasServerToolEvents: Boolean = false

    fun onChunk(chunk: StreamChunk) {
        when (chunk) {
            is StreamChunk.Usage -> {
                // A provider may internally join several HTTP requests (for example pause_turn).
                // Their extra TTFT cannot be separated here; do not pretend this is one measured stream.
                val last = chunk.lastRequestUsage
                if (last != null && (last.promptTokens != chunk.usage.promptTokens || last.completionTokens != chunk.usage.completionTokens)) {
                    hasServerToolEvents = true
                }
            }
            is StreamChunk.ServerToolStart,
            is StreamChunk.ServerToolInputDelta,
            is StreamChunk.ServerToolInputEnd,
            is StreamChunk.ServerToolEnd,
            is StreamChunk.ToolCallOutput -> {
                hasServerToolEvents = true
            }
            is StreamChunk.TextDelta -> {
                if (chunk.text.isNotEmpty()) {
                    recordPayload()
                }
            }
            is StreamChunk.ReasoningDelta -> {
                if (chunk.text.isNotEmpty()) {
                    recordPayload()
                }
            }
            is StreamChunk.ToolCallDelta -> {
                if (chunk.toolNameDelta.isNotEmpty() || chunk.inputDelta.isNotEmpty()) {
                    recordPayload()
                }
            }
            else -> {
                // Metadata, Usage, Start, End, empty payloads, image snapshots, server tool waits are not content
            }
        }
    }

    private fun recordPayload() {
        val now = nanoTime()
        if (firstPayloadNano == null) {
            firstPayloadNano = now
        }
        lastPayloadNano = now
        contentPayloadCount++
    }

    /**
     * Finalizes timing metrics for the current request step.
     *
     * @param outputTokens Provider-reported output tokens (including reasoning tokens where applicable).
     * @param streaming Whether the request was streamed.
     */
    fun finish(outputTokens: Int?, streaming: Boolean = true): GenerationTiming {
        val finishNano = nanoTime()
        val requestMs = ((finishNano - startNano) / 1_000_000L).coerceAtLeast(0L)

        val firstContentMs = if (streaming && firstPayloadNano != null) {
            ((firstPayloadNano!! - startNano) / 1_000_000L).coerceAtLeast(0L)
        } else {
            null
        }

        val tokens = (outputTokens ?: 0).toLong().coerceAtLeast(0L)
        val isEligible = streaming &&
            !hasServerToolEvents &&
            contentPayloadCount >= 2 &&
            firstPayloadNano != null &&
            lastPayloadNano != null &&
            outputTokens != null &&
            outputTokens > 0

        val rawGenMs = if (isEligible) {
            ((lastPayloadNano!! - firstPayloadNano!!) / 1_000_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        // Tiny bursts are dominated by buffering and scheduling; no credible rate from a sub-200ms sample.
        val generationMs = if (isEligible && rawGenMs >= 200L) rawGenMs else null
        val measuredSteps = if (generationMs != null) 1 else 0

        return GenerationTiming(
            firstContentMs = firstContentMs,
            generationMs = generationMs,
            requestMs = requestMs,
            outputTokens = tokens,
            measuredSteps = measuredSteps,
            totalSteps = 1,
        )
    }
}
