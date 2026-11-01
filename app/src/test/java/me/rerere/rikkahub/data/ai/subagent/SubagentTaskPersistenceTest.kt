package me.rerere.rikkahub.data.ai.subagent

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task.json 只存"重启后恢复所需"的元数据 + 状态 + 归属：
 * 正文留在 result.md，运行中被重启打断的任务按 CANCELLED + 重启说明恢复，partial 结果仍可读。
 */
class SubagentTaskPersistenceTest {
    private val conversationId = Uuid.random()
    private val modelId = Uuid.random()

    private fun task(
        status: SubagentStatus = SubagentStatus.COMPLETED,
        result: String? = "done",
        error: String? = null,
        steps: Int = 4,
    ) = SubagentTask(
        id = "11111111-2222-3333-4444-555555555555",
        prompt = "do the thing",
        modelId = modelId,
        conversationId = conversationId,
        status = status,
        createdAt = 1_000L,
        startedAt = 2_000L,
        finishedAt = 9_000L,
        result = result,
        error = error,
        steps = steps,
    )

    @Test
    fun `record round trips through json and keeps the owner`() {
        val record = task().toRecord()
        val decoded = decodeSubagentTaskRecord(encodeSubagentTaskRecord(record))

        assertEquals(record, decoded)
        assertEquals(conversationId.toString(), decoded!!.conversationId)
        assertEquals("COMPLETED", decoded.status)
        assertEquals(4, decoded.steps)
        // 正文不进 task.json。
        assertFalse(encodeSubagentTaskRecord(record).contains("done"))
    }

    @Test
    fun `prompt with quotes newlines and emoji survives json escaping`() {
        val tricky = "prompt with \"quotes\", \\backslash\\, \n newline and 😀 emoji"
        val record = task().toRecord().copy(prompt = tricky)

        val decoded = decodeSubagentTaskRecord(encodeSubagentTaskRecord(record))
        assertEquals(tricky, decoded!!.prompt)
    }

    @Test
    fun `unknown json fields are ignored so old records stay readable`() {
        val json = """
            {"version":1,"id":"11111111-2222-3333-4444-555555555555","prompt":"p",
             "conversationId":"$conversationId","status":"COMPLETED","createdAt":1,"steps":3,
             "futureField":"whatever"}
        """.trimIndent()

        val decoded = decodeSubagentTaskRecord(json)
        assertEquals(3, decoded!!.steps)
        assertEquals(SubagentStatus.COMPLETED, decoded.toTask().status)
        assertNull(decodeSubagentTaskRecord("not json at all"))
    }

    @Test
    fun `a task interrupted by a restart comes back cancelled with a restart note`() {
        val now = 50_000L
        listOf(SubagentStatus.RUNNING, SubagentStatus.QUEUED).forEach { status ->
            val restored = task(status = status, result = null).toRecord().toTask(
                resultPath = "/tmp/result.md",
                logPath = "/tmp/events.log",
                now = now,
            )

            assertEquals(SubagentStatus.CANCELLED, restored.status)
            assertEquals(SUBAGENT_RESTART_ERROR, restored.error)
            assertEquals(now, restored.finishedAt)
            assertEquals("/tmp/result.md", restored.resultPath)
            assertEquals(conversationId, restored.conversationId)
            // 正文懒读，不从 json 里恢复。
            assertNull(restored.result)
        }
    }

    @Test
    fun `a restart note keeps the previous error text`() {
        val restored = task(status = SubagentStatus.RUNNING, error = "network down")
            .toRecord()
            .toTask(now = 1L)

        assertEquals(SubagentStatus.CANCELLED, restored.status)
        assertTrue(restored.error!!.contains("network down"))
        assertTrue(restored.error!!.contains(SUBAGENT_RESTART_ERROR))
    }

    @Test
    fun `finished tasks keep their status and error on restore`() {
        val completed = task().toRecord().toTask(now = 1L)
        assertEquals(SubagentStatus.COMPLETED, completed.status)
        assertNull(completed.error)
        assertEquals(9_000L, completed.finishedAt)

        val failed = task(status = SubagentStatus.FAILED, error = "boom").toRecord().toTask(now = 1L)
        assertEquals(SubagentStatus.FAILED, failed.status)
        assertEquals("boom", failed.error)
    }

    @Test
    fun `unknown status in an old record degrades to failed instead of throwing`() {
        val restored = task().toRecord().copy(status = "SOMETHING_NEW").toTask(now = 1L)
        assertEquals(SubagentStatus.FAILED, restored.status)
    }

    @Test
    fun `only plain uuids may be used as task ids on disk`() {
        assertTrue(isSafeSubagentTaskId("11111111-2222-3333-4444-555555555555"))
        assertTrue(isSafeSubagentTaskId(Uuid.random().toString()))
        assertTrue(isSafeSubagentTaskId("11111111-2222-3333-4444-555555555555".uppercase()))

        assertFalse(isSafeSubagentTaskId(""))
        assertFalse(isSafeSubagentTaskId("task-1"))
        assertFalse(isSafeSubagentTaskId("../escape"))
        assertFalse(isSafeSubagentTaskId("11111111-2222-3333-4444-555555555555/../x"))
        assertFalse(isSafeSubagentTaskId("11111111-2222-3333-4444-555555555555/"))
    }
}
