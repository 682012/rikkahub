package me.rerere.ai.agent

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.StreamChunk

/**
 * 无法计时的步骤原因码。
 *
 * 用稳定的 ASCII 码存进 [GenerationTiming.skippedReasons]，由 UI 本地化；未知码原样展示，
 * 不做「看码猜文案」。
 */
object GenerationSkipReason {
    /** 非流式请求拿不到首包到末包之间那段生成窗口。 */
    const val NON_STREAM = "non_stream"

    /** 上游没有报告输出 token（或报了 0），没有分子。 */
    const val NO_OUTPUT_TOKENS = "no_output_tokens"

    /** 生成只落在一个包里（整包缓冲），窗口为 0，测不出速率。 */
    const val SINGLE_PACKET = "single_packet"

    /** 有多个包但窗口太短，调度/缓冲噪声会盖过真实速率。 */
    const val SHORT_WINDOW = "short_window"

    /** 服务端工具或内部续写（pause_turn 等）的等待混在窗口里，算不出纯生成速率。 */
    const val SERVER_WAIT = "server_wait"

    /** 旧版本消息没有计时记录，那几步测不到也补不回来。 */
    const val LEGACY_UNKNOWN = "legacy_unknown"
}

/**
 * Generation timing and observed throughput metrics for an LLM response.
 *
 * Note: Token counts are provider-reported output tokens (including provider-defined reasoning tokens where
 * applicable) rather than client-tokenized counts. Speed reflects client-observed stream throughput across the
 * active generation window (first content delta to last content delta), excluding network setup / TTFT, trailing
 * usage metadata delays, and server-side tool execution pauses.
 *
 * 一条消息可以有多个步骤（多轮工具调用共用同一条助手消息）。某一步测不准时不再把整条消息的速度藏起来，
 * 而是只统计真正测到的那几步：[measuredSteps] / [totalSteps] 给出覆盖范围，[skippedReasons] 给出没测到的原因。
 * 分子 [measuredOutputTokens] 与分母 [generationMs] 严格同源，未测步骤的 token 不会混进分子把速度抬高。
 */
@Serializable
data class GenerationTiming(
    val firstContentMs: Long? = null,
    val generationMs: Long? = null,
    val requestMs: Long = 0L,
    val outputTokens: Long = 0L,
    val measuredSteps: Int = 0,
    val totalSteps: Int = 0,
    /** 只有配套 [generationMs] 的那几步的输出 token；r9 及更早的记录没有这个字段，保持 0。 */
    val measuredOutputTokens: Long = 0L,
    /** 没测到的步骤按原因分组的计数，键见 [GenerationSkipReason]。 */
    val skippedReasons: Map<String, Int> = emptyMap(),
) {
    /** 没能计时的步骤数；没有覆盖说明时是 0。 */
    val skippedSteps: Int
        get() = (totalSteps - measuredSteps).coerceAtLeast(0)

    /** 部分可信：至少一步测到，但不是全部。 */
    val isPartial: Boolean
        get() = measuredSteps > 0 && totalSteps > measuredSteps

    /**
     * 与 [generationMs] 同源的已测输出 token。
     *
     * r9 及更早的记录没有 [measuredOutputTokens]，那时只有「全部步骤都测到」的老口径才能确定
     * 分子就是 [outputTokens]；部分测到的老记录无从判断，返回 0 而不是猜。
     */
    val measuredTokens: Long
        get() = when {
            measuredOutputTokens > 0L -> measuredOutputTokens
            totalSteps > 0 && measuredSteps == totalSteps -> outputTokens.coerceAtLeast(0L)
            else -> 0L
        }

    /**
     * Client-observed stream throughput in tokens per second.
     *
     * 已测步骤的 token 总和 / 这些步骤的生成窗口总和，等于按窗口长度加权的平均速率。
     * 要求窗口至少 [MIN_WINDOW_MS]、已测 token 为正；没有任何可信步骤时返回 null。
     * 部分覆盖时仍然返回数值，覆盖范围由 [measuredSteps] / [totalSteps] 交给 UI 说明。
     */
    val speedTokensPerSecond: Double?
        get() {
            if (totalSteps <= 0 || measuredSteps <= 0 || measuredSteps > totalSteps) return null
            val duration = generationMs ?: return null
            if (duration < MIN_WINDOW_MS) return null
            val tokens = measuredTokens
            if (tokens <= 0L) return null
            val speed = (tokens.toDouble() * 1000.0) / duration.toDouble()
            return if (speed.isFinite() && speed > 0.0) speed else null
        }

    /**
     * Merges this timing with another step. Merges request duration, known generation periods,
     * measured output tokens, skip reasons and step counts, keeps the latest firstContentMs.
     */
    fun add(other: GenerationTiming): GenerationTiming {
        val leftMeasured = speedTokensPerSecond != null
        val rightMeasured = other.speedTokensPerSecond != null
        // A legacy partial record has some time but no separable token numerator. Neither its
        // time nor its nominal measured-step count may dilute a newly measured continuation.
        val leftMs = generationMs.takeIf { leftMeasured }
        val rightMs = other.generationMs.takeIf { rightMeasured }
        val mergedGenMs = when {
            leftMs != null && rightMs != null -> leftMs + rightMs
            leftMs != null -> leftMs
            else -> rightMs
        }
        return GenerationTiming(
            firstContentMs = other.firstContentMs,
            generationMs = mergedGenMs,
            requestMs = (this.requestMs + other.requestMs).coerceAtLeast(0L),
            outputTokens = (this.outputTokens + other.outputTokens).coerceAtLeast(0L),
            measuredSteps = (if (leftMeasured) measuredSteps else 0) + (if (rightMeasured) other.measuredSteps else 0),
            totalSteps = this.totalSteps.coerceAtLeast(0) + other.totalSteps.coerceAtLeast(0),
            measuredOutputTokens = ((if (leftMeasured) measuredTokens else 0L) +
                (if (rightMeasured) other.measuredTokens else 0L)).coerceAtLeast(0L),
            skippedReasons = mergeSkipReasons(effectiveSkipReasons(leftMeasured), other.effectiveSkipReasons(rightMeasured)),
        )
    }

    private fun effectiveSkipReasons(measured: Boolean): Map<String, Int> {
        val reasons = skippedReasons.filterValues { it > 0 }.toMutableMap()
        val unavailable = (totalSteps - if (measured) measuredSteps else 0).coerceAtLeast(0)
        val unexplained = (unavailable - reasons.values.sum()).coerceAtLeast(0)
        if (unexplained > 0) reasons[GenerationSkipReason.LEGACY_UNKNOWN] =
            (reasons[GenerationSkipReason.LEGACY_UNKNOWN] ?: 0) + unexplained
        return reasons
    }

    operator fun plus(other: GenerationTiming): GenerationTiming = add(other)

    companion object {
        /** 低于这个窗口的样本主要反映缓冲和调度，不给速率。 */
        const val MIN_WINDOW_MS = 200L

        private fun mergeSkipReasons(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val merged = LinkedHashMap<String, Int>(a)
            b.forEach { (reason, count) -> merged[reason] = (merged[reason] ?: 0) + count }
            return merged
        }
    }
}

