package me.rerere.ai.provider.providers.claude

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 把一次响应的 usage 拆成多帧：`message_start` 的 `message.usage` 带输入侧字段
 * （`input_tokens` / `cache_read_input_tokens` / `cache_creation_input_tokens`），
 * `message_delta` 的 `usage` 往往只有累计的 `output_tokens`。
 *
 * 这些用例锁定：解码器必须维护累计快照，"本帧没带这个字段"不等于 0；显式上报的 0 可以覆盖；
 * `cache_creation_input_tokens` 属于输入计费，永远不能进 `cachedTokens`；没有 usage 的帧不发空快照。
 * 每个解码器实例只服务一次请求，不会带上一次请求的数字。
 */
class ClaudeStreamDecoderUsageTest {
    private fun ev(type: String, data: String) = SseEvent(event = type, data = data)

    private fun ClaudeStreamDecoder.usages(vararg events: SseEvent): List<TokenUsage> =
        events.flatMap { accept(it).chunks }.filterIsInstance<StreamChunk.Usage>().map { it.usage }

    private fun messageStart(usage: String) =
        ev("message_start", """{"type":"message_start","message":{"id":"msg_1","model":"claude-x","usage":$usage}}""")

    private fun messageDelta(usage: String, stopReason: String = "end_turn") =
        ev(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":$usage}""",
        )

    @Test
    fun `delta without cache fields must keep the input side snapshot`() {
        val decoder = ClaudeStreamDecoder()
        val usages = decoder.usages(
            messageStart(
                """{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":200,"output_tokens":1}""",
            ),
            messageDelta("""{"output_tokens":50}""", stopReason = "end_turn"),
        )

        assertEquals(2, usages.size)
        assertEquals(TokenUsage(promptTokens = 1200, completionTokens = 1, cachedTokens = 900, totalTokens = 1201), usages[0])
        // message_delta 只上报 output：输入侧不能掉成 0，cached 也不能消失
        assertEquals(TokenUsage(promptTokens = 1200, completionTokens = 50, cachedTokens = 900, totalTokens = 1250), usages[1])
    }

    @Test
    fun `delta repeating only input tokens must not drop cache read and creation`() {
        // 已证实 bug 的最小复现：delta 帧只带 input_tokens/output_tokens。
        // 缺字段当 0 时 prompt 从 1200 掉成 100，下游 merge 又留下 cached=900 → 命中率 900%。
        val decoder = ClaudeStreamDecoder()
        val usages = decoder.usages(
            messageStart(
                """{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":200,"output_tokens":1}""",
            ),
            messageDelta("""{"input_tokens":100,"output_tokens":50}""", stopReason = "end_turn"),
        )

        assertEquals(TokenUsage(1200, 1, 900, 1201), usages[0])
        assertEquals(TokenUsage(1200, 50, 900, 1250), usages[1])

        // 下游 TokenUsage.merge 的语义（取最新非零值）不改，但拿到完整快照后再合并
        // 也不会出现 cached > prompt 的 900% 命中率
        val merged = usages.first().merge(usages.last())
        assertEquals(TokenUsage(1200, 50, 900, 1250), merged)
        assertTrue("cached 不能超过 prompt: $merged", merged.cachedTokens <= merged.promptTokens)
    }

    @Test
    fun `usage frame with only output tokens still reports the full snapshot`() {
        val decoder = ClaudeStreamDecoder()
        val usages = decoder.usages(
            messageStart("""{"input_tokens":30}"""),
            messageDelta("""{"output_tokens":20}""", stopReason = "end_turn"),
        )

        assertEquals(TokenUsage(30, 0, 0, 30), usages[0])
        assertEquals(TokenUsage(30, 20, 0, 50), usages[1])
    }

    @Test
    fun `explicit zero cache read must become the new value`() {
        // 显式上报的 0 是真实事实（这次没有命中缓存），必须覆盖旧快照。
        // 注：下游 TokenUsage.merge 仍取非零值，所以缓存统计要按解码器直接输出的快照算。
        val decoder = ClaudeStreamDecoder()
        val usages = decoder.usages(
            messageStart("""{"input_tokens":100,"cache_read_input_tokens":900,"output_tokens":5}"""),
            messageDelta("""{"cache_read_input_tokens":0,"output_tokens":6}""", stopReason = "end_turn"),
        )

        assertEquals(TokenUsage(1000, 5, 900, 1005), usages[0])
        assertEquals(TokenUsage(100, 6, 0, 106), usages[1])
    }

    @Test
    fun `cache creation tokens are billed input and never cache hits`() {
        val decoder = ClaudeStreamDecoder()
        val usages = decoder.usages(
            messageStart(
                """{"input_tokens":100,"cache_read_input_tokens":0,"cache_creation_input_tokens":200,"output_tokens":7}""",
            ),
        )

        val usage = usages.single()
        assertEquals(300, usage.promptTokens)
        assertEquals("写缓存不算命中", 0, usage.cachedTokens)
        assertEquals(307, usage.totalTokens)
    }

    @Test
    fun `a new decoder starts from an empty snapshot`() {
        val first = ClaudeStreamDecoder()
        val firstUsages = first.usages(
            messageStart("""{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":200,"output_tokens":1}"""),
        )
        assertEquals(TokenUsage(1200, 1, 900, 1201), firstUsages.single())

        // 下一次请求是新解码器：不能继承上一次的 input/cache
        val second = ClaudeStreamDecoder()
        val secondUsages = second.usages(messageStart("""{"input_tokens":5,"output_tokens":1}"""))

        assertEquals(TokenUsage(5, 1, 0, 6), secondUsages.single())
    }

    @Test
    fun `prompt and total keep every input component`() {
        val decoder = ClaudeStreamDecoder()
        val usage = decoder.usages(
            messageStart("""{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":200}"""),
            messageDelta("""{"output_tokens":50}""", stopReason = "end_turn"),
        ).last()

        assertEquals(100 + 900 + 200, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(900, usage.cachedTokens)
        assertEquals(usage.promptTokens + usage.completionTokens, usage.totalTokens)
    }

    @Test
    fun `frames without usage must not emit an empty snapshot`() {
        val decoder = ClaudeStreamDecoder()
        val start = decoder.accept(
            ev("message_start", """{"type":"message_start","message":{"id":"msg_1","model":"claude-x"}}"""),
        )
        assertTrue(start.chunks.none { it is StreamChunk.Usage })

        // 只有未知扩展字段的 usage 不构成新的用量事实，同样不发空快照
        val unknownOnly = decoder.accept(
            messageDelta("""{"server_tool_use":{"web_search_requests":2}}""", stopReason = "end_turn"),
        )
        assertTrue(unknownOnly.chunks.none { it is StreamChunk.Usage })

        // 已经确认过的快照也不会被这帧清空
        val known = ClaudeStreamDecoder().usages(
            messageStart("""{"input_tokens":10,"cache_read_input_tokens":2}"""),
            messageDelta("""{"server_tool_use":{"web_search_requests":1}}""", stopReason = "end_turn"),
        )
        assertEquals(TokenUsage(12, 0, 2, 12), known.single())
    }
}
