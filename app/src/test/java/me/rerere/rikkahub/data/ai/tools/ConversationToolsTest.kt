package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 助手隔离：会话检索和读取的作用域由工具自己决定，模型传什么参数都改不了。
 */
class ConversationToolsTest {
    private val assistantId = Uuid.random()
    private val otherAssistantId = Uuid.random()

    private fun result(conversationId: String) = MessageSearchResult(
        nodeId = "node-$conversationId",
        messageId = "message-$conversationId",
        conversationId = conversationId,
        title = "Title",
        updateAt = Instant.parse("2026-01-02T03:04:05Z"),
        snippet = "a [hit] here",
    )

    private fun textOf(output: List<UIMessagePart>): String = (output.single() as UIMessagePart.Text).text

    /** 检索结果里的会话 id，按返回顺序 */
    private fun conversationIdsOf(output: List<UIMessagePart>): List<String> =
        Json.parseToJsonElement(textOf(output)).jsonArray
            .map { element -> element.jsonObject.getValue("conversation_id").jsonPrimitive.content }

    @Test
    fun `conversation search is always scoped to the calling assistant`() = runBlocking {
        var requestedQuery: String? = null
        var requestedScope: Uuid? = null
        val tool = conversationSearchTool(assistantId) { query, scope ->
            requestedQuery = query
            requestedScope = scope
            listOf(result("conv-1"))
        }

        val output = tool.execute(
            buildJsonObject {
                put("query", "noodles")
                // 模型自己塞的范围参数不该生效
                put("assistant_id", otherAssistantId.toString())
            }
        )

        assertEquals("conversation_search", tool.name)
        assertEquals(assistantId, requestedScope)
        assertEquals("noodles", requestedQuery)
        assertEquals(listOf("conv-1"), conversationIdsOf(output))
    }

    @Test
    fun `conversation search honours the result limit`() = runBlocking {
        val tool = conversationSearchTool(assistantId) { _, _ -> listOf(result("a"), result("b"), result("c")) }

        val limited = tool.execute(
            buildJsonObject {
                put("query", "hi")
                put("limit", 2)
            }
        )

        assertEquals(listOf("a", "b"), conversationIdsOf(limited))
    }

    @Test
    fun `conversation read refuses another assistant's conversation`() = runBlocking {
        var tailRead = false
        val tool = conversationReadTool(
            assistantId = assistantId,
            ownerOf = { otherAssistantId.toString() },
            readTail = { _, _ ->
                tailRead = true
                listOf("user" to "another assistant's secret")
            },
        )

        val error = runCatching {
            tool.execute(buildJsonObject { put("conversation_id", "conv-2") })
        }.exceptionOrNull()

        assertEquals("conversation_read", tool.name)
        assertTrue(error is IllegalArgumentException)
        assertEquals("conversation not found", error?.message)
        // 核不过归属就不去读正文
        assertFalse(tailRead)
    }

    @Test
    fun `conversation read refuses an unknown conversation`() = runBlocking {
        val tool = conversationReadTool(
            assistantId = assistantId,
            ownerOf = { null },
            readTail = { _, _ -> emptyList() },
        )

        val error = runCatching {
            tool.execute(buildJsonObject { put("conversation_id", "missing") })
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("conversation not found", error?.message)
    }

    @Test
    fun `conversation read returns its own conversation`() = runBlocking {
        var requestedId: String? = null
        var requestedLimit: Int? = null
        val tool = conversationReadTool(
            assistantId = assistantId,
            ownerOf = { assistantId.toString() },
            readTail = { id, limit ->
                requestedId = id
                requestedLimit = limit
                listOf("user" to "hello", "assistant" to "hi")
            },
        )

        val output = tool.execute(
            buildJsonObject {
                put("conversation_id", "conv-3")
                put("limit", 5)
            }
        )

        assertEquals("conv-3", requestedId)
        assertEquals(5, requestedLimit)
        assertTrue(textOf(output).contains("hi"))
    }

    @Test
    fun `conversation read requires a conversation id`() = runBlocking {
        val tool = conversationReadTool(
            assistantId = assistantId,
            ownerOf = { assistantId.toString() },
            readTail = { _, _ -> emptyList() },
        )

        val error = runCatching { tool.execute(buildJsonObject { put("limit", 5) }) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("conversation_id is required", error?.message)
    }
}
