package me.rerere.ai.provider.providers.claudeweb

import me.rerere.ai.provider.web.withWebResponse
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.util.json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.Reader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 传输层接口。生产实现只走 [CLAUDE_WEB_ORIGIN] 的三个固定端点；
 * 测试替换的是 socket，而不是 URL、请求体或解析。
 */
internal interface ClaudeWebTransport {
    /** 读取账号的组织列表，同时验证会话真实有效。 */
    suspend fun organizations(credential: WebSessionCredential): List<ClaudeWebOrganization>

    /** 新建一个空会话，返回它的 uuid。 */
    suspend fun createConversation(credential: WebSessionCredential, organizationUuid: String): String

    /** 在该会话里发一次纯文本 completion，逐个交出解码后的 SSE 事件。 */
    suspend fun completion(
        credential: WebSessionCredential,
        organizationUuid: String,
        conversationUuid: String,
        prompt: String,
        onEvent: (SseEvent) -> Unit,
    )
}

/**
 * 真实传输层：OkHttp，代理/路由沿用外部 client，但已经过
 * [me.rerere.ai.provider.forPrivateWebSession]（无日志拦截器、不跟随跳转、不自动重试、无 CookieJar）。
 */
internal class OkHttpClaudeWebTransport(
    private val client: OkHttpClient,
) : ClaudeWebTransport {

    override suspend fun organizations(credential: WebSessionCredential): List<ClaudeWebOrganization> =
        client.claudeWebCall(ClaudeWebHttp.organizations(credential)) { response ->
            parseClaudeWebOrganizations(response.readClaudeWebBody().orEmpty())
        }

    override suspend fun createConversation(
        credential: WebSessionCredential,
        organizationUuid: String,
    ): String {
        val requestedUuid = newClaudeWebConversationUuid()
        return client.claudeWebCall(
            ClaudeWebHttp.createConversation(credential, organizationUuid, requestedUuid)
        ) { response ->
            parseClaudeWebCreatedConversation(response.readClaudeWebBody().orEmpty(), requestedUuid)
        }
    }

    override suspend fun completion(
        credential: WebSessionCredential,
        organizationUuid: String,
        conversationUuid: String,
        prompt: String,
        onEvent: (SseEvent) -> Unit,
    ) {
        client.claudeWebCall(
            ClaudeWebHttp.completion(credential, organizationUuid, conversationUuid, prompt)
        ) { response ->
            if (response.code !in 200..299) {
                val errorText = runCatching { response.readBoundedText(64 * 1024) }.getOrNull().orEmpty()
                throw classifyClaudeWebHttpFailure(response.code, errorText)
            }
            val contentType = response.header("Content-Type")
            if (contentType?.contains("text/html", ignoreCase = true) == true) {
                throw claudeWebHtmlChallengeException()
            }
            val body = response.body ?: throw WebProtocolException("Claude Web 响应没有正文")
            // 用 Reader 按 UTF-8 解码：多字节字符跨网络分段时不会被撕裂。
            val reader: Reader = body.byteStream().reader(Charsets.UTF_8)
            val sse = ClaudeWebSseReader(reader)
            while (true) {
                val event = sse.nextEvent() ?: break
                onEvent(event)
            }
        }
    }
}

private fun Response.readClaudeWebBody(): String? {
    if (code !in 200..299) {
        val errorText = runCatching { readBoundedText() }.getOrNull().orEmpty()
        throw classifyClaudeWebHttpFailure(code, errorText)
    }
    val text = runCatching { readBoundedText() }.getOrNull()
        ?: throw WebProtocolException("Claude Web 响应没有正文")
    if (claudeWebLooksLikeHtml(header("Content-Type"), text)) {
        throw claudeWebHtmlChallengeException()
    }
    return text
}

/**
 * 一次可取消的 OkHttp 调用。
 *
 * 取消协程时 [Call.cancel] 会立刻中止传输，[Response.use] 保证 reader/连接被释放；
 * 不需要 `withContext(Dispatchers.IO)`，也不会把阻塞读留在无人管的线程上。
 *
 * 这是 `me.rerere.ai.provider.web.withWebResponse(client, request, block)` 的临时私有版本：
 * 待公共 helper 落地后，这里直接换掉即可（签名相同）。
 */
private suspend fun <T> OkHttpClient.claudeWebCall(request: Request, block: (Response) -> T): T =
    withWebResponse(this, request, block)

private fun Response.readBoundedText(limit: Long = 2L * 1024 * 1024): String {
    val source = body.source()
    val buffer = okio.Buffer()
    while (true) {
        val read = source.read(buffer, minOf(8192, limit + 1 - buffer.size))
        if (read == -1L) break
        if (buffer.size > limit) throw WebProtocolException("Claude Web response exceeded the client size limit")
    }
    return buffer.readUtf8()
}

