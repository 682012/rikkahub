package me.rerere.ai.provider.providers.claudeweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionCredentialProvider
import me.rerere.ai.provider.WebSessionService
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal const val ORG_A = "1f0a7b2c-3d4e-4f5a-8b6c-7d8e9f0a1b2c"
internal const val ORG_B = "2a1b8c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d"
internal const val ORG_NOT_LISTED = "3b2c9d4e-5f6a-4b7c-8d8e-9f0a1b2c3d4e"

/** 合成数据，没有任何真实抓包内容。 */
internal object ClaudeWebFixtures {

    fun event(name: String, data: String): String = "event: $name\ndata: $data\n\n"

    fun json(value: String): String = JsonPrimitive(value).toString()

    /** 现代网页端的 completion 流：message_start + text 块 + message_stop。 */
    fun textStream(vararg deltas: String, terminal: Boolean = true): String = buildString {
        append(
            event(
                "message_start",
                """{"type":"message_start","message":{"id":"msg_sanitized","type":"message","role":"assistant","content":[]}}""",
            )
        )
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            )
        )
        deltas.forEach { delta ->
            append(
                event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${json(delta)}}}""",
                )
            )
        }
        append(event("content_block_stop", """{"type":"content_block_stop","index":0}"""))
        if (terminal) {
            append(
                event(
                    "message_delta",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                )
            )
            append(event("message_stop", """{"type":"message_stop"}"""))
        }
    }
}

/**
 * 假后端：只替换 socket。URL 拼接、请求体、状态码分类和 SSE 解析全部走生产代码。
 */
internal class ClaudeWebFakeBackend(
    private val organizationsBody: String = """[{"uuid":"$ORG_A","is_personal":true}]""",
    private val completionBody: String = ClaudeWebFixtures.textStream("你好", "，世界"),
    private val organizationsCode: Int = 200,
    private val completionCode: Int = 200,
    private val organizationsContentType: String = "application/json",
    private val completionContentType: String = "text/event-stream",
) : Interceptor {

    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val path = request.url.encodedPath
        return when {
            path.endsWith("/api/organizations") ->
                response(request, organizationsCode, organizationsBody, organizationsContentType)

            path.endsWith("/completion") ->
                response(request, completionCode, completionBody, completionContentType)

            else -> response(request, 200, """{"uuid":"${requestedConversationUuid(request)}"}""", "application/json")
        }
    }

    fun completionRequests(): List<Request> = requests.filter { it.url.encodedPath.endsWith("/completion") }

    fun createRequests(): List<Request> =
        requests.filter { it.url.encodedPath.endsWith("/chat_conversations") }

    fun requestedConversationUuid(request: Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        return body["uuid"]!!.jsonPrimitive.content
    }

    private fun response(request: Request, code: Int, body: String, contentType: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
}

internal class ClaudeWebFakeVault(
    var credential: WebSessionCredential = WebSessionCredential(
        WebSessionService.CLAUDE,
        mapOf(
            "sessionKey" to "sk-ant-sid01-sanitized",
            // 浏览器自己的 Cookie，必须永远不出现在请求里。
            "cf_clearance" to "browser-only-cookie",
        ),
    ),
) : WebSessionCredentialProvider {
    override suspend fun getSession(setting: ProviderSetting.WebAccount): WebSessionCredential = credential
}

internal fun testProvider(
    backend: Interceptor,
    vault: WebSessionCredentialProvider = ClaudeWebFakeVault(),
): ClaudeWebProvider {
    val client = OkHttpClient.Builder().addInterceptor(backend).build()
    return ClaudeWebProvider(vault, OkHttpClaudeWebTransport(client))
}

internal fun testSetting(
    organizationId: String? = null,
    toolBridgeEnabled: Boolean = false,
) = ProviderSetting.ClaudeWeb(
    organizationId = organizationId,
    toolBridgeEnabled = toolBridgeEnabled,
)

internal fun testParams(modelId: String = CLAUDE_WEB_DEFAULT_MODEL_ID) =
    TextGenerationParams(model = Model(modelId = modelId))
