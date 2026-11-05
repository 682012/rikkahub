package me.rerere.rikkahub.data.organization.autofile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAutoFileParserTest {

    private val id1 = "6d1a2f1e-3b1c-4a2d-9f10-012345678901"
    private val id2 = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Test
    fun `accepts strict json object`() {
        val result = ConversationAutoFileParser.parse(
            """{"assignments":[{"id":"$id1","name":"旅行计划","isNew":false,"confidence":0.93}]}""",
        )

        assertNull(result.error)
        assertEquals(1, result.assignments.size)
        val assignment = result.assignments.single()
        assertEquals(Uuid.parse(id1), assignment.conversationId)
        assertEquals("旅行计划", assignment.folderName)
        assertEquals(false, assignment.isNew)
        assertEquals(0.93, assignment.confidence, 0.0001)
    }

    @Test
    fun `rejects code fence and prose`() {
        val fenced = ConversationAutoFileParser.parse(
            "```json\n{\"assignments\":[{\"id\":\"$id1\",\"name\":\"A\",\"isNew\":true,\"confidence\":0.9}]}\n```",
        )
        assertNotNull(fenced.error)
        assertTrue(fenced.assignments.isEmpty())

        val chatty = ConversationAutoFileParser.parse(
            "好的，结果如下 {\"assignments\":[{\"id\":\"$id1\",\"name\":\"A\",\"isNew\":true,\"confidence\":0.9}]}",
        )
        assertNotNull(chatty.error)
        assertTrue(chatty.assignments.isEmpty())
    }

    @Test
    fun `rejects unknown fields and missing fields`() {
        val extra = ConversationAutoFileParser.parse(
            """{"assignments":[{"id":"$id1","name":"A","isNew":true,"confidence":0.9,"reason":"x"}]}""",
        )
        assertNotNull(extra.error)

        val missingIsNew = ConversationAutoFileParser.parse(
            """{"assignments":[{"id":"$id1","name":"A","confidence":0.9}]}""",
        )
        assertNotNull(missingIsNew.error)

        val missingConfidence = ConversationAutoFileParser.parse(
            """{"assignments":[{"id":"$id1","name":"A","isNew":false}]}""",
        )
        assertNotNull(missingConfidence.error)

        val notJson = ConversationAutoFileParser.parse("抱歉，我无法完成")
        assertNotNull(notJson.error)
    }

    @Test
    fun `drops duplicate ids entirely`() {
        val result = ConversationAutoFileParser.parse(
            """{"assignments":[
                {"id":"$id1","name":"A","isNew":false,"confidence":0.9},
                {"id":"$id1","name":"B","isNew":false,"confidence":0.9}
            ]}""",
        )

        assertNull(result.error)
        assertTrue(result.assignments.isEmpty())
    }

    @Test
    fun `drops invalid uuid bad confidence and blank name`() {
        val result = ConversationAutoFileParser.parse(
            """{"assignments":[
                {"id":"not-a-uuid","name":"A","isNew":false,"confidence":0.9},
                {"id":"$id1","name":"B","isNew":false,"confidence":1.4},
                {"id":"$id2","name":"   ","isNew":false,"confidence":0.9}
            ]}""",
        )

        assertNull(result.error)
        assertTrue(result.assignments.isEmpty())
    }

    @Test
    fun `keeps low confidence for planner to reject and normalises name`() {
        val result = ConversationAutoFileParser.parse(
            """{"assignments":[
                {"id":"$id1","name":"  多  空格   库名 ","isNew":true,"confidence":0.4},
                {"id":"$id2","name":"${"x".repeat(80)}","isNew":false,"confidence":0.99}
            ]}""",
        )

        assertNull(result.error)
        assertEquals(2, result.assignments.size)
        assertEquals("多 空格 库名", result.assignments[0].folderName)
        assertEquals(0.4, result.assignments[0].confidence, 0.0001)
        assertEquals(ConversationAutoFileParser.MAX_NAME_LENGTH, result.assignments[1].folderName.length)
    }
}