/**
 * Claude 网页 completion 的 SSE 状态机。
 *
 * - `content_block_start`（text 块）与 `content_block_delta/text_delta` 收进回答文本；
 * - `message_stop` 是正常终态，`message_delta.delta.stop_reason` 非空也可以作为终态；
 * - 旧版 `completion` 事件是累计快照，且必须带非空 `stop_reason` 才算终态；
 * - 服务端内置工具块（`tool_use` 等）只忽略，不触发任何本地工具调用；
 * - `error` 事件只保留错误类型，不回显服务端正文；
 * - 没有终态就 EOF 时抛 [StreamTruncatedException]（IOException），绝不把半截回答当成功。
 */
internal class ClaudeWebStreamState(
    private val maxTextChars: Int = CLAUDE_WEB_MAX_TEXT_CHARS,
) {
    private val deltaText = StringBuilder()
    private val legacyText = StringBuilder()

    private var legacyMode = false
    var messageId: String? = null
        private set
    var terminal = false
        private set
    var stopReason: String? = null
        private set

    val text: String
        get() = if (legacyMode) legacyText.toString() else deltaText.toString()

    fun accept(event: SseEvent) {
        val payload = event.data.trim()
        if (payload.isEmpty()) return
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: throw WebProtocolException("Claude Web 的 SSE 数据不是合法 JSON 对象")

        val type = root.string("type") ?: event.event.orEmpty()
        when (type) {
            "message_start" -> (root["message"] as? JsonObject)?.string("id")?.let { messageId = it }

            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject ?: return
                // 只收文本块；tool_use / server tool 块一律忽略，不在本地调用任何工具。
                if (block.string("type") == "text") appendDelta(block.string("text").orEmpty())
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject ?: return
                if (delta.string("type") == "text_delta") {
                    appendDelta(delta.string("text").orEmpty())
                }
            }

            "message_delta" -> {
                val reason = (root["delta"] as? JsonObject)?.string("stop_reason")
                if (!reason.isNullOrBlank()) {
                    stopReason = reason
                    terminal = true
                }
            }

            "message_stop" -> terminal = true

            // 旧版网页端：completion 是累计快照，只有 stop_reason 非空才算结束。
            "completion" -> {
                legacyMode = true
                root.string("completion")?.let(::replaceLegacyText)
                val reason = root.string("stop_reason")
                if (!reason.isNullOrBlank()) {
                    stopReason = reason
                    terminal = true
                }
            }

            "error" -> throw claudeWebApiError(root)

            else -> {
                // 事件名是 error，或正文里带 error 对象：只暴露类型，不回显服务端正文。
                if (event.event == "error" || root["error"] != null) throw claudeWebApiError(root)
                // ping / content_block_stop / 未知事件：忽略
            }
        }
    }

    fun finish() {
        if (!terminal) {
            throw StreamTruncatedException(
                "Claude Web 的响应流在终止事件前结束，回复可能被截断"
            )
        }
    }

    private fun appendDelta(chunk: String) {
        if (chunk.isEmpty()) return
        deltaText.append(chunk)
        requireTextLimit(deltaText)
    }

    private fun replaceLegacyText(snapshot: String) {
        legacyText.setLength(0)
        legacyText.append(snapshot)
        requireTextLimit(legacyText)
    }

    private fun requireTextLimit(builder: StringBuilder) {
        if (builder.length > maxTextChars) {
            throw WebProtocolException("Claude Web 的回答文本超过客户端上限，已中止本次回答")
        }
    }
}

/**
 * 一次完整的网页对话：组织 → 新会话 → completion。
 *
 * 每次都从读取组织开始：这一步同时是"会话是否还有效"的真实校验（401/403 会在这里就抛
 * [WebSessionException]）。用户已有的远端会话既不会被读取，也不会被续写或删除。
 */
internal class ClaudeWebChatSession(
    private val transport: ClaudeWebTransport,
    private val credential: WebSessionCredential,
    private val configuredOrganizationId: String?,
) {
    /** 收集完整回答后返回 (text, responseId)。缓冲完成前调用方拿不到任何半截回答。 */
    suspend fun completeText(prompt: String): Pair<String, String?> {
        val organizations = transport.organizations(credential)
        val organization = selectClaudeWebOrganization(organizations, configuredOrganizationId)
        val conversationUuid = transport.createConversation(credential, organization.uuid)
        val state = ClaudeWebStreamState()
        transport.completion(
            credential = credential,
            organizationUuid = organization.uuid,
            conversationUuid = conversationUuid,
            prompt = prompt,
        ) { event -> state.accept(event) }
        state.finish()
        val text = state.text
        if (text.isBlank()) {
            throw WebProtocolException("Claude Web 没有返回任何文本，无法确认回答")
        }
        return text to (state.messageId ?: conversationUuid)
    }
}
