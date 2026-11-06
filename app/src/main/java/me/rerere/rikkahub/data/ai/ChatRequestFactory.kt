package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import me.rerere.rikkahub.data.ai.learning.AssistantLearningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryBudget
import me.rerere.ai.agent.RetryDelay
import me.rerere.ai.agent.RetryGate
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.canDisableToolCallsWithDefinitions
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.expandNotices
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.channel.ChannelFailoverProvider
import me.rerere.rikkahub.data.ai.channel.ChannelTarget
import me.rerere.rikkahub.data.ai.channel.resolveChannelPlan
import me.rerere.rikkahub.data.ai.clientprofile.ProfiledRequest
import me.rerere.rikkahub.data.ai.clientprofile.applyProfile
import me.rerere.rikkahub.data.ai.execution.ExecutionGuidance
import me.rerere.rikkahub.data.ai.inputassist.InputAssistRuntime
import me.rerere.rikkahub.data.ai.inputassist.rewriteRequestMessages
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.ResponsesFilesToLocalTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.RetryDelayMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.knowledge.KnowledgeLimits
import me.rerere.rikkahub.data.knowledge.LocalKnowledgeRepository
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationClientProfile
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.runtime
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同；
 * 压缩请求的历史部分会压成纯文本（见 [CompactionRequestMessages]），因此只有 system 前缀一致。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
    val learningPrompt: String = "",
    /**
     * 本轮固定的客户端协议兼容档；`null` = 未启用（等价原生）。
     * 一轮只解析一次，所以自动选择的档位在这一轮所有工具步骤里都一样。
     */
    val clientProfile: ProfiledRequest? = null,
    /**
     * 本轮的知识库参考片段（已带来源/编号、已按预算截断）。
     * 只在会话开启知识库且本轮命中时非空；一轮只查一次，因此每个工具步骤注入的内容完全一致。
     */
    val knowledgeContext: String = "",
    /**
     * 本轮的「执行方式」短指引（由 [ExecutionGuidance] 按实际工具生成）。
     * 会话关闭高效模式、或本轮没有代码类工具时为空串，此时系统提示一个字都不变。
     */
    val executionGuidance: String = "",
    /**
     * 本轮允许尝试的有序渠道（同模型多渠道优先级，fork）。
     *
     * 空列表 = 没开启 / 没配置 / 只有一个渠道，此时 [createAgent] 不加任何包装，
     * 请求路径与旧版本完全一致。列表非空时索引 0 是本轮首发的渠道。
     */
    val channelPlan: List<ChannelTarget> = emptyList(),
    /**
     * 用户选的客户端兼容档原始值。渠道切换后要按新 provider 重新套一次兼容档
     * （OpenAI 兼容与 Claude 的档位不一样），所以这里保留原始选择而不是只留解析结果。
     */
    val clientProfilePreference: ConversationClientProfile = ConversationClientProfile.NATIVE,
) {
    // The memory snapshot is fixed for this turn; avoid rebuilding it on every tool step.
    val memoryPrompt: String by lazy { if (assistant.enableMemory) buildMemoryPrompt(memories) else "" }
}

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    private val learningRepository: AssistantLearningRepository,
    /** 本地知识库（只读检索）；按需注入不可信参考片段，不做全量注入 */
    private val knowledgeRepository: LocalKnowledgeRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
    okHttpClient: okhttp3.OkHttpClient,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        PromptInjectionTransformer,
        PlaceholderTransformer,
        me.rerere.rikkahub.data.ai.transformers.LinkReadingTransformer(okHttpClient),
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
        // Refresh after potentially slow OCR/link reads, without shifting prompt injection depths.
        TimeReminderTransformer,
    )

    /**
     * 生成结束时依次执行的输出转换链。
     *
     * [ResponsesFilesToLocalTransformer] 放最后：它按“当前 provider + 本轮消息”把 Responses 容器/库文件
     * 取回落地，必须在 think tag、正则、base64 图片都处理完之后再跑。单个文件取不到只影响那个部件，
     * 不会让整条回复失败（取消照常向上抛）。
     */
    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        ResponsesFilesToLocalTransformer,
    )

    // 压缩请求不走 OCR：OCR 是另一次视觉请求，压缩只需要知道"这里有张图片"，
    // 为了看图新增一次对外请求（可能还换来未授权的外传）不值得
    private val compactionInputTransformers: List<InputMessageTransformer> =
        inputTransformers.filterNot { it === OcrTransformer }.map {
            if (it is me.rerere.rikkahub.data.ai.transformers.LinkReadingTransformer)
                me.rerere.rikkahub.data.ai.transformers.LinkReadingTransformer(okHttpClient, allowNetwork = false) else it
        }

    // 多图逐张识别：只在用户显式标记图片时才发请求，构造函数和 DI 不动，缓存按需创建
    private val sequentialImageDescriber: SequentialImageDescriber by lazy {
        SequentialImageDescriber(context, providerManager, usageRecorder)
    }

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(
        settings: Settings,
        conversation: Conversation,
        modelOverride: Model? = null,
        requestMessages: List<UIMessage> = conversation.currentMessages,
    ): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准；modelOverride 只给压缩这类旁路请求单独指定模型
        check(settings.assistants.any { it.id == conversation.assistantId }) {
            context.getString(R.string.r21_assistant_missing)
        }
        val assistant = settings.getAssistantOf(conversation)
        val model = modelOverride ?: settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        // 客户端兼容档：纯本地判断（provider 类型 / Responses 开关 / 模型名族），不发探测请求；
        // 一轮只算一次，所以自动选择的档位在本轮所有工具步骤里保持恒定。
        val clientProfile = applyProfile(
            params = TextGenerationParams(model = model, sessionId = conversation.id.toString()),
            setting = provider,
            profile = conversation.runtime.clientProfile,
        )
        // 压缩/摘要这类旁路请求（modelOverride != null）只取文本历史：不带工具、不查知识库、不加执行指引，
        // 免得为了一份摘要多打一次 SQLite、多塞一套工具说明。
        val mainTurn = modelOverride == null
        val runtimeOptions = conversation.runtime
        // 压缩这类旁路请求只需要文本，指定了单独模型时不带工具，
        // 避免不支持工具调用的上游直接拒绝请求
        val tools = if (!mainTurn || (provider is ProviderSetting.WebAccount && !provider.toolBridgeEnabled)) {
            emptyList()
        } else {
            chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversation.id,
                // 知识库工具只在这个会话真的开启后才进工具目录；scope 固定为当前助手
                knowledgeEnabled = runtimeOptions.knowledgeEnabled,
            ).let { tools ->
                if (provider is ProviderSetting.WebAccount) tools.map { it.copy(needsApproval = { true }) }
                else tools
            }
        }
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            learningPrompt = if (modelOverride != null) "" else try {
                learningRepository.buildPrompt(learningRepository.snapshot(assistant.id.toString()), model.id.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Learning unavailable; continuing without experience hints", e)
                ""
            },
            tools = tools,
            clientProfile = clientProfile,
            // 同模型多渠道优先级（fork）：只取用户显式排过的渠道；关闭/未配置时为空列表，
            // createAgent 不加包装，请求路径与旧版本一致。
            channelPlan = if (mainTurn) {
                resolveChannelPlan(settings.providers, model, settings.channelFailover)
            } else {
                emptyList()
            },
            clientProfilePreference = conversation.runtime.clientProfile,
            // 一轮只查一次知识库，片段挂在用户请求尾部（不可信引用），工具步骤里原样复用
            knowledgeContext = if (mainTurn && runtimeOptions.knowledgeEnabled) {
                knowledgeContextFor(assistant, requestMessages)
            } else "",
            // 高效模式：按实际拿到的工具生成短指引；关掉或没有代码类工具时为空，系统提示不变
            executionGuidance = if (mainTurn && runtimeOptions.efficientTools) {
                ExecutionGuidance.forTools(tools)
            } else "",
        )
    }

    /**
     * 本轮的知识库参考片段。
     *
     * 只用**当前这条用户消息的原始文本**（`currentMessages` 里最后一条 USER）当查询：
     * 不用 assistant 侧尾输出，也没有未来消息；同一（助手, 文本）一轮只查一次。
     * 知识库不可用时只记一条日志、照常发原请求，绝不因为本地索引问题拦住对话。
     */
    private suspend fun knowledgeContextFor(assistant: Assistant, requestMessages: List<UIMessage>): String {
        val query = requestMessages
            .lastOrNull { it.role == MessageRole.USER }
            ?.toText()
            ?.trim()
            .orEmpty()
        if (query.isBlank()) return ""
        // The turn retains its result; a new turn must see document edits and deletions.
        return try {
            knowledgeRepository.buildKnowledgeContext(
                assistantId = assistant.id,
                query = query,
                maxChars = KnowledgeLimits.CONTEXT_DEFAULT_MAX_CHARS,
            ).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Knowledge unavailable; continuing without local references", e)
            ""
        }
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度；[takePendingParts] 取出要在请求模型前写进助手消息的通知。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
        takePendingParts: suspend () -> List<UIMessagePart> = { emptyList() },
        takePendingUserParts: suspend () -> List<UIMessagePart> = { emptyList() },
    ): Pair<AgentRequest, AgentHooks> {
        val usageTag = UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId)
        val baseProvider = usageRecorder.wrap(
            providerManager.getProviderByType(turn.providerSetting),
            usageTag,
        )
        val actualProvider = providerWithChannelFailover(turn, baseProvider, usageTag, processingStatus)
        fun stampChannel(message: UIMessage): UIMessage =
            (actualProvider as? ChannelFailoverProvider)?.let {
                message.copy(modelId = it.currentChannel().model.id)
            } ?: message
        val request = AgentRequest(
            provider = actualProvider,
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy()
                .copy(networkHandover = providerManager.networkHandover)
                .withTurnRetryBudget(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                // 输入优化/自查只改「发出去的那一份」：按 assistantId + 消息 hash 精确替换那一条用户消息，
                // 会话里存的原话、完整历史、通知和 tool result 全都不动。默认关闭时这里是恒等变换。
                return buildStep(
                    turn = turn,
                    messages = rewriteRequestMessages(
                        messages,
                        InputAssistRuntime.stateFor(turn.conversationId)?.takeIf { it.assistantId == turn.assistant.id },
                    ),
                    processingStatus = processingStatus,
                )
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                stampChannel(message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                })

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                stampChannel(message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                })

            override suspend fun pendingParts() = takePendingParts()

            override suspend fun pendingUserParts() = takePendingUserParts()
        }
        return request to hooks
    }

    /**
     * 同模型多渠道优先级（fork）：把 provider 包成「按用户排的顺序逐个渠道试」。
     *
     * - 计划为空（开关关闭/没配组/没有可用渠道）时原样返回基础 provider，请求路径与旧版本一致；
     *   计划里只剩一个渠道（当前渠道被删或被禁用）时也要用它，所以只判断空不看数量；
     * - 每次尝试都用目标渠道自己的 providerSetting + model 调 provider 并记 usage，
     *   统计里看到的是**实际出话**的渠道；
     * - 切换本身不重跑工具步骤：已经在会话里的工具结果原样保留，只有这一次模型请求换地址重发。
     */
    private fun providerWithChannelFailover(
        turn: ChatTurnContext,
        base: Provider<ProviderSetting>,
        tag: UsageTag,
        processingStatus: MutableStateFlow<String?>,
    ): Provider<ProviderSetting> {
        val plan = turn.channelPlan
        if (plan.isEmpty()) return base
        return ChannelFailoverProvider(
            channels = plan,
            providerFor = { target ->
                usageRecorder.wrap(providerManager.getProviderByType(target.providerSetting), tag)
            },
            // 在 prepareStep 这一步算好的参数上只改渠道相关的字段，见 rebuildParamsForChannel
            rebuildParams = { params, target -> rebuildParamsForChannel(turn, params, target) },
            onSwitch = { from, to, error ->
                Log.w(
                    TAG,
                    "Channel failover: ${from.providerSetting.name}/${from.model.modelId} -> " +
                        "${to.providerSetting.name}/${to.model.modelId}",
                    error,
                )
                // 只更新状态行；真正的"生成中"状态仍由上层在收到内容时清空
                processingStatus.value =
                    "渠道不可用，已切换到 ${to.providerSetting.name} / " +
                    to.model.displayName.ifBlank { to.model.modelId }
            },
        )
    }

    /**
     * 生成压缩摘要。
     *
     * 系统提示词、参数和 sessionId 与正常对话相同，历史部分则交给 [CompactionRequestMessages]
     * 压成带角色标记的纯文本：assistant 图片、内联 base64 和超长工具输出都不会进入压缩请求。
     * OpenAI 兼容的转发层（LiteLLM 等）会直接拒绝 assistant 消息里的图片，历史里有一条就会
     * 400 `Images in assistant messages are not supported`，压缩只需要文字，没必要冒这个险。
     *
     * 纯文本历史继续沿用正常对话的角色结构与缓存前缀；只有包含附件的压缩范围
     * 才走文本转写，兼容不接受 assistant 图片或非视觉模型的上游。
     * OpenAI/Claude 保留工具定义但强制 tool_choice=none，其余协议移除工具。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
        /** 传了就按流式接收并逐块上报真实进度；不传则维持原来的非流式请求 */
        onProgress: (suspend (CompressionChunkProgress) -> Unit)? = null,
    ): String {
        // Native OpenAI/Claude can retain declarations but force tool_choice=none. Other protocols
        // still drop definitions. Never trade summary correctness for cache hits.
        val preserveDefinitions = turn.providerSetting.canDisableToolCallsWithDefinitions
        suspend fun generate(keepDefinitions: Boolean): UIMessage? {
            val step = if (CompactionRequestMessages.requiresAdaptation(messages)) {
                buildCompactionStep(turn, messages, compressPrompt, includeTools = keepDefinitions)
            } else {
                // Text-only history retains the existing role structure and cache prefix.
                // 压缩永远不做逐张识别：摘要只需要文字，多看一遍图就是多一次对外请求
                buildStep(
                    turn,
                    messages + UIMessage.user(compressPrompt),
                    includeTools = keepDefinitions,
                    allowSequentialVision = false,
                )
            }
            val params = step.params
            val provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            )
            val requestParams = params.copy(
                maxTokens = null,
                disableToolCalls = keepDefinitions,
                model = if (keepDefinitions) params.model else params.model.copy(tools = emptySet()),
            )
            return withRetry(
                policy = turn.settings.retryPolicy().copy(networkHandover = providerManager.networkHandover),
                onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
            ) {
                if (onProgress == null) {
                    provider.generateText(
                        providerSetting = turn.providerSetting,
                        messages = step.messages,
                        params = requestParams,
                    ).message
                } else {
                    // 后台压缩要看到真实进度：同一个请求按流式接收，字数和分块数都来自实际返回，
                    // 用量仍由 usageRecorder 按 COMPRESSION 记账，取消也照旧传到这条流上。
                    val text = StringBuilder()
                    var chunks = 0
                    var sawToolCall = false
                    provider.streamText(
                        providerSetting = turn.providerSetting,
                        messages = step.messages,
                        params = requestParams,
                    ).collect { chunk ->
                        chunks++
                        when (chunk) {
                            is me.rerere.ai.ui.StreamChunk.TextDelta -> text.append(chunk.text)
                            is me.rerere.ai.ui.StreamChunk.ToolCallStart,
                            is me.rerere.ai.ui.StreamChunk.ToolCallDelta,
                            is me.rerere.ai.ui.StreamChunk.ServerToolStart,
                            is me.rerere.ai.ui.StreamChunk.ServerToolEnd -> sawToolCall = true

                            else -> Unit
                        }
                        onProgress(CompressionChunkProgress(delta = if (chunk is me.rerere.ai.ui.StreamChunk.TextDelta) chunk.text else "", chunks = chunks))
                    }
                    // 流式路径看不到 generateText 那份完整 UIMessage：抢着调工具时正文不可信，按工具回应处理
                    if (sawToolCall || text.isBlank()) {
                        null
                    } else {
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text(text.toString())),
                        )
                    }
                }
            }
        }
        var fallbackUsed = false
        val response = try { generate(preserveDefinitions) }
        catch (error: Exception) {
            if (!preserveDefinitions || !compressionToolChoiceUnsupported(error)) throw error
            fallbackUsed = true
            generate(false)
        }
        // Some compatible relays ignore tool_choice. Preserve the previous working text-only route
        // rather than accept a tool response as a summary or disable automatic compression.
        val summary = response?.compressionSummaryOrNull()
            ?: (if (preserveDefinitions && !fallbackUsed) generate(false)?.compressionSummaryOrNull() else null)
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /** 一步对话请求实际发出的消息和参数 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        includeTools: Boolean = true,
        /**
         * 是否允许「逐张识别」预处理。
         *
         * 压缩/后台摘要这类请求必须传 false：它们已经有纯文本转写路径，再为看图多花一次对外视觉请求
         * 不值得，也不该在用户没选的时候偷偷发两遍图。
         */
        allowSequentialVision: Boolean = true,
    ): StepRequest {
        val assistant = turn.assistant
        // 压缩/摘要这类后台请求把工具摘掉，避免模型抢着调工具
        val tools = if (includeTools) turn.tools else emptyList()
        // 先按上下文窗口裁剪，再决定要识别哪些图：被裁掉的历史图片不该再花一次请求
        val limitedMessages = messages.limitContext(assistant.contextMessageLimit)
        // 逐张识别必须在 inputTransformers 之前：每张标记图先单独发一次无工具视觉请求，
        // 再把这些图替换成说明文本，主请求才真正不带图、也不会每一步重传历史图
        val preparedMessages = if (allowSequentialVision && includeTools && limitedMessages.hasSequentialVisionImages()) {
            sequentialImageDescriber.prepareMessages(turn, limitedMessages, processingStatus)
        } else {
            limitedMessages
        }
        val requestMessages = buildList {
            buildSystemMessage(turn, tools, messages)?.let { add(it) }
            // 知识库参考：不可信引用，只挂在最后一条用户消息尾部（不塞系统提示、不改历史）。
            // 放在 transforms 之前，动态时钟（TimeReminder 追加在请求尾部）仍然是最后一条。
            addAll(attachKnowledgeReference(preparedMessages, turn.knowledgeContext))
        }.transforms(
            transformers = if (allowSequentialVision) inputTransformers else compactionInputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        ).expandNotices()
        val systemChars = requestMessages.filter { it.role == MessageRole.SYSTEM }.sumOf { it.toText().length }
        PromptCompositionStore.recordTransformedSystem(turn.assistant.id.toString(), systemChars)
        return StepRequest(requestMessages, buildParams(turn, tools))
    }

    /**
     * 压缩请求的消息和参数。
     *
     * 历史照常裁剪、文档照常转文本，但不走 OCR：为了看图再发一次视觉请求，等于凭空多一次对外传输。
     * 转换后的历史交给 [CompactionRequestMessages] 压成带角色标记的纯文本，
     * assistant 图片、内联 base64、超长工具输出都不会出现在要发的消息里。
     */
    private suspend fun buildCompactionStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
        includeTools: Boolean,
    ): StepRequest {
        val tools = if (includeTools) turn.tools else emptyList()
        // 系统提示同样要走输入转换：占位符、提示词注入和工作区规则在压缩请求里也要生效
        val transformed = buildList {
            buildSystemMessage(turn, tools, messages)?.let { add(it) }
            addAll(messages.limitContext(turn.assistant.contextMessageLimit))
        }.transforms(
            transformers = compactionInputTransformers,
            context = context,
            model = turn.model,
            assistant = turn.assistant,
            settings = turn.settings,
            workspaceCwd = turn.workspaceCwd,
        )
        val system = transformed.firstOrNull { it.role == MessageRole.SYSTEM }
        return StepRequest(
            messages = CompactionRequestMessages.build(
                system = system,
                history = if (system == null) transformed else transformed.filterNot { it === system },
                compressPrompt = compressPrompt,
            ),
            params = buildParams(turn, tools),
        )
    }

    /** 系统提示词：助手提示词（或会话级提示词）+ 记忆 + 工具说明，正常对话和压缩共用 */
    private fun buildSystemMessage(
        turn: ChatTurnContext,
        tools: List<Tool>,
        messages: List<UIMessage>,
    ): UIMessage? {
        val assistant = turn.assistant
        val effective = if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank())
            turn.conversationSystemPrompt else assistant.systemPrompt
        val sections = buildList {
            add("主提示词" to effective)
            if (assistant.enableMemory) add("记忆" to turn.memoryPrompt)
            if (turn.learningPrompt.isNotBlank()) add("经验学习" to turn.learningPrompt)
            tools.forEach { tool ->
                val guidance = tool.systemPrompt(turn.model, messages)
                if (guidance.isNotBlank()) add("工具 · ${tool.name}" to guidance)
            }
            // 高效模式的小节：只讲怎么用已经给的入口，不改工具、不改权限（关闭时为空，不产生任何 diff）
            if (turn.executionGuidance.isNotBlank()) {
                add(ExecutionGuidance.SECTION_TITLE to turn.executionGuidance)
            }
            // 客户端兼容档的风格引导：独立小节、不替换主提示词、不改身份
            turn.clientProfile?.guidance?.takeIf { it.isNotBlank() }?.let { guidance ->
                add("客户端风格 · 工具用法" to guidance)
            }
        }
        PromptCompositionStore.record(assistant.id.toString(), turn.model.displayName,
            sections.map { PromptSection(it.first, it.second.length) })
        val system = sections.map { it.second }.filter { it.isNotBlank() }.joinToString("\n")
        return if (system.isNotBlank()) UIMessage.system(prompt = system).copy(isSynthetic = true) else null
    }

    private fun buildParams(turn: ChatTurnContext, tools: List<Tool>): TextGenerationParams {
        val params = TextGenerationParams(
            model = turn.model,
            temperature = turn.assistant.temperature,
            topP = turn.assistant.topP,
            maxTokens = turn.assistant.maxTokens,
            tools = tools,
            reasoningLevel = turn.assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(turn.assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(turn.assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        // 兼容档只补不覆盖：用户/助手/模型的自定义头与请求体优先，同名去重；
        // 原生档（NATIVE / 不支持而降级）时原样返回，一个字节都不改。
        return turn.clientProfile?.applyTo(params) ?: params
    }

    /**
     * 同模型多渠道（fork）：把某一步已经算好的参数改成发给[target]渠道的版本。
     *
     * 只替换和渠道强相关的字段：model、模型自己的自定义头/请求体，以及按目标 provider 重新解析的兼容档。
     * [params] 里其它一切都是 `prepareStep` 那一步算出来的（maxTokens、tools、disableToolCalls、
     * reasoningLevel、temperature、sessionId……），**必须原样保留**：重建一份新的会丢掉步级 token 预算
     * 和这一步的工具禁用，等于在切换渠道时偷偷改了请求语义。
     *
     * 兼容档要重算是因为它是按 provider 类型/模型族判定的，Claude 和 OpenAI 兼容不能共用一份；
     * 主渠道本来就走 [buildParams] 里解析好的那份，不受这里影响。
     */
    private fun rebuildParamsForChannel(
        turn: ChatTurnContext,
        params: TextGenerationParams,
        target: ChannelTarget,
    ): TextGenerationParams {
        val channelParams = params.copy(
            model = target.model,
            customHeaders = buildList {
                addAll(turn.assistant.customHeaders)
                addAll(target.model.customHeaders)
            },
            customBody = buildList {
                addAll(turn.assistant.customBodies)
                addAll(target.model.customBodies)
            },
        )
        return applyProfile(
            params = channelParams,
            setting = target.providerSetting,
            profile = turn.clientProfilePreference,
        ).applyTo(channelParams)
    }

    /**
     * 把知识库参考片段挂到用户请求尾部。
     *
     * 只追加到**最后一条 USER 消息**的文本 part 后面：消息条数与角色顺序不变（各协议都接受），
     * 前面的静态前缀也不受影响；同一轮每个工具步骤注入的都是同一份文本（[ChatTurnContext] 里固定）。
     */
    private fun attachKnowledgeReference(messages: List<UIMessage>, reference: String): List<UIMessage> {
        if (reference.isBlank()) return messages
        val index = messages.indexOfLast { it.role == MessageRole.USER }
        if (index == -1) return messages
        val target = messages[index]
        return messages.toMutableList().also { list ->
            list[index] = target.copy(parts = target.parts + UIMessagePart.Text("\n\n$reference"))
        }
    }
}

/**
 * 压缩摘要流式生成中的一个分块。
 *
 * [delta] 是这块里的正文（用量、结束这类事件为空），[chunks] 是本次请求累计收到的分块数。
 * 字数由调用方按 Unicode 字符累计，这里不重复实现。
 */
data class CompressionChunkProgress(
    val delta: String,
    val chunks: Int,
)

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

/**
 * 主对话和子代理共用的唯一重试策略构造函数。
 *
 * 关了自动重试就是 [RetryPolicy.NONE]；开着时次数夹在 0~100（0 也等于不重试），
 * 等待时长按设置里的固定秒数或随机区间来，不再走 ai 模块默认的指数退避——
 * 设置页写"每次 30~60 秒随机"，实际等待就必须真的落在 30~60 秒里。
 */
internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) {
        networkSetting.toRetryPolicy()
    } else {
        RetryPolicy.NONE
    }

/**
 * 把用户设置翻译成请求层的重试策略。
 *
 * 三件事必须从这里一起出去，否则界面说一套、请求层做一套：
 * - 次数与等待：保留原有 `maxRetries` / `retryDelay*`；
 * - 范围：[RetryScope] 同时喂给 `retryOn`（要不要再发）和 `RetryBudget`（按哪一类记账）；
 * - 无限：`retryUnlimited` 让预算不再按次数封顶，但用户取消、关闭总开关仍然立刻生效。
 */
internal fun NetworkSetting.toRetryPolicy(): RetryPolicy {
    val delay = normalizedRetryDelay()
    val scope = retryScope()
    return RetryPolicy(
        maxRetries = maxRetries.coerceIn(0, 100),
        retryOn = { error -> scope.allows(error) },
        scope = scope,
        unlimitedRetries = retryUnlimited,
        // 实时总开关：关掉之后已经排上的退避重试立刻放弃，不等这一轮的设置快照过期
        retryGate = RetryGate { RetrySwitch.isEnabled() },
        delayMode = when (delay.mode) {
            RetryDelayMode.FIXED -> RetryDelay.Fixed(delay.fixedSeconds.seconds)
            RetryDelayMode.RANGE -> RetryDelay.RandomRange(
                min = delay.minSeconds.seconds,
                max = delay.maxSeconds.seconds,
            )
        },
    )
}

/**
 * 给一轮对话挂一份共享的重试预算。
 *
 * AgentLoop 的每个模型步骤都会调一次 `withRetry(request.retry)`；不共享时"每步 N 次"会随工具步数
 * 放大成 N × 步数。用户设置的次数应该是**整轮**的次数：这里建一份，整轮所有步骤都从同一份里扣。
 *
 * 自动恢复轮已经在 `ChatAutoRecovery.settingsForTurnRecovery` 里把内层次数收到 0，
 * 这里仍会建一份 0 次预算，效果就是每步只发一次；整轮次数留给外层自动恢复，两层不叠加。
 */
internal fun RetryPolicy.withTurnRetryBudget(): RetryPolicy =
    copy(sharedBudget = RetryBudget(maxRetries, scope, unlimitedRetries))

/** Only an explicit tool-choice compatibility error enables the single text-only fallback. */internal fun compressionToolChoiceUnsupported(error: Throwable): Boolean {
    if (error !is me.rerere.ai.util.HttpException) return false
    val text = error.message.orEmpty().lowercase()
    if (listOf("api key", "unauthorized", "permission", "rate limit").any { it in text }) return false
    return "tool_choice" in text && listOf("unsupported", "not supported", "unknown", "invalid", "unrecognized").any { it in text }
}
