package me.rerere.ai.provider.web

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebChatExchange 的协议契约测试：真实解析、真实 schema 校验、all-or-nothing、nonce 绑定。
 * nonce 通过 internal nonceFactory 注入固定值，断言可复现。
 */
class WebChatExchangeTest {

    // ------------------------------------------------------------ 无工具 / 纯文本

    @Test
    fun `plain exchange without tools carries no protocol`() {
        val exchange = exchange(listOf(UIMessage.system("be brief"), UIMessage.user("hello")))

        val prompt = exchange.prompt
        assertFalse(prompt.contains(WebChatExchange.PROTOCOL_ID))
        assertFalse(prompt.contains("nonce"))
        assertTrue(prompt.contains("\"role\":\"system\""))
        assertTrue(prompt.contains("\"role\":\"user\""))
        assertTrue(prompt.contains("hello"))

        val chunks = exchange.complete("plain answer", responseId = "resp-1")
        assertEquals(
            listOf("TextStart", "TextDelta", "TextEnd", "Finish"),
            chunks.map { it::class.simpleName },
        )
        assertEquals("plain answer", (chunks[1] as StreamChunk.TextDelta).text)
        val finish = chunks.last() as StreamChunk.Finish
        assertEquals("stop", finish.finishReason)
        assertEquals("resp-1", finish.responseId)
    }

