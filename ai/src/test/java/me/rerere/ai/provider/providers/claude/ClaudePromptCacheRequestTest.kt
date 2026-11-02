package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudePromptCacheRequestTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun buildRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(provider, providerSetting, messages, params, stream) as JsonObject
    }

    private fun dummyTool(): Tool {
        return Tool(
            name = "dummy_tool",
            description = "dummy",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    @Test
    fun `summary keeps declarations and system prefix but never lets custom settings reenable tools`() {
        val setting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(UIMessage.system("stable system"), UIMessage.user("history"))
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool()),
            customBody = listOf(me.rerere.ai.provider.CustomBody("tool_choice", JsonPrimitive("auto"))),
        )
        val chat = buildRequest(setting, messages, params)
        val summary = buildRequest(setting, messages + UIMessage.user("Summarize, do not call tools"),
            params.copy(disableToolCalls = true))
        assertEquals(chat["tools"], summary["tools"])
        assertEquals(chat["system"], summary["system"])
        assertEquals("none", summary["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=false should not add cache_control anywhere`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = false)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should not have cache_control
        val system = request["system"]?.jsonArray
        assertNotNull(system)
        assertTrue(system!!.isNotEmpty())
        assertNull(system.last().jsonObject["cache_control"])

        // tools should not have cache_control
        val tools = request["tools"]?.jsonArray
        assertNotNull(tools)
        assertTrue(tools!!.isNotEmpty())
        assertNull(tools.last().jsonObject["cache_control"])

        // messages should not have cache_control
        val msgs = request["messages"]!!.jsonArray
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }

    @Test
    fun `promptCaching=true should add cache_control to last system block and last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should have cache_control
        val system = request["system"]!!.jsonArray
        val systemCacheControl = system.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", systemCacheControl["type"]!!.jsonPrimitive.content)
        assertNull(systemCacheControl["ttl"])

        // tools should have cache_control
        val tools = request["tools"]!!.jsonArray
        val toolsCacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", toolsCacheControl["type"]!!.jsonPrimitive.content)
        assertNull(toolsCacheControl["ttl"])
    }

    @Test
    fun `promptCaching=true without system should add cache_control to last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        assertNull(request["system"])

        val tools = request["tools"]!!.jsonArray
        val cacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
        assertNull(cacheControl["ttl"])
    }

    @Test
    fun `promptCaching=true with one hour ttl should add ttl to cache_control`() {
        val providerSetting = ProviderSetting.Claude(
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR
        )
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        val systemCacheControl = request["system"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", systemCacheControl["type"]!!.jsonPrimitive.content)
        assertEquals("1h", systemCacheControl["ttl"]!!.jsonPrimitive.content)

        val toolCacheControl = request["tools"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", toolCacheControl["type"]!!.jsonPrimitive.content)
        assertEquals("1h", toolCacheControl["ttl"]!!.jsonPrimitive.content)

        val messagesJson = request["messages"]!!.jsonArray
        val firstUserMessage = messagesJson.first { msg ->
            msg.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }.jsonObject
        val messageCacheControl = firstUserMessage["content"]!!
            .jsonArray
            .last()
            .jsonObject["cache_control"]!!
            .jsonObject
        assertEquals("ephemeral", messageCacheControl["type"]!!.jsonPrimitive.content)
        assertEquals("1h", messageCacheControl["ttl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=true should keep the second-to-last real user message as a breakpoint`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question"),
            UIMessage.assistant("second answer"),
            UIMessage.user("third question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Should have 3 real user messages
        val userMsgIndices = realUserIndices(msgs)
        assertEquals(3, userMsgIndices.size)

        // Second-to-last real user message (index 1 in userMsgIndices) keeps its cache_control
        assertCacheControl(msgs[userMsgIndices[1]].jsonObject, expectedTtl = null)

        // The last message is the tail breakpoint, so it has one too
        assertCacheControl(msgs.last().jsonObject, expectedTtl = null)

        // First user message should NOT have cache_control
        val firstContent = msgs[userMsgIndices[0]].jsonObject["content"]!!.jsonArray
        assertNull(firstContent.last().jsonObject["cache_control"])
    }

    @Test
    fun `promptCaching=true with only one user message should cache that user message`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("only question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // 只有一条 user 消息时它就是尾部断点, 上一轮的读点不存在
        assertEquals(1, msgs.size)
        assertCacheControl(msgs.single().jsonObject, expectedTtl = null)
        assertEquals(1, countCacheControl(msgs))
    }

    @Test
    fun `multi step tool loop caches the tail tool_result instead of only the previous turn`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, MessagesInToolLoop, params)
        val msgs = request["messages"]!!.jsonArray

        // user, assistant(text), user, assistant(text+tool_use), user(tool_result),
        // assistant(text+tool_use), user(tool_result)
        assertEquals(7, msgs.size)
        assertEquals("user", roleOf(msgs.last()))
        // 最后一条消息是 tool_result, 尾部断点必须落在这里, 否则每一步都要重新 prefill 整轮工具输出
        val tailBlocks = msgs.last().jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", tailBlocks.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertCacheControl(msgs.last().jsonObject, expectedTtl = null)

        // 原位置的断点保留: 倒数第二条真实 user 消息 (第一条 user)
        val userMsgIndices = realUserIndices(msgs)
        assertEquals(2, userMsgIndices.size)
        assertCacheControl(msgs[userMsgIndices[0]].jsonObject, expectedTtl = null)

        // 中间的消息不带断点, messages 上一共只有两个
        assertNull(msgs[1].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"])
        assertNull(msgs[3].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"])
        assertEquals(2, countCacheControl(msgs))
    }

    @Test
    fun `a request carries at most four cache breakpoints`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, MessagesInToolLoop, params)

        // system 1 + tools 1 + messages 2
        assertEquals(4, countCacheControl(request))
        // 显式尾部断点已经落地, 顶层自动断点不再重复占槽 (否则 4 + 1 会超上限)
        assertNull(request["cache_control"])
    }

    @Test
    fun `the top level automatic breakpoint stays as a fallback when no tail block can host one`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        // 只有 system 消息: 没有 messages 可以落尾部断点
        val request = buildRequest(
            providerSetting,
            listOf(UIMessage.system("system prompt")),
            params
        )

        assertEquals(0, request["messages"]!!.jsonArray.size)
        val cacheControl = request["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.contentOrNull)
        // system 1 + tools 1 + 自动 1
        assertEquals(3, countCacheControl(request))
    }

    @Test
    fun `tail breakpoint keeps the ttl of the top level automatic breakpoint`() {
        val providerSetting = ProviderSetting.Claude(
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, MessagesInToolLoop, params)

        val tailCacheControl = request["messages"]!!.jsonArray.last()
            .jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", tailCacheControl["type"]!!.jsonPrimitive.content)
        assertEquals("1h", tailCacheControl["ttl"]!!.jsonPrimitive.content)
        assertEquals(4, countCacheControl(request))
    }

    @Test
    fun `promptCaching=false keeps the request free of every breakpoint`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = false)
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, MessagesInToolLoop, params)

        assertNull(request["cache_control"])
        assertEquals(0, countCacheControl(request))
    }

    private fun roleOf(message: JsonElement): String? =
        message.jsonObject["role"]?.jsonPrimitive?.contentOrNull

    private fun realUserIndices(msgs: JsonArray): List<Int> = msgs.mapIndexedNotNull { index, msg ->
        val obj = msg.jsonObject
        if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
            val content = obj["content"]!!.jsonArray
            val isToolResult = content.any {
                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
            }
            index.takeIf { !isToolResult }
        } else {
            null
        }
    }

    /** 断言这条 message 的最后一个 content block 带 cache_control; [expectedTtl] 为 null 时不能带 ttl */
    private fun assertCacheControl(message: JsonObject, expectedTtl: String?) {
        val cacheControl = message["content"]!!.jsonArray.last().jsonObject["cache_control"]
        assertNotNull(cacheControl)
        val obj = cacheControl!!.jsonObject
        assertEquals("ephemeral", obj["type"]!!.jsonPrimitive.contentOrNull)
        val ttl = (obj["ttl"] as? JsonPrimitive)?.contentOrNull
        if (expectedTtl == null) {
            assertNull(ttl)
        } else {
            assertEquals(expectedTtl, ttl)
        }
    }

    /** 递归统计请求体里 cache_control 的个数 */
    private fun countCacheControl(element: JsonElement): Int = when (element) {
        is JsonObject -> element.entries.sumOf { (key, value) ->
            (if (key == "cache_control") 1 else 0) + countCacheControl(value)
        }

        is JsonArray -> element.sumOf { countCacheControl(it) }
        else -> 0
    }

    private companion object {
        /** system + 两个用户轮 + 两次工具调用的 agent 轮: 最后一条消息是 tool_result */
        val MessagesInToolLoop = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("run the tests"),
            UIMessage.assistant("first answer"),
            UIMessage.user("now fix the failing one"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Looking at the failure"),
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "dummy_tool",
                        input = """{"query":"a"}""",
                        output = listOf(UIMessagePart.Text("first tool output")),
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Checking the logs"),
                    UIMessagePart.Tool(
                        toolCallId = "call_2",
                        toolName = "dummy_tool",
                        input = """{"query":"b"}""",
                        output = listOf(UIMessagePart.Text("second tool output")),
                    ),
                ),
            ),
        )
    }
}
