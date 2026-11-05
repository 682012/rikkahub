package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryDelay
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.canDisableToolCallsWithDefinitions
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.expandNotices
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
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
)

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
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
        inputTransformers.filterNot { it === OcrTransformer }

    // 多图逐张识别：只在用户显式标记图片时才发请求，构造函数和 DI 不动，缓存按需创建
    private val sequentialImageDescriber: SequentialImageDescriber by lazy {
        SequentialImageDescriber(context, providerManager, usageRecorder)
    }

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(
        settings: Settings,
        conversation: Conversation,
        modelOverride: Model? = null,
    ): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准；modelOverride 只给压缩这类旁路请求单独指定模型
        val assistant = settings.getAssistantOf(conversation)
        val model = modelOverride ?: settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            // 压缩这类旁路请求只需要文本，指定了单独模型时不带工具，
            // 避免不支持工具调用的上游直接拒绝请求
            tools = if (modelOverride != null || (provider is ProviderSetting.WebAccount && !provider.toolBridgeEnabled)) {
                emptyList()
            } else {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                    conversationId = conversation.id,
                ).let { tools ->
                    if (provider is ProviderSetting.WebAccount) tools.map { it.copy(needsApproval = { true }) }
                    else tools
                }
            },
        )
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
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy().copy(networkHandover = providerManager.networkHandover),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(turn, messages, processingStatus)
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun pendingParts() = takePendingParts()
        }
        return request to hooks
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
    ): String {
        // Native OpenAI/Claude can retain declarations but force tool_choice=none. Other protocols
        // still drop definitions. Never trade summary correctness for cache hits.
        val preserveDefinitions = turn.providerSetting.canDisableToolCallsWithDefinitions
        suspend fun generate(keepDefinitions: Boolean): UIMessage {
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
            return withRetry(
                policy = turn.settings.retryPolicy().copy(networkHandover = providerManager.networkHandover),
                onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
            ) {
                usageRecorder.wrap(
                    providerManager.getProviderByType(turn.providerSetting),
                    UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
                ).generateText(
                    providerSetting = turn.providerSetting,
                    messages = step.messages,
                    params = params.copy(
                        maxTokens = null,
                        disableToolCalls = keepDefinitions,
                        model = if (keepDefinitions) params.model else params.model.copy(tools = emptySet()),
                    ),
                ).message
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
        val summary = response.compressionSummaryOrNull()
            ?: (if (preserveDefinitions && !fallbackUsed) generate(false).compressionSummaryOrNull() else null)
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
            addAll(preparedMessages)
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        ).expandNotices()
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
        val system = buildString {
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                    turn.conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            // 记忆
            if (assistant.enableMemory) {
                appendLine()
                append(buildMemoryPrompt(turn.memories))
            }
            // 工具prompt
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(turn.model, messages))
            }
        }
        return if (system.isNotBlank()) UIMessage.system(prompt = system).copy(isSynthetic = true) else null
    }

    private fun buildParams(turn: ChatTurnContext, tools: List<Tool>) = TextGenerationParams(
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
}

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

internal fun NetworkSetting.toRetryPolicy(): RetryPolicy {
    val delay = normalizedRetryDelay()
    return RetryPolicy(
        maxRetries = maxRetries.coerceIn(0, 100),
        delayMode = when (delay.mode) {
            RetryDelayMode.FIXED -> RetryDelay.Fixed(delay.fixedSeconds.seconds)
            RetryDelayMode.RANGE -> RetryDelay.RandomRange(
                min = delay.minSeconds.seconds,
                max = delay.maxSeconds.seconds,
            )
        },
    )
}

/** Only an explicit tool-choice compatibility error enables the single text-only fallback. */
internal fun compressionToolChoiceUnsupported(error: Throwable): Boolean {
    if (error !is me.rerere.ai.util.HttpException) return false
    val text = error.message.orEmpty().lowercase()
    if (listOf("api key", "unauthorized", "permission", "rate limit").any { it in text }) return false
    return "tool_choice" in text && listOf("unsupported", "not supported", "unknown", "invalid", "unrecognized").any { it in text }
}