    @Test
    fun `plain exchange does not treat text as a tool protocol envelope`() {
        val exchange = exchange(listOf(UIMessage.user("show me an example")))
        val quoted = envelope(text = "look", toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\"}")))

        val chunks = exchange.complete(quoted)

        assertEquals("stop", (chunks.last() as StreamChunk.Finish).finishReason)
        assertEquals(quoted, (chunks[1] as StreamChunk.TextDelta).text)
        assertTrue(chunks.none { it is StreamChunk.ToolCallStart })
    }

    // ------------------------------------------------------------- 上下文与角色边界

    @Test
    fun `conversation keeps system user assistant and executed tool results in order`() {
        val messages = listOf(
            UIMessage.system("system rules"),
            UIMessage.user("search for me"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("calling the tool"),
                    UIMessagePart.Tool(
                        toolCallId = "local-call-1",
                        toolName = "search_web",
                        input = "{\"query\":\"kotlin\"}",
                        output = listOf(UIMessagePart.Text("result body")),
                    ),
                ),
            ),
            UIMessage.user("thanks"),
        )

        val prompt = exchange(messages, tools = listOf(searchTool())).prompt

        val systemIndex = prompt.indexOf("\"role\":\"system\"")
        val userIndex = prompt.indexOf("\"role\":\"user\"")
        val assistantIndex = prompt.indexOf("\"role\":\"assistant\"")
        val toolResultIndex = prompt.indexOf("\"role\":\"tool_result\"")
        assertTrue(systemIndex >= 0)
        assertTrue(userIndex > systemIndex)
        assertTrue(assistantIndex > userIndex)
        assertTrue(toolResultIndex > assistantIndex)

        assertTrue(prompt.contains("system rules"))
        assertTrue(prompt.contains("calling the tool"))
        assertTrue(prompt.contains("\"name\":\"search_web\""))
        assertTrue(prompt.contains("result body"))
        // 工具结果紧跟在请求它的 assistant 之后
        assertTrue(prompt.indexOf("result body") > prompt.indexOf("calling the tool"))
    }

    @Test
    fun `history reasoning is not forwarded to the web chat`() {
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "secret chain of thought"),
                    UIMessagePart.Text("final answer"),
                ),
            ),
        )

        val prompt = exchange(messages).prompt

        assertTrue(prompt.contains("final answer"))
        assertFalse(prompt.contains("secret chain of thought"))
    }

    @Test
    fun `pending tool results are marked instead of pretending to be empty`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "local-call-2",
                        toolName = "search_web",
                        input = "{\"query\":\"kotlin\"}",
                        output = emptyList(),
                    ),
                ),
            ),
        )

        val prompt = exchange(messages).prompt

        assertTrue(prompt.contains("\"status\":\"not_executed\""))
        assertTrue(prompt.contains("\"name\":\"search_web\""))
    }

    // ---------------------------------------------------------------- 附件必须报错

    @Test
    fun `image attachment is rejected explicitly`() {
        val exchange = exchange(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("what is this"),
                        UIMessagePart.Image(url = "data:image/png;base64,AAAA"),
                    ),
                ),
            )
        )

        val error = assertThrows(WebChatExchangeException.UnsupportedContent::class.java) { exchange.prompt }
        assertTrue(error.message!!.contains("image attachment"))
    }

    @Test
    fun `audio attachment is rejected explicitly`() {
        val exchange = exchange(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Audio(url = "https://example.com/a.mp3")),
                ),
            )
        )

        val error = assertThrows(WebChatExchangeException.UnsupportedContent::class.java) { exchange.prompt }
        assertTrue(error.message!!.contains("audio attachment"))
    }

    @Test
    fun `document attachment is rejected explicitly`() {
        val exchange = exchange(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Document(
                            url = "https://example.com/a.pdf",
                            fileName = "a.pdf",
                            mime = "application/pdf",
                        ),
                    ),
                ),
            )
        )

        val error = assertThrows(WebChatExchangeException.UnsupportedContent::class.java) { exchange.prompt }
        assertTrue(error.message!!.contains("document attachment"))
        assertTrue(error.message!!.contains("a.pdf"))
    }

    @Test
    fun `non text tool output is rejected explicitly`() {
        val exchange = exchange(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "local-call-3",
                            toolName = "search_web",
                            input = "{\"query\":\"kotlin\"}",
                            output = listOf(UIMessagePart.Image(url = "data:image/png;base64,AAAA")),
                        ),
                    ),
                ),
            )
        )

        val error = assertThrows(WebChatExchangeException.UnsupportedContent::class.java) { exchange.prompt }
        assertTrue(error.message!!.contains("tool result"))
    }

    @Test
    fun `disabled tools with supplied tools fails immediately`() {
        val error = assertThrows(WebChatExchangeException.ToolsDisabled::class.java) {
            WebChatExchange(
                messages = listOf(UIMessage.user("hi")),
                params = params(listOf(searchTool())),
                toolsEnabled = false,
            )
        }
        assertTrue(error.message!!.contains("toolsEnabled is false"))
    }

    @Test
    fun `tools enabled without a tool list stays plain text`() {
        val exchange = WebChatExchange(
            messages = listOf(UIMessage.user("hi")),
            params = params(emptyList()),
            toolsEnabled = true,
        ).apply { nonceFactory = { TEST_NONCE } }

        assertFalse(exchange.prompt.contains(WebChatExchange.PROTOCOL_ID))
        assertTrue(exchange.prompt.contains("\"role\":\"user\""))
        assertEquals("stop", (exchange.complete("plain").last() as StreamChunk.Finish).finishReason)
    }

    @Test
    fun `exchange never executes a tool itself`() {
        val tool = Tool(
            name = "search_web",
            description = "Search the web.",
            parameters = { searchSchema() },
            execute = { throw AssertionError("the exchange must not execute tools") },
        )
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(tool))

        val result = exchange.result(
            envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\"}"))),
        )

        val part = result.message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search_web", part.toolName)
        assertTrue(part.output.isEmpty())
    }

    // --------------------------------------------------------------- 提示词与 nonce

    @Test
    fun `tool prompt declares the protocol and the exchange nonce`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val prompt = exchange.prompt

        assertTrue(prompt.contains("no native function calling API"))
        assertTrue(prompt.contains(WebChatExchange.PROTOCOL_ID))
        assertTrue(prompt.contains("\"nonce\":\"$TEST_NONCE\""))
        assertTrue(prompt.contains("search_web"))
        assertTrue(prompt.contains("\"type\":\"object\""))
        assertTrue(prompt.contains("at most ${WebChatExchange.MAX_TOOL_CALLS} calls"))
    }

    @Test
    fun `default nonce factory yields a fresh 128 bit hex nonce`() {
        val pattern = Regex("\"nonce\":\"([0-9a-f]{32})\"")
        val first = WebChatExchange(
            messages = listOf(UIMessage.user("hi")),
            params = params(listOf(searchTool())),
            toolsEnabled = true,
        ).prompt
        val second = WebChatExchange(
            messages = listOf(UIMessage.user("hi")),
            params = params(listOf(searchTool())),
            toolsEnabled = true,
        ).prompt

        val firstNonce = pattern.find(first)?.groupValues?.get(1)
        val secondNonce = pattern.find(second)?.groupValues?.get(1)
        assertNotNull(firstNonce)
        assertNotNull(secondNonce)
        assertEquals(32, firstNonce!!.length)
        assertNotEquals(firstNonce, secondNonce)
    }

    // -------------------------------------------------------------- 正常工具协议流程

    @Test
    fun `valid envelope with one call produces text then tool then finish`() {
        val exchange = exchange(listOf(UIMessage.user("search")), tools = listOf(searchTool()))

        val chunks = exchange.complete(
            envelope(text = "searching now", toolCalls = listOf(toolCall("search_web", "{\"query\":\"kotlin\"}"))),
            responseId = "resp-2",
        )

        assertEquals(
            listOf("TextStart", "TextDelta", "TextEnd", "ToolCallStart", "ToolCallDelta", "ToolCallEnd", "Finish"),
            chunks.map { it::class.simpleName },
        )
        assertEquals("searching now", (chunks[1] as StreamChunk.TextDelta).text)

        val start = chunks[3] as StreamChunk.ToolCallStart
        assertEquals("search_web", start.toolName)
        // 调用 id 由客户端生成，不是模型给的
        assertTrue(start.id.isNotBlank())
        assertTrue(start.id != "call-1")

        val delta = chunks[4] as StreamChunk.ToolCallDelta
        assertEquals(start.id, delta.id)
        assertEquals("kotlin", json.parseToJsonElement(delta.inputDelta).jsonObject["query"]!!.jsonPrimitive.content)

        val finish = chunks.last() as StreamChunk.Finish
        assertEquals("tool_calls", finish.finishReason)
        assertEquals("resp-2", finish.responseId)
    }

    @Test
    fun `text only envelope finishes with stop and emits no tool chunks`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val chunks = exchange.complete(envelope(text = "no tools needed", toolCalls = emptyList()))

        assertEquals(listOf("TextStart", "TextDelta", "TextEnd", "Finish"), chunks.map { it::class.simpleName })
        assertEquals("stop", (chunks.last() as StreamChunk.Finish).finishReason)
    }

    @Test
    fun `two validated calls are both emitted and get separate ids`() {
        val exchange = exchange(listOf(UIMessage.user("search twice")), tools = listOf(searchTool()))

        val chunks = exchange.complete(
            envelope(
                toolCalls = listOf(
                    toolCall("search_web", "{\"query\":\"first\"}"),
                    toolCall("search_web", "{\"query\":\"second\"}"),
                ),
            )
        )

        val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        assertEquals(2, starts.size)
        assertNotEquals(starts[0].id, starts[1].id)
        assertEquals("tool_calls", (chunks.last() as StreamChunk.Finish).finishReason)
    }

    @Test
    fun `result builds assistant message with empty tool output`() {
        val exchange = exchange(listOf(UIMessage.user("search")), tools = listOf(searchTool()))

        val result = exchange.result(
            envelope(text = "on it", toolCalls = listOf(toolCall("search_web", "{\"query\":\"kotlin\"}"))),
            responseId = "resp-3",
        )

        assertEquals("resp-3", result.id)
        assertEquals("gemini-web", result.model)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(MessageRole.ASSISTANT, result.message.role)
        assertEquals("on it", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)

        val tool = result.message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search_web", tool.toolName)
        assertEquals("kotlin", json.parseToJsonElement(tool.input).jsonObject["query"]!!.jsonPrimitive.content)
        // 还没执行，output 必须为空
        assertTrue(tool.output.isEmpty())
        assertFalse(tool.isExecuted)
    }

    @Test
    fun `unicode text and arguments survive the round trip`() {
        val exchange = exchange(listOf(UIMessage.user("你好")), tools = listOf(searchTool()))
        val query = "日本語 🎌 café"

        val chunks = exchange.complete(
            envelope(text = "好的 🌍", toolCalls = listOf(toolCall("search_web", "{\"query\":${jsonString(query)}}"))),
        )

        assertEquals("好的 🌍", (chunks[1] as StreamChunk.TextDelta).text)
        val delta = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals(query, json.parseToJsonElement(delta.inputDelta).jsonObject["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quoted envelope inside prose is not executed`() {
        val exchange = exchange(listOf(UIMessage.user("show me the raw protocol")), tools = listOf(searchTool()))
        val example = envelope(text = "hi", toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\"}")))
        val reply = "Sure, here is the shape you asked about:\n```json\n$example\n```\nThat is it."

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(reply)
        }
        assertTrue(error.message!!.contains("invalid tool protocol reply"))
        // 引用代码不会被执行，也不会有半成品结果
        assertThrows(WebChatExchangeException.ProtocolViolation::class.java) { exchange.result(reply) }
        // 上下文里的示例作为普通文本保留，mention 不代表执行
        assertTrue(exchange.prompt.contains("show me the raw protocol"))
    }

    // -------------------------------------------------------------- 协议不合法拒绝

    @Test
    fun `wrong nonce is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(
                    text = "hi",
                    toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\"}")),
                    nonce = "ffffffffffffffffffffffffffffffff",
                )
            )
        }
        assertTrue(error.message!!.contains("nonce"))
    }

    @Test
    fun `missing nonce is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                "{\"protocol\":\"${WebChatExchange.PROTOCOL_ID}\",\"text\":\"hi\",\"tool_calls\":[]}"
            )
        }
        assertTrue(error.message!!.contains("nonce"))
    }

    @Test
    fun `duplicate envelope field is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                "{\"protocol\":\"${WebChatExchange.PROTOCOL_ID}\",\"nonce\":\"$TEST_NONCE\"," +
                    "\"text\":\"hi\",\"text\":\"again\",\"tool_calls\":[]}"
            )
        }
        assertTrue(error.message!!.contains("duplicate object key"))
    }

    @Test
    fun `markdown fence is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete("```json\n" + envelope(text = "hi") + "\n```")
        }
        assertTrue(error.message!!.contains("invalid tool protocol reply"))
    }

    @Test
    fun `trailing content after the envelope is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(text = "hi") + " hope that helps!")
        }
        assertTrue(error.message!!.contains("unexpected content after the JSON value"))
    }

    @Test
    fun `envelope wrapped in quotes is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete("\"" + envelope(text = "hi").replace("\"", "\\\"") + "\"")
        }
        assertTrue(error.message!!.contains("must be a single JSON object"))
    }

    @Test
    fun `unknown extra envelope field is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                "{\"protocol\":\"${WebChatExchange.PROTOCOL_ID}\",\"nonce\":\"$TEST_NONCE\"," +
                    "\"text\":\"hi\",\"tool_calls\":[],\"finish_reason\":\"stop\"}"
            )
        }
        assertTrue(error.message!!.contains("unexpected field"))
    }

    @Test
    fun `tool call with invented id is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))
        val call = "{\"id\":\"call-1\",\"name\":\"search_web\",\"arguments\":{\"query\":\"x\"}}"

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(call)))
        }
        assertTrue(error.message!!.contains("unsupported field \"id\""))
    }

    @Test
    fun `unknown tool name is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("delete_everything", "{}"))))
        }
        assertTrue(error.message!!.contains("not an allowed tool"))
    }

    @Test
    fun `tool name is matched exactly`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("Search_Web", "{\"query\":\"x\"}"))))
        }
        assertTrue(error.message!!.contains("not an allowed tool"))
    }

    @Test
    fun `non object arguments are rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf("{\"name\":\"search_web\",\"arguments\":[\"x\"]}")))
        }
        assertTrue(error.message!!.contains("must be a JSON object"))
    }

    @Test
    fun `missing arguments are rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf("{\"name\":\"search_web\"}")))
        }
        assertTrue(error.message!!.contains("missing \"arguments\""))
    }

    @Test
    fun `one invalid call rejects the whole reply`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool(), clockTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(
                    text = "doing two things",
                    toolCalls = listOf(
                        toolCall("search_web", "{\"query\":\"valid\"}"),
                        toolCall("get_time", "{\"when\":\"now\"}"),
                    ),
                )
            )
        }
        assertTrue(error.message!!.contains("missing required property \"zone\""))
        // 前一个合法调用也不会被放行：整体失败，拿不到任何 chunk 或消息
        assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.result(
                envelope(
                    toolCalls = listOf(
                        toolCall("search_web", "{\"query\":\"valid\"}"),
                        toolCall("get_time", "{\"when\":\"now\"}"),
                    ),
                )
            )
        }
    }

    @Test
    fun `more than sixteen calls is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))
        val calls = (1..17).map { index -> toolCall("search_web", "{\"query\":\"q$index\"}") }

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = calls))
        }
        assertTrue(error.message!!.contains("limit is ${WebChatExchange.MAX_TOOL_CALLS}"))
    }

    @Test
    fun `reply larger than one megabyte is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))
        val huge = envelope(text = "a".repeat(WebChatExchange.MAX_RESPONSE_BYTES))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(huge)
        }
        assertTrue(error.message!!.contains("byte limit"))
    }

    // ----------------------------------------------------------------- schema 校验

    @Test
    fun `missing required property is rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("search_web", "{\"limit\":3}"))))
        }
        assertTrue(error.message!!.contains("missing required property \"query\""))
    }

    @Test
    fun `wrong primitive type and out of bounds numbers are rejected`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val wrongType = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":42}"))))
        }
        assertTrue(wrongType.message!!.contains("must be of type string"))

        val outOfBounds = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\",\"limit\":99}"))))
        }
        assertTrue(outOfBounds.message!!.contains("must be <= 10.0"))

        val notInteger = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\",\"limit\":1.5}"))))
        }
        assertTrue(notInteger.message!!.contains("must be of type integer"))

        val tooLong = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":\"${"x".repeat(21)}\"}"))),
            )
        }
        assertTrue(tooLong.message!!.contains("at most 20 characters"))
    }

    @Test
    fun `array items and item count are enforced`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val badItem = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\",\"tags\":[1,2]}"))),
            )
        }
        assertTrue(badItem.message!!.contains("must be of type string"))

        val tooMany = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(
                    toolCalls = listOf(toolCall("search_web", "{\"query\":\"x\",\"tags\":[\"a\",\"b\",\"c\"]}")),
                )
            )
        }
        assertTrue(tooMany.message!!.contains("at most 2 items"))
    }

    @Test
    fun `nested additionalProperties false is enforced`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val error = assertThrows(WebChatExchangeException.ProtocolViolation::class.java) {
            exchange.complete(
                envelope(
                    toolCalls = listOf(
                        toolCall("search_web", "{\"query\":\"x\",\"options\":{\"safe\":true,\"extra\":1}}"),
                    ),
                )
            )
        }
        assertTrue(error.message!!.contains("additionalProperties is false"))
    }

    // ---------------------------------------------------------------- schema 允许的情况

    @Test
    fun `schema compliant arguments are accepted`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(searchTool()))

        val chunks = exchange.complete(
            envelope(
                text = "ok",
                toolCalls = listOf(
                    toolCall(
                        "search_web",
                        "{\"query\":\"kotlin\",\"limit\":3,\"tags\":[\"a\"],\"options\":{\"safe\":false}}",
                    ),
                ),
            )
        )

        assertEquals("tool_calls", (chunks.last() as StreamChunk.Finish).finishReason)
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallStart })
    }

    @Test
    fun `tool without declared schema accepts any arguments object`() {
        val exchange = exchange(listOf(UIMessage.user("hi")), tools = listOf(looseTool()))

        val chunks = exchange.complete(envelope(toolCalls = listOf(toolCall("echo", "{\"anything\":[1,2]}"))))

        assertEquals("tool_calls", (chunks.last() as StreamChunk.Finish).finishReason)
    }

    // -------------------------------------------------------------------- helpers

    private fun exchange(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        toolsEnabled: Boolean = tools.isNotEmpty(),
        nonce: String = TEST_NONCE,
    ): WebChatExchange = WebChatExchange(
        messages = messages,
        params = params(tools),
        toolsEnabled = toolsEnabled,
    ).apply {
        nonceFactory = { nonce }
    }

    private fun params(tools: List<Tool>): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "gemini-web", displayName = "Gemini Web"),
        tools = tools,
    )

    private fun searchTool(schema: InputSchema? = searchSchema()): Tool = Tool(
        name = "search_web",
        description = "Search the web.",
        parameters = { schema },
        execute = { emptyList() },
    )

    private fun clockTool(): Tool = Tool(
        name = "get_time",
        description = "Return the current time.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("zone", buildJsonObject { put("type", "string") })
                },
                required = listOf("zone"),
            )
        },
        execute = { emptyList() },
    )

    /** 未声明 schema 的工具：只要 arguments 是 JSON 对象就放行。 */
    private fun looseTool(): Tool = Tool(
        name = "echo",
        description = "Echo whatever it gets.",
        execute = { emptyList() },
    )

    private fun searchSchema(): InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put(
                "query",
                buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 20)
                },
            )
            put(
                "limit",
                buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 10)
                },
            )
            put(
                "tags",
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("maxItems", 2)
                },
            )
            put(
                "options",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("safe", buildJsonObject { put("type", "boolean") })
                        },
                    )
                    put("additionalProperties", false)
                },
            )
        },
        required = listOf("query"),
    )

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun toolCall(name: String, arguments: String): String =
        "{\"name\":${jsonString(name)},\"arguments\":$arguments}"

    private fun envelope(
        text: String = "",
        toolCalls: List<String> = emptyList(),
        nonce: String = TEST_NONCE,
        protocol: String = WebChatExchange.PROTOCOL_ID,
    ): String = buildString {
        append("{\"protocol\":").append(jsonString(protocol))
        append(",\"nonce\":").append(jsonString(nonce))
        append(",\"text\":").append(jsonString(text))
        append(",\"tool_calls\":[").append(toolCalls.joinToString(",")).append("]}")
    }

    companion object {
        private const val TEST_NONCE = "0123456789abcdef0123456789abcdef"
    }
}