/**
 * Monotonic timer tracking stream chunks for generation timing and client-observed stream throughput.
 *
 * Records timestamps only on non-empty TextDelta, ReasoningDelta, or ToolCallDelta (with non-empty input or tool name).
 * Metadata, Usage, Start, End, empty payloads, image snapshots, and server-side tool waits do not advance content time.
 * Server-side tool execution / internal continuation invalidates pure generation TPS for that step (its usage covers
 * more than the measured window) — that step is reported as unmeasured with [GenerationSkipReason.SERVER_WAIT]
 * instead of skewing the rate.
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
     * 窗口只看内容包：第一个内容包到最后一个内容包，因此末尾的 usage 尾包、请求关闭、工具执行都不计入。
     * 测不准时也不丢弃信息，而是带一条 [GenerationSkipReason] 回去，UI 能说清为什么没有数。
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
        val rawGenMs = if (firstPayloadNano != null && lastPayloadNano != null) {
            ((lastPayloadNano!! - firstPayloadNano!!) / 1_000_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        // 先算出可信窗口，再判断能不能用它去配这批 token；顺序决定原因码是否说得准
        val reason = when {
            !streaming -> GenerationSkipReason.NON_STREAM
            outputTokens == null || outputTokens <= 0 -> GenerationSkipReason.NO_OUTPUT_TOKENS
            hasServerToolEvents -> GenerationSkipReason.SERVER_WAIT
            contentPayloadCount < 2 || firstPayloadNano == null -> GenerationSkipReason.SINGLE_PACKET
            // Tiny bursts are dominated by buffering and scheduling; no credible rate from a sub-200ms sample.
            rawGenMs < GenerationTiming.MIN_WINDOW_MS -> GenerationSkipReason.SHORT_WINDOW
            else -> null
        }

        val generationMs = if (reason == null) rawGenMs else null
        return GenerationTiming(
            firstContentMs = firstContentMs,
            generationMs = generationMs,
            requestMs = requestMs,
            outputTokens = tokens,
            measuredSteps = if (generationMs != null) 1 else 0,
            totalSteps = 1,
            measuredOutputTokens = if (generationMs != null) tokens else 0L,
            skippedReasons = if (reason == null) emptyMap() else mapOf(reason to 1),
        )
    }
}
