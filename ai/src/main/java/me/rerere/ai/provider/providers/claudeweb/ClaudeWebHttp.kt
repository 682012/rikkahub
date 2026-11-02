package me.rerere.ai.provider.providers.claudeweb

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.util.json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

private val CLAUDE_WEB_JSON = "application/json".toMediaType()

private val CLAUDE_WEB_UUID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

/**
 * 组织 / 会话 id 只能以规范 UUID 的形式进入请求路径。
 *
 * 组织 id 来自服务端，是外部输入：不校验就拼进 path，服务端返回的 `../` 之类的内容就能改写我们请求的
 * 端点。这里只接受 UUID 字面量，其它一律拒绝，也绝不"修一下再用"。
 */
internal fun requireClaudeWebUuid(value: String, label: String): String {
    val trimmed = value.trim()
    val parsed = CLAUDE_WEB_UUID.matches(trimmed) && runCatching { Uuid.parse(trimmed) }.isSuccess
    if (!parsed) {
        throw WebProtocolException("Claude Web 返回的 $label 不是合法 UUID，已拒绝拼接进请求路径。")
    }
    return trimmed
}

/** 新会话 id 由本地生成（与官方网页端一致：先建会话，再在该会话里发一次 completion）。 */
internal fun newClaudeWebConversationUuid(): String = Uuid.random().toString()

/**
 * 解析 `POST /api/organizations/{org}/chat_conversations` 的响应。
 *
 * 只用响应里合法 UUID 形式的值；缺失时退回本地生成的那个 id（那次请求本来就是我们自己发起的）。
 */
internal fun parseClaudeWebCreatedConversation(body: String, requestedUuid: String): String {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
    val obj = root as? kotlinx.serialization.json.JsonObject
        ?: throw WebProtocolException("Claude Web 新建会话的响应不是 JSON 对象")
    if (obj["error"] != null) throw claudeWebApiError(obj)
    val returned = obj.string("uuid")?.takeIf { it.isNotBlank() }
        ?: return requireClaudeWebUuid(requestedUuid, "conversation")
    val validated = requireClaudeWebUuid(returned, "conversation")
    if (!validated.equals(requestedUuid, ignoreCase = true)) {
        throw WebProtocolException("Claude Web returned a different conversation ID; existing chats were not touched")
    }
    return validated
}

/** 200 也可能是一张 HTML 挑战页/登录页；这种回答绝不能被当作"模型没说话"。 */
internal fun claudeWebLooksLikeHtml(contentType: String?, body: String): Boolean {
    if (contentType?.contains("text/html", ignoreCase = true) == true) return true
    val head = body.trimStart()
    return head.startsWith("<")
}

internal fun claudeWebHtmlChallengeException(): WebSessionException = WebSessionException(
    "claude.ai 返回了网页而不是接口数据（可能被要求人机验证）；不会尝试绕过验证码、Cloudflare 或 TLS 指纹，" +
        "请在浏览器完成验证后重新导入会话。"
)

/**
 * 三个固定端点，全部在 [CLAUDE_WEB_ORIGIN] 上：
 * - `GET  /api/organizations`
 * - `POST /api/organizations/{org}/chat_conversations`
 * - `POST /api/organizations/{org}/chat_conversations/{uuid}/completion`
 *
 * 没有私有 backend，没有可配置 baseUrl，也不带任何浏览器指纹 Cookie。
 */
internal object ClaudeWebHttp {

    fun organizations(credential: WebSessionCredential): Request = Request.Builder()
        .url(apiUrl("organizations"))
        .get()
        .header("Accept", "application/json")
        .header("Origin", CLAUDE_WEB_ORIGIN)
        .header("Referer", "$CLAUDE_WEB_ORIGIN/new")
        .header("Cookie", claudeWebCookieHeader(credential))
        .header("User-Agent", CLAUDE_WEB_USER_AGENT)
        .build()

    fun createConversation(
        credential: WebSessionCredential,
        organizationUuid: String,
        conversationUuid: String,
    ): Request {
        val organization = requireClaudeWebUuid(organizationUuid, "organization")
        val conversation = requireClaudeWebUuid(conversationUuid, "conversation")
        val payload = buildJsonObject {
            put("uuid", conversation)
            put("name", "")
            put("include_conversation_preferences", true)
            put("is_temporary", false)
        }.toString()
        return Request.Builder()
            .url(apiUrl("organizations", organization, "chat_conversations"))
            .post(payload.toRequestBody(CLAUDE_WEB_JSON))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", CLAUDE_WEB_ORIGIN)
            .header("Referer", "$CLAUDE_WEB_ORIGIN/new")
            .header("Cookie", claudeWebCookieHeader(credential))
            .header("User-Agent", CLAUDE_WEB_USER_AGENT)
            .build()
    }

    fun completion(
        credential: WebSessionCredential,
        organizationUuid: String,
        conversationUuid: String,
        prompt: String,
    ): Request {
        val organization = requireClaudeWebUuid(organizationUuid, "organization")
        val conversation = requireClaudeWebUuid(conversationUuid, "conversation")
        // 请求体形状对齐网页端：一份纯文本 prompt、新会话的 parent 哨兵、消息模式渲染。
        // tools / attachments / files 一律是空数组：不注入 claude.ai 原生工具（web_search / artifacts / repl），
        // 也不把用户附件塞进网页会话。
        val payload = buildJsonObject {
            put("prompt", prompt)
            put("parent_message_uuid", CLAUDE_WEB_PARENT_MESSAGE_UUID)
            put("rendering_mode", "messages")
            put("locale", "en-US")
            put("timezone", java.util.TimeZone.getDefault().id)
            put("sync_sources", kotlinx.serialization.json.JsonArray(emptyList()))
            put("tools", kotlinx.serialization.json.JsonArray(emptyList()))
            put("attachments", kotlinx.serialization.json.JsonArray(emptyList()))
            put("files", kotlinx.serialization.json.JsonArray(emptyList()))
        }.toString()
        return Request.Builder()
            .url(
                apiUrl(
                    "organizations", organization,
                    "chat_conversations", conversation,
                    "completion",
                )
            )
            .post(payload.toRequestBody(CLAUDE_WEB_JSON))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .header("Origin", CLAUDE_WEB_ORIGIN)
            .header("Referer", "$CLAUDE_WEB_ORIGIN/chat/$conversation")
            .header("Cookie", claudeWebCookieHeader(credential))
            .header("User-Agent", CLAUDE_WEB_USER_AGENT)
            .build()
    }

    private fun apiUrl(vararg segments: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host(CLAUDE_WEB_HOST)
            .addPathSegment("api")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()
}
