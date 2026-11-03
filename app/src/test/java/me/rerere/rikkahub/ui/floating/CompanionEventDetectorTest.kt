package me.rerere.rikkahub.ui.floating

import kotlin.uuid.Uuid
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.companion.CompanionEventKind
import me.rerere.rikkahub.data.companion.CompanionTriggerKind
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.service.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件从"会话状态的两次快照"里提取，键要能区分每一轮生成/每一个错误/每一个工具调用。
 *
 * 这里同时守住隐私边界：事件的 detail 一律为空，聊天正文不会跟着事件进提示词。
 */
class CompanionEventDetectorTest {
    private val conversation = Uuid.random()
    private val detector = CompanionEventDetector()

    private fun state(
        generating: Boolean = false,
        startedAt: Long? = null,
        error: ChatError? = null,
        tools: List<String> = emptyList(),
        conversationId: Uuid = conversation,
    ) = FloatingChatUiState(
        conversationId = conversationId,
        title = "不该出现在提示词里的标题",
        generating = generating,
        processingStatus = null,
        phase = GenerationPhase.Waiting,
        hasReply = false,
        preview = "不该出现在提示词里的正文",
        previewIsReasoning = false,
        pendingTools = tools.map { id ->
            UIMessagePart.Tool(
                toolCallId = id,
                toolName = "shell",
                input = "{}",
                approvalState = ToolApprovalState.Pending,
            )
        },
        queuedCount = 0,
        queuePaused = false,
        error = error,
        generationStartedAt = startedAt,
    )

    @Test
    fun `the first snapshot never speaks`() {
        assertTrue(detector.detect(null, state(generating = true, startedAt = 5L)).isEmpty())
    }

    @Test
    fun `starting a reply produces one event keyed by its start time`() {
        val events = detector.detect(state(), state(generating = true, startedAt = 5L))
        assertEquals(1, events.size)
        assertEquals(CompanionEventKind.GENERATION_STARTED, events.single().event)
        assertEquals("start:$conversation:5", events.single().eventKey)
        // 同一轮再观察多少次都不会多出事件
        assertTrue(detector.detect(state(generating = true, startedAt = 5L), state(generating = true, startedAt = 5L)).isEmpty())
    }

    @Test
    fun `finishing a reply produces one event keyed by the generation`() {
        val events = detector.detect(state(generating = true, startedAt = 5L), state(generating = false, startedAt = 5L))
        assertEquals(CompanionEventKind.GENERATION_FINISHED, events.single().event)
        assertEquals("finish:$conversation:5", events.single().eventKey)
    }

    @Test
    fun `a failure produces an event once per error`() {
        val first = ChatError(error = IllegalStateException("boom"), conversationId = conversation, timestamp = 900)
        // 新错误是新的实例（新 id），不只是时间戳变了
        val second = ChatError(error = IllegalStateException("boom again"), conversationId = conversation, timestamp = 1_000)
        val events = detector.detect(state(generating = true), state(error = first))
        assertEquals(CompanionEventKind.GENERATION_FAILED, events.single().event)
        assertEquals("error:$conversation:900", events.single().eventKey)
        // 同一个错误再观察不会重复
        assertTrue(detector.detect(state(error = first), state(error = first)).isEmpty())
        // 新的错误算新事件
        assertEquals(
            "error:$conversation:1000",
            detector.detect(state(error = first), state(error = second)).single().eventKey,
        )
    }

    @Test
    fun `a new pending tool call produces one event`() {
        val events = detector.detect(state(), state(tools = listOf("call-1")))
        assertEquals(CompanionEventKind.APPROVAL_PENDING, events.single().event)
        assertEquals("approval:call-1", events.single().eventKey)
        assertTrue(detector.detect(state(tools = listOf("call-1")), state(tools = listOf("call-1"))).isEmpty())
    }

    @Test
    fun `switching conversations does not fire stale events`() {
        val events = detector.detect(
            state(generating = true, startedAt = 5L),
            state(generating = false, startedAt = 5L, conversationId = Uuid.random()),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `events never carry chat content`() {
        val events = detector.detect(
            state(error = ChatError(error = IllegalStateException("x"), conversationId = conversation)),
            state(generating = true, startedAt = 7L, tools = listOf("call-2")),
        )
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            assertEquals(CompanionTriggerKind.EVENT, event.kind)
            assertEquals("", event.detail)
            assertTrue(!event.eventKey.orEmpty().contains("不该出现"))
        }
    }
}
