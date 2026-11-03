package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词只由"人设 + 触发 + 小助手自己的记忆"组成。
 *
 * 这里断言的是可见的行为：内置人设不用手写就能用、历史窗口有上限、事件只说事件、
 * 用户输入有上限、模型输出会被收成一句台词。
 */
class CompanionPromptBuilderTest {
    private val session = Uuid.random()

    private fun build(
        trigger: CompanionTrigger = CompanionTrigger(CompanionTriggerKind.TAP),
        persona: String = CompanionPersonas.builtIn(FloatingCompanionPreset.WHALE_MAID),
        name: String = "Marin",
        memory: List<CompanionExchange> = emptyList(),
    ) = CompanionPromptBuilder.build(
        persona = persona,
        name = name,
        trigger = trigger,
        memory = memory,
        sessionId = session,
        languageTag = "zh-CN",
    )

    @Test
    fun `every preset ships a built-in persona and name`() {
        FloatingCompanionPreset.entries.forEach { preset ->
            assertTrue("$preset has no default name", CompanionPersonas.defaultName(preset).isNotBlank())
            assertTrue("$preset has no built-in persona", CompanionPersonas.builtIn(preset).isNotBlank())
            assertTrue("$preset has no persona summary", CompanionPersonas.summaryKey(preset).isNotBlank())
        }
    }

    @Test
    fun `system prompt carries the name, persona and the no-chat rule`() {
        val request = build(persona = "You are a tiny lighthouse keeper.")
        assertTrue(request.systemPrompt.contains("Marin"))
        assertTrue(request.systemPrompt.contains("tiny lighthouse keeper"))
        assertTrue(request.systemPrompt.contains("cannot see the user's chats"))
        assertTrue(request.systemPrompt.contains("zh-CN"))
    }

    @Test
    fun `history keeps at most the memory window`() {
        val memory = (1..10).map { CompanionExchange("user-$it", "reply-$it") }
        val request = build(memory = memory)
        assertEquals(CompanionLimits.MEMORY_EXCHANGES * 2, request.history.size)
        // 只有最近几条，旧的不再发
        assertFalse(request.history.any { it.text == "user-1" })
        assertEquals("user-10", request.history[request.history.lastIndex - 1].text)
        assertEquals(CompanionPromptRole.USER, request.history[request.history.lastIndex - 1].role)
        assertEquals("reply-10", request.history.last().text)
        assertEquals(CompanionPromptRole.ASSISTANT, request.history.last().role)
    }

    @Test
    fun `history is dropped when it would exceed the character budget`() {
        val fat = "x".repeat(CompanionLimits.MAX_HISTORY_CHARS / 2 + 1)
        val memory = listOf(
            CompanionExchange(fat, fat),
            CompanionExchange("small-1", "small-2"),
        )
        val request = build(memory = memory)
        // 第一条就超预算，被整条丢掉
        assertTrue(request.history.none { it.text == fat })
        assertEquals(listOf("small-1", "small-2"), request.history.map { it.text })
    }

    @Test
    fun `input trigger keeps the user sentence and caps its length`() {
        val long = "啊".repeat(CompanionLimits.MAX_INPUT_CHARS + 50)
        val request = build(trigger = CompanionTrigger(CompanionTriggerKind.INPUT, detail = long))
        assertTrue(request.userPrompt.contains("啊".repeat(CompanionLimits.MAX_INPUT_CHARS)))
        assertFalse(request.userPrompt.contains("啊".repeat(CompanionLimits.MAX_INPUT_CHARS + 1)))
    }

    @Test
    fun `event triggers describe the event without any chat text`() {
        CompanionEventKind.entries.forEach { event ->
            val request = build(trigger = CompanionTrigger(CompanionTriggerKind.EVENT, event = event))
            assertTrue(
                "$event has no prompt sentence",
                request.userPrompt.contains("chat reply") || request.userPrompt.contains("tool"),
            )
        }
    }

    @Test
    fun `tap trigger asks for one line`() {
        val request = build()
        assertTrue(request.userPrompt.contains("tapped"))
        assertTrue(request.history.isEmpty())
        assertEquals(session, request.sessionId)
    }

    @Test
    fun `clean line keeps a single short spoken line`() {
        assertEquals("在呢", cleanCompanionLine("  \"在呢\"  "))
        assertEquals("好呀 我等你", cleanCompanionLine("好呀\n我等你\n第三行不要"))
        assertEquals("", cleanCompanionLine("   \n  "))
        val long = "喵".repeat(CompanionLimits.MAX_REPLY_CHARS + 20)
        assertEquals(CompanionLimits.MAX_REPLY_CHARS, cleanCompanionLine(long).length)
    }

    @Test
    fun `memory window forgets the oldest exchange`() {
        val memory = CompanionMemory(capacity = 2)
        memory.record("a", "1")
        memory.record("b", "2")
        memory.record("c", "3")
        assertEquals(2, memory.size)
        assertEquals(listOf("b", "c"), memory.snapshot().map { it.prompt })
        memory.clear()
        assertEquals(0, memory.size)
    }
}
