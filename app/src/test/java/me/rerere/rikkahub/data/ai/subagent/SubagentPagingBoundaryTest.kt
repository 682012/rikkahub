package me.rerere.rikkahub.data.ai.subagent

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页边界：UTF-16 代理对不能被切开，nextOffset 串起来读必须能把全文原样拼回来，
 * 并且 wait 的多任务首屏要落在整体预算内。
 */
class SubagentPagingBoundaryTest {
    private val emoji = "😀" // U+1F600，2 个 code unit

    private fun task() = SubagentTask(
        id = "task-1",
        prompt = "p",
        modelId = null,
        conversationId = Uuid.random(),
        status = SubagentStatus.COMPLETED,
        steps = 2,
    )

    private fun readAll(full: String, limit: Int): String {
        val builder = StringBuilder()
        var offset = 0
        var guard = 0
        while (true) {
            val page = subagentResultPage(task(), full, offset, limit)
            builder.append(page.text)
            if (page.eof) break
            assertTrue("nextOffset must advance to avoid an infinite loop", page.nextOffset > offset)
            offset = page.nextOffset
            guard++
            assertTrue("paging should terminate", guard < 10_000)
        }
        return builder.toString()
    }

    @Test
    fun `limit 1 on an emoji returns both code units and does not stall`() {
        val page = subagentResultPage(task(), emoji, offset = 0, limit = 1)

        assertEquals(2, page.text.length)
        assertEquals(emoji, page.text)
        assertEquals(2, page.nextOffset)
        assertTrue(page.eof)
    }

    @Test
    fun `emoji results paginate back to the exact original text`() {
        val full = "a$emoji b${emoji}c ${emoji}${emoji} tail"
        listOf(1, 2, 3, 5, 7, 6_000).forEach { limit ->
            assertEquals("limit=$limit", full, readAll(full, limit))
        }
        // 15 个 emoji 混排；逐 code unit 读也要能拼回原文。
        val heavy = emoji.repeat(15) + "end"
        assertEquals(heavy, readAll(heavy, 1))
    }

    @Test
    fun `no page starts or ends with a lone surrogate`() {
        val full = "${emoji}x${emoji}y${emoji}z"
        var offset = 0
        repeat(20) {
            val page = subagentResultPage(task(), full, offset, 3)
            val text = page.text
            if (text.isNotEmpty()) {
                assertFalse("page must not start with a low surrogate", text.first().isLowSurrogate())
                assertFalse("page must not end with a high surrogate", text.last().isHighSurrogate())
            }
            if (page.eof) return
            offset = page.nextOffset
        }
    }

    @Test
    fun `an offset inside a surrogate pair aligns back to the pair`() {
        val full = "a${emoji}b"
        // offset=2 落在 emoji 的中间（低位所在位置）。
        val page = subagentResultPage(task(), full, offset = 2, limit = 1)

        assertEquals(1, page.offset)
        assertTrue(page.text.startsWith(emoji))
        assertFalse(page.text.first().isLowSurrogate())
    }

    @Test
    fun `truncating a preview never splits a surrogate pair`() {
        val text = "a".repeat(9) + emoji + "b"
        val truncated = subagentTruncateUtf16(text, 10)

        assertEquals(9, truncated.length)
        assertEquals("a".repeat(9), truncated)
        assertFalse(truncated.endsWith(emoji.first()))

        assertEquals(text, subagentTruncateUtf16(text, 999))
        assertEquals("", subagentTruncateUtf16(text, 0))
        assertEquals(text, subagentTruncateUtf16(text, text.length))
    }

    @Test
    fun `notice preview does not end with half an emoji`() {
        // 预览上限 200：199 个 a 之后刚好是一个 emoji，朴素的 take(200) 会切出半个字符。
        val body = "a".repeat(SUBAGENT_NOTICE_PREVIEW_CHARS - 1) + emoji + "TAIL-MARKER"
        val notice = buildSubagentNotice(
            SubagentTask(
                id = "task-1",
                prompt = "p",
                modelId = null,
                conversationId = Uuid.random(),
                status = SubagentStatus.COMPLETED,
                startedAt = 1_000L,
                finishedAt = 5_000L,
            ),
            body,
        )

        val preview = notice.text.lines()
            .first { it.startsWith("Preview:") }
            .removePrefix("Preview: ")
            .removeSuffix("…")
        assertFalse(preview.last().isHighSurrogate())
        assertEquals(SUBAGENT_NOTICE_PREVIEW_CHARS - 1, preview.length)
        assertFalse(notice.text.contains("TAIL-MARKER"))
    }

    @Test
    fun `wait first screen limit respects the multi task budget`() {
        // 单任务可以用满上限。
        assertEquals(SUBAGENT_RESULT_DEFAULT_LIMIT, subagentWaitPageLimit(0, 1))
        assertEquals(SUBAGENT_RESULT_MAX_LIMIT, subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, 1))
        assertEquals(SUBAGENT_RESULT_MAX_LIMIT, subagentWaitPageLimit(99_999, 1))

        // 多任务均分整体预算。
        assertEquals(4_000, subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, 2))
        assertEquals(4_000, subagentWaitPageLimit(0, 2))
        assertEquals(2_666, subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, 3))
        assertEquals(1_000, subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, SUBAGENT_MAX_WAIT_TASKS))
        assertEquals(1_000, subagentWaitPageLimit(0, SUBAGENT_MAX_WAIT_TASKS))

        // 再多的任务也不低于最低额度，且总额仍远小于旧的 8 × 12000。
        assertEquals(SUBAGENT_WAIT_MIN_PAGE, subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, 32))
        val total = subagentWaitPageLimit(SUBAGENT_RESULT_MAX_LIMIT, SUBAGENT_MAX_WAIT_TASKS) * SUBAGENT_MAX_WAIT_TASKS
        assertTrue(total <= 16_000)
        assertEquals(SUBAGENT_WAIT_PAGE_BUDGET, total)
    }
}
