package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.agent.GenerationSkipReason
import me.rerere.ai.agent.GenerationTiming
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test

class ChatSpeedStatusTest {
    private fun chat(timing: GenerationTiming?, finished: Boolean = true): Conversation {
        val message=UIMessage(role=MessageRole.ASSISTANT,parts=emptyList(),generationTiming=timing,
            finishedAt=if (finished) kotlinx.datetime.LocalDateTime.parse("2026-11-03T20:00:00") else null)
        return Conversation(assistantId=Uuid.random(),messageNodes=listOf(MessageNode(messages=listOf(message),selectIndex=0)))
    }
    @Test fun measuredSpeedUsesStreamNotRequestWait() {
        val c=chat(GenerationTiming(generationMs=2000,requestMs=12000,outputTokens=100,measuredSteps=1,totalSteps=1))
        assertEquals(50.0,conversationSpeed(c)!!,.0001)
    }
    @Test fun shortBurstHasNoInventedRate() { assertNull(conversationSpeed(chat(GenerationTiming(generationMs=100,outputTokens=500,measuredSteps=1,totalSteps=1)))) }
    @Test fun unmeasuredNonStreamHasNoRate() { assertNull(conversationSpeed(chat(GenerationTiming(requestMs=2000,outputTokens=100,totalSteps=1)))) }
    @Test fun oldMessageWithoutTimingHasNoRate() { assertNull(conversationSpeed(chat(null))) }

    @Test fun partialCoverageKeepsSpeedAndReportsSteps() {
        // 72 步里只测到 65 步：仍然给出加权速度，并标出覆盖范围
        val ui = conversationSpeedUi(
            chat(
                GenerationTiming(
                    generationMs = 2000,
                    requestMs = 30000,
                    outputTokens = 130,
                    measuredSteps = 65,
                    totalSteps = 72,
                    measuredOutputTokens = 100,
                    skippedReasons = mapOf(GenerationSkipReason.SERVER_WAIT to 5, GenerationSkipReason.SINGLE_PACKET to 2),
                )
            )
        )
        assertEquals(50.0, ui.speed!!, 0.0001)
        assertEquals(65, ui.measuredSteps)
        assertEquals(72, ui.totalSteps)
        assertTrue(ui.partial)
        assertTrue(ui.hasCoverage)
        assertNull(ui.reasonCode)
        assertEquals("50.0 tok/s", ui.valueText)
        // 原因按出现次数排序，详情里直接展示
        assertEquals(listOf(GenerationSkipReason.SERVER_WAIT to 5, GenerationSkipReason.SINGLE_PACKET to 2), ui.skippedReasons)
    }

    @Test fun allStepsUnmeasuredSayWhy() {
        val ui = conversationSpeedUi(
            chat(
                GenerationTiming(
                    outputTokens = 30,
                    measuredSteps = 0,
                    totalSteps = 3,
                    skippedReasons = mapOf(GenerationSkipReason.SINGLE_PACKET to 2, GenerationSkipReason.SERVER_WAIT to 1),
                )
            )
        )
        assertNull(ui.speed)
        assertEquals(ChatSpeedPlaceholder, ui.valueText)
        // 最主要的原因优先：出现次数最多的那个
        assertEquals(GenerationSkipReason.SINGLE_PACKET, ui.reasonCode)
        assertFalse(ui.partial)
        assertTrue(ui.hasCoverage)
    }

    @Test fun legacyMessageSaysTimingIsMissing() {
        val ui = conversationSpeedUi(chat(null))
        assertNull(ui.speed)
        assertEquals(GenerationSkipReason.LEGACY_UNKNOWN, ui.reasonCode)
        assertFalse(ui.hasCoverage)
    }

    @Test fun unfinishedConversationHeaderMatchesTheFooter() {
        val ui = conversationSpeedUi(chat(GenerationTiming(generationMs=2000, outputTokens=100,
            measuredSteps=1, totalSteps=1), finished=false))
        assertNull(ui.speed)
        assertNull(ui.reasonCode)
        assertFalse(ui.hasCoverage)
    }

    @Test fun unfinishedStreamHidesValueAndReason() {
        val ui = GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1)
            .chatSpeedUi(finished = false)
        assertNull(ui.speed)
        assertNull(ui.reasonCode)
        assertEquals(ChatSpeedPlaceholder, ui.valueText)
        // 生成中既不报数也不报覆盖：窗口还没结束
        assertFalse(ui.hasCoverage)
    }

    @Test fun headerAndMessageUseSameValueText() {
        val ui = GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1).chatSpeedUi()
        // 顶部摘要和消息底部都从 ui.valueText 取值，不会出现两种写法
        assertEquals(conversationSpeedText(ui.speed), ui.valueText)
        assertEquals("50.0 tok/s", ui.valueText)
    }

    @Test fun reasonCodesMapToTheirOwnStrings() {
        assertEquals(R.string.r10_speed_reason_server_wait, chatSpeedReasonRes(GenerationSkipReason.SERVER_WAIT))
        assertEquals(R.string.r10_speed_reason_single_packet, chatSpeedReasonRes(GenerationSkipReason.SINGLE_PACKET))
        assertEquals(R.string.r10_speed_reason_short_window, chatSpeedReasonRes(GenerationSkipReason.SHORT_WINDOW))
        assertEquals(R.string.r10_speed_reason_non_stream, chatSpeedReasonRes(GenerationSkipReason.NON_STREAM))
        assertEquals(R.string.r10_speed_reason_no_tokens, chatSpeedReasonRes(GenerationSkipReason.NO_OUTPUT_TOKENS))
        assertEquals(R.string.r10_speed_reason_legacy, chatSpeedReasonRes(GenerationSkipReason.LEGACY_UNKNOWN))
        // 未知/未来原因码有兜底文案，不显示裸码
        assertEquals(R.string.r10_speed_reason_unknown, chatSpeedReasonRes("future_reason"))
        assertEquals(R.string.r10_speed_reason_unknown, chatSpeedReasonRes(null))
    }
}
