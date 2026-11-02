package me.rerere.ai.provider.providers.geminiweb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionCredentialProvider
import me.rerere.ai.provider.forPrivateWebSession
import me.rerere.ai.provider.web.WebChatExchange
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient

/**
 * Experimental "use the web page, not an API key" provider for gemini.google.com.
 *
 * Deliberate properties:
 * - Only `https://gemini.google.com` is contacted. The session cookie never leaves that host, there is no
 *   Google cookie refresh (refreshing would log the user's browser out) and no bot-protection workaround.
 *   If Google refuses the client, the user is told to sign in again in a browser and re-import cookies.
 * - Authentication/challenge/rate-limit answers surface as [me.rerere.ai.provider.WebSessionException],
 *   which is not an `IOException`, so nothing upstream retries it into an account lockout.
 * - [listModels] verifies the imported session with a real `/app` bootstrap request; it never returns a
 *   static list that would make a signed-out account look usable.
 * - The web endpoint takes no model id. The single `web-default` entry means "whatever the account's own
 *   default model is"; an arbitrary model id is rejected instead of pretending it was selected.
 * - Every request sends the complete local context as one standalone web chat. The user's existing remote
 *   chats are never read, continued or deleted.
 * - Plain text only. Image/document/audio/video parts are rejected loudly instead of being dropped.
 * - The answer is accepted only after the terminal candidate marker. Tool replies are buffered and only
 *   handed to the bridge once complete, so a truncated answer can never execute half a tool call.
 */
class GeminiWebProvider internal constructor(
    private val credentials: WebSessionCredentialProvider,
    private val transport: GeminiWebTransport,
) : Provider<ProviderSetting.GeminiWeb> {

    /** Production constructor: session-isolated OkHttp client, fixed endpoints. */
    constructor(
        client: OkHttpClient,
        credentials: WebSessionCredentialProvider,
    ) : this(
        credentials = credentials,
        transport = OkHttpGeminiWebTransport(client.forPrivateWebSession()),
    )

    /**
     * A real bootstrap request is the login check: a cookie set that Google rejects throws
     * [me.rerere.ai.provider.WebSessionException] here, instead of a static list claiming to be signed in.
     */
    override suspend fun listModels(providerSetting: ProviderSetting.GeminiWeb): List<Model> {
        val credential: WebSessionCredential = credentials.getSession(providerSetting)
        transport.bootstrap(credential)
        return listOf(defaultModel())
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requireTextOnly(messages)
        requireDefaultModel(params)
        val toolsEnabled = providerSetting.toolBridgeEnabled && params.tools.isNotEmpty()
        val exchange = WebChatExchange(messages, params, toolsEnabled = toolsEnabled)
        val (text, responseId) = session(providerSetting).completeText(exchange.prompt)
        // Validation happens inside the exchange; a malformed reply throws instead of returning half a call.
        return exchange.result(text, responseId)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = channelFlow {
        requireTextOnly(messages)
        requireDefaultModel(params)
        val toolsEnabled = providerSetting.toolBridgeEnabled && params.tools.isNotEmpty()
        val exchange = WebChatExchange(messages, params, toolsEnabled = toolsEnabled)
        val session = session(providerSetting)

        // Snapshot revisions cannot be represented by append-only UI deltas. Keep the latest
        // complete candidate, then emit; this is deliberately buffered, not live token streaming.
        val (text, responseId) = session.completeText(exchange.prompt)
        exchange.complete(text, responseId).forEach { send(it) }
    }

    private suspend fun session(providerSetting: ProviderSetting.GeminiWeb): GeminiWebChatSession {
        val credential: WebSessionCredential = credentials.getSession(providerSetting)
        return GeminiWebChatSession(transport = transport, credential = credential)
    }

    /** The web request carries no model selector; accepting another id would be a false claim. */
    private fun requireDefaultModel(params: TextGenerationParams) {
        require(params.model.modelId == GEMINI_WEB_DEFAULT_MODEL_ID) {
            "Gemini 网页实验通道不能选择模型（请求里没有 model 头），请使用唯一的 " +
                "$GEMINI_WEB_DEFAULT_MODEL_ID 条目，让账号自己的默认模型回答。"
        }
    }

    /**
     * Rejects media parts instead of quietly sending a prompt with the picture missing.
     * Tool parts stay allowed: history with tool results is plain text once the bridge rendered it.
     */
    private fun requireTextOnly(messages: List<UIMessage>) {
        for (message in messages) {
            for (part in message.parts) {
                val kind = when (part) {
                    is UIMessagePart.Image -> "图片"
                    is UIMessagePart.Video -> "视频"
                    is UIMessagePart.Audio -> "音频"
                    is UIMessagePart.Document -> "文件"
                    else -> null
                } ?: continue
                throw IllegalArgumentException(
                    "Gemini 网页实验通道只支持纯文本：当前消息包含$kind，已拒绝发送而不是丢弃附件。" +
                        "请改用其他提供商或移除该附件。"
                )
            }
        }
    }

    private fun defaultModel() = Model(
        modelId = GEMINI_WEB_DEFAULT_MODEL_ID,
        displayName = "网页默认",
        // The web page has no model parameter; this id only means "let the account default run".
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
        // Client-side capacity estimate for local compression only, not an upstream context window.
        contextLength = GEMINI_WEB_DEFAULT_CONTEXT_LENGTH,
    )

    private companion object {
        const val TEXT_ID = "gemini-web-text"
    }
}
