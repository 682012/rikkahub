package me.rerere.ai.provider.providers.claudeweb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
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
import okhttp3.OkHttpClient

/**
 * 实验性的 "直接用网页会话，而不是 API Key" 提供商（claude.ai）。
 *
 * 刻意的取舍：
 * - 只访问 `https://claude.ai` 上的三个官方端点；cookie 只允许 `sessionKey`，不会转发 `cf_clearance`
 *   等浏览器 Cookie，也不模仿 Claude Code / Chrome 的 UA、TLS 指纹或任何私有 backend。
 * - 401 / 403 / 429 / 挑战页 / 3xx / 200 却返回 HTML：一律 [me.rerere.ai.provider.WebSessionException]
 *   （不是 IOException），因此没有任何重试层会把"会话失效"放大成账号风控。
 * - 每次请求都新建一个空会话：用户已有的远端会话不会被读取、续写或删除；上下文由 [WebChatExchange]
 *   以纯文本完整携带。
 * - 附件（图片/文件/音频/视频）不在本通道支持范围：由 exchange 明确拒绝，绝不静默丢弃。
 * - 网页端没有逐 token 的稳定协议保证，所以即使 [streamText] 也是**缓冲**的：整段回答在拿到终止事件后
 *   才交给 exchange 校验并输出，因此不存在"半个工具调用被执行"或"截断当成功"。
 * - 只提供 [CLAUDE_WEB_DEFAULT_MODEL_ID] 一个模型 id；设置里出现别的 id 直接拒绝，不会用新 id 冒充
 *   网页默认模型。
 */
class ClaudeWebProvider internal constructor(
    private val credentials: WebSessionCredentialProvider,
    private val transport: ClaudeWebTransport,
) : Provider<ProviderSetting.ClaudeWeb> {

    /** 生产构造：代理/路由沿用外部 client，但会移除日志、跳转跟随、自动重试与 CookieJar。 */
    constructor(
        client: OkHttpClient,
        credentials: WebSessionCredentialProvider,
    ) : this(
        credentials = credentials,
        transport = OkHttpClaudeWebTransport(client.forPrivateWebSession()),
    )

    /**
     * 列模型同时校验真实会话：组织列表接口能正常返回，才认为这个 sessionKey 还能用。
     * 只有 `claude-web-default` 一个 id；`contextLength` 是客户端估算，不代表服务端实际额度。
     */
    override suspend fun listModels(providerSetting: ProviderSetting.ClaudeWeb): List<Model> {
        validateSession(providerSetting)
        return listOf(defaultModel(providerSetting))
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.ClaudeWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val exchange = exchange(providerSetting, messages, params)
        // 先取 prompt：附件/工具能力在这里就会被 exchange 明确拒绝，不会先发出任何请求。
        val prompt = exchange.prompt
        val (text, responseId) = session(providerSetting).completeText(prompt)
        return exchange.result(text, responseId)
    }

    /**
     * 缓冲式流：整段回答收完并通过 exchange 校验后才产生 chunk。
     * 这不是"逐 token"，只是把一次完整回答按 exchange 的语义分块下发。
     */
    override suspend fun streamText(
        providerSetting: ProviderSetting.ClaudeWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = channelFlow {
        val exchange = exchange(providerSetting, messages, params)
        val prompt = exchange.prompt
        val (text, responseId) = session(providerSetting).completeText(prompt)
        exchange.complete(text, responseId).forEach { send(it) }
    }

    private suspend fun validateSession(providerSetting: ProviderSetting.ClaudeWeb) {
        val credential = credentials.getSession(providerSetting)
        val organizations = transport.organizations(credential)
        selectClaudeWebOrganization(organizations, providerSetting.organizationId)
    }

    private suspend fun session(providerSetting: ProviderSetting.ClaudeWeb): ClaudeWebChatSession {
        val credential: WebSessionCredential = credentials.getSession(providerSetting)
        return ClaudeWebChatSession(
            transport = transport,
            credential = credential,
            configuredOrganizationId = providerSetting.organizationId,
        )
    }

    private fun exchange(
        providerSetting: ProviderSetting.ClaudeWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): WebChatExchange {
        requireDefaultModel(params)
        val toolsEnabled = providerSetting.toolBridgeEnabled && params.tools.isNotEmpty()
        return WebChatExchange(messages, params, toolsEnabled = toolsEnabled)
    }

    private fun requireDefaultModel(params: TextGenerationParams) {
        val modelId = params.model.modelId.trim()
        if (modelId != CLAUDE_WEB_DEFAULT_MODEL_ID) {
            throw IllegalArgumentException(
                "Claude 网页实验通道只提供模型 \"$CLAUDE_WEB_DEFAULT_MODEL_ID\"；" +
                    "当前配置的模型 \"$modelId\" 不受支持，也不会被悄悄替换成网页默认模型。"
            )
        }
    }

    private fun defaultModel(providerSetting: ProviderSetting.ClaudeWeb) = Model(
        modelId = CLAUDE_WEB_DEFAULT_MODEL_ID,
        displayName = CLAUDE_WEB_DEFAULT_MODEL_NAME,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
        abilities = if (providerSetting.toolBridgeEnabled) listOf(ModelAbility.TOOL) else emptyList(),
        // 客户端容量估算，仅用于本地压缩判断；网页端的真实上限由账号侧决定。
        contextLength = 32_768,
    )
}
