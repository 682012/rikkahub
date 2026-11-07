package me.rerere.rikkahub.data.ai.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.agent.RetryErrorCategory
import me.rerere.ai.agent.retryCategoryOf
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.NetworkRouteChangedException
import me.rerere.ai.provider.NetworkUnavailableException
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.NetworkChangedException
import me.rerere.rikkahub.data.datastore.ChannelFailoverSettings
import me.rerere.rikkahub.data.datastore.ModelChannelSlot
import me.rerere.rikkahub.data.datastore.findProvider

/**
 * 主聊天的「同模型多渠道优先级 + 故障切换」执行层（fork）。
 *
 * 设计边界（改动前请先读这段）：
 *
 * 1. **顺序完全由用户决定**：启用后只按「模型设置 → 渠道优先级」里用户排好的白名单从上到下调用，
 *    排第一的就是首选。会话当前选中哪个渠道**不参与排序**，只用来决定这是哪个模型组的请求。
 *    这样用户排的顺序不会被"当前选中"悄悄顶掉。
 * 2. **只认用户显式白名单**：候选渠道只来自那条列表。不从别的助手、全局默认模型或任何"猜"出来的
 *    渠道里取一个来兜底。
 * 3. **同一个模型才算渠道**：白名单槽位的 modelId 必须和模型组的 modelId 一致，provider 里必须有这个
 *    模型。对不上就跳过，绝不拿同 provider 里的另一个模型顶替（那等于偷换模型调用）。
 * 4. **同协议族才互换**：跨协议的 messages 结构、工具声明、纯文本转换都不一样，换过去无法保证请求正确。
 *    所以只在同一协议族（例如都是 OpenAI 兼容接口）内部切换，跨族槽位直接跳过并在设置页标出来。
 * 5. **默认关闭**：开关为 false 时 [resolveChannelPlan] 返回空列表，调用方连包装都不加，
 *    请求路径与旧版本一致。
 * 6. **切换发生在 provider 请求层**：一次 `streamText` / `generateText` 之内换渠道，不重跑工具步骤，
 *    因此已经执行成功的工具结果原样保留在会话里（AgentLoop 的消息不会被回退）。
 * 7. **有界不循环**：单次调用最多把每个渠道各试一次；下标只向后走（`index + 1`），一圈走完就抛最后一次
 *    的真实错误，一个渠道成功后就固定下来，后续步骤不再回到前面失败过的渠道。
 * 8. **不该换的一律不换**：[channelFailoverSwitchable] 只放行「这个渠道不行、换一个可能行」的错误。
 *    用户取消、网络整体不可用/换网、web 会话失效、请求格式错误都直接抛出，交给已有的重试与自动恢复。
 * 9. **空响应要换**：流式请求正常结束但一个字都没出（空 stream、只有 Finish、只有 usage 元数据），
 *    以及非流式结果里没有任何内容，都算这个渠道没干活，换下一个。
 * 10. **半截输出不重来**：一旦已经往下游发过任何模型产物（正文、思考、工具调用草稿、ServerToolStart、
 *    图片等），失败就直接抛——换渠道重发会把两段内容接在一起。这种失败交给 ChatAutoRecovery 的
 *     检查点续跑（它保留已完成的工具与正文）。只有纯元数据（usage / finish 标记）会被短暂缓冲，
 *     没出内容就整段丢弃，避免两个渠道的用量混在一起。
 * 11. **只 catch 普通异常**：`Error`（OOM、StackOverflow 等致命错误）不参与切换判定，原样向上抛；
 *     `CancellationException` 永远传播，不会因为取消去调下一个渠道。
 * 12. **用量记在真实渠道上**：每次尝试都用该渠道的 providerSetting + model 去记 usage，所以统计里看到的
 *     是实际出话的 provider 实例，不是会话里选的那个。
 */

/** 一个可调用的渠道：某个 provider 实例里的某个模型。 */
data class ChannelTarget(
    /** provider 实例 UUID 字符串（设置里排序用的定位符） */
    val providerId: String,
    /** 实际发请求用的 provider 设置（已应用模型自己的 providerOverwrite） */
    val providerSetting: ProviderSetting,
    val model: Model,
) {
    /** 协议族：只有同族渠道之间才允许切换，见 [channelProtocolFamily]。 */
    val protocolFamily: String get() = channelProtocolFamily(providerSetting)

    /** 设置里给人看的协议族名字。 */
    val protocolFamilyLabel: String get() = channelProtocolFamilyLabel(protocolFamily)

    /** 是不是同一个渠道（provider 实例 + 模型）。 */
    fun sameChannelAs(other: ChannelTarget): Boolean =
        providerSetting.id == other.providerSetting.id && model.id == other.model.id
}

/**
 * 渠道的协议族。跨族之间无法保证 messages / transforms / 工具声明一致，因此不做切换。
 *
 * 这里的族按 provider 实现分（同一个 provider 类 = 同一套线协议实现），不按模型厂家分：
 * 同一个模型挂在两个 OpenAI 兼容中转上是最常见的用法，也正是唯一保证得住的场景。
 */
fun channelProtocolFamily(setting: ProviderSetting): String = when (setting) {
    is ProviderSetting.OpenAI -> FAMILY_OPENAI
    is ProviderSetting.Claude -> FAMILY_CLAUDE
    is ProviderSetting.Google -> FAMILY_GOOGLE
    is ProviderSetting.ChatGPT -> FAMILY_CHATGPT
    is ProviderSetting.Grok -> FAMILY_GROK
    is ProviderSetting.ClaudeWeb -> FAMILY_CLAUDE_WEB
    is ProviderSetting.GeminiWeb -> FAMILY_GEMINI_WEB
}

/** 协议族的中文名，设置页直接显示。 */
fun channelProtocolFamilyLabel(family: String): String = when (family) {
    FAMILY_OPENAI -> "OpenAI 兼容接口"
    FAMILY_CLAUDE -> "Claude 接口"
    FAMILY_GOOGLE -> "Google Gemini 接口"
    FAMILY_CHATGPT -> "ChatGPT 账号"
    FAMILY_GROK -> "Grok 账号"
    FAMILY_CLAUDE_WEB -> "Claude 网页会话"
    FAMILY_GEMINI_WEB -> "Gemini 网页会话"
    else -> "未知协议"
}

const val FAMILY_OPENAI = "openai"
const val FAMILY_CLAUDE = "claude"
const val FAMILY_GOOGLE = "google"
const val FAMILY_CHATGPT = "chatgpt"
const val FAMILY_GROK = "grok"
const val FAMILY_CLAUDE_WEB = "claude-web"
const val FAMILY_GEMINI_WEB = "gemini-web"

/**
 * 解析本轮允许尝试的**有序**渠道列表。
 *
 * 顺序**就是**用户在设置里排的白名单顺序，索引 0 = 首选。会话当前选中的渠道不参与排序，
 * 只用来确定「这是哪个模型组的请求」（组身份是 modelId）。
 *
 * 返回空列表的情况（调用方据此走老路径，不加任何包装）：
 * - 开关关闭；
 * - 这个模型没有配置过渠道组；
 * - 会话模型属于 web 账号（登录态会话不是可互换的 API 渠道）；
 * 已配置的白名单全部失效时明确报错，不能悄悄回到白名单外的渠道。
 * 只有一个可用渠道仍按白名单调用。
 *
 * 解析不到 / 失效 / 跨协议族的槽位会被跳过：provider 被删、模型被删、provider 被禁用、模型不是 CHAT、
 * 槽位 modelId 与模型组不一致。**当前渠道被禁用不会让整个计划失效**——只要白名单里还有可用渠道，
 * 就照样按列表调用，不会因为"当前渠道不可用"而把用户排好的 fallback 一起丢掉。
 */
fun resolveChannelPlan(
    providers: List<ProviderSetting>,
    model: Model,
    failover: ChannelFailoverSettings,
): List<ChannelTarget> {
    // 自建排序池是独立模式：开了就只按用户排的池子走，分类组（routes）不参与
    if (failover.customPoolEnabled) return resolveCustomPoolPlan(providers, model, failover)
    if (!failover.enabled) return emptyList()
    val route = failover.routeFor(model.modelId) ?: return emptyList()

    // 会话模型属于 web 账号：登录态会话有会话/凭据绑定，不是可互换的 API 渠道，一律不切换
    val currentEntry = model.findProvider(providers, checkOverwrite = false)
    if (currentEntry is ProviderSetting.WebAccount) return emptyList()

    val listed = route.channels.mapNotNull { slot ->
        // 槽位必须指向和模型组同一个模型，防止同 provider 里的另一个模型被顶替进来
        if (!slot.modelId.trim().equals(route.modelId.trim(), ignoreCase = true)) return@mapNotNull null
        channelTarget(providers, slot.providerId, slot.modelId)
    }.distinctBy { it.providerSetting.id to it.model.id }
    check(listed.isNotEmpty()) { "该模型的渠道优先级列表中没有可用渠道，请检查提供商开关和模型配置。" }

    // prepareStep 按当前 provider 转换历史和工具；必须与它同族，不能按候选首项偷偷换协议。
    val family = currentEntry?.let { channelProtocolFamily(model.findProvider(providers) ?: it) }
        ?: listed.first().protocolFamily
    val ordered = listed.filter { it.protocolFamily == family }
    check(ordered.isNotEmpty()) { "该模型没有与当前会话协议相同的可用渠道，请调整渠道优先级。" }
    // 配置了白名单就始终用它，只有一个渠道也不回退到白名单外的原渠道。
    return ordered
}

/**
 * 自建排序池（独立模式）的有序渠道计划。
 *
 * 与分类组模式的区别只有两条：池的成员由用户从**所有**已配置聊天模型里自己挑，不限 modelId；
 * 池只对用户排的顺序负责，会话当前选中的模型不参与排序。
 *
 * 边界（和分类组一致，都是为了让请求真的能发对）：
 * - 仍然只调用池里的渠道，绝不从池外借一个兜底；
 * - 池为空、池内全部失效、池首项与当前会话协议族不一致时**明确抛错**，不静默丢首项换下一个；
 * - 历史/工具是按当前会话模型所属协议族准备的（见文件头第 4 条），所以池内跨协议族的成员不参与切换。
 */
fun resolveCustomPoolPlan(
    providers: List<ProviderSetting>,
    model: Model,
    failover: ChannelFailoverSettings,
): List<ChannelTarget> {
    if (!failover.customPoolEnabled) return emptyList()
    val pool = failover.orderedPool.map { it.normalized() }.filter { it.isIdentifiable }
    check(pool.isNotEmpty()) {
        "自建排序池为空：请先在「渠道优先级 → 自建排序池」里加入至少一个聊天模型，或关闭自建排序池。"
    }

    // 会话模型属于 web 账号：登录态会话不是可互换的 API 渠道，一律不切换
    val currentEntry = model.findProvider(providers, checkOverwrite = false)
    if (currentEntry is ProviderSetting.WebAccount) return emptyList()

    val resolved = pool
        .mapNotNull { slot -> channelTargetOfSlot(providers, slot) }
        .distinctBy { it.providerSetting.id to it.model.id }
    check(resolved.isNotEmpty()) {
        "自建排序池里的渠道全部失效（提供商或模型已被删除/禁用），请重新配置；不会回退到池外的渠道。"
    }

    val sessionEntry = model.findProvider(providers) ?: currentEntry
    val family = sessionEntry?.let { channelProtocolFamily(it) } ?: resolved.first().protocolFamily
    val head = resolved.first()
    check(head.protocolFamily == family) {
        "自建排序池的首选项（${head.providerSetting.name} / ${head.model.displayName.ifBlank { head.model.modelId }}）" +
            "是${head.protocolFamilyLabel}，与当前会话模型的" +
            "${channelProtocolFamilyLabel(family)}不互通，不能安全发送这次请求；" +
            "请把同协议的模型排到第一，或改用同协议的会话模型。"
    }
    return resolved.filter { it.protocolFamily == family }
}

/**
 * 把池里的一个槽解析成可调用的渠道：优先按模型实例 UUID 精确定位（[ModelChannelSlot.modelUuid]），
 * 没有 UUID 时退回 provider 实例 + modelId。解析不到返回 null（失效槽位跳过，不猜别的模型）。
 */
fun channelTargetOfSlot(
    providers: List<ProviderSetting>,
    slot: ModelChannelSlot,
): ChannelTarget? {
    val normalized = slot.normalized()
    val uuid = normalized.modelUuid
    if (uuid.isNotBlank()) {
        val candidates = providers.filter { entry ->
            normalized.providerId.isBlank() ||
                entry.id.toString().equals(normalized.providerId, ignoreCase = true)
        }
        candidates.forEach { entry ->
            val model = entry.models.firstOrNull {
                it.id.toString().equals(uuid, ignoreCase = true)
            } ?: return@forEach
            channelTargetOf(providers, entry, model)?.let { return it }
        }
        if (normalized.providerId.isBlank() && normalized.modelId.isBlank()) return null
    }
    if (normalized.providerId.isBlank() || normalized.modelId.isBlank()) return null
    return channelTarget(providers, normalized.providerId, normalized.modelId)
}

/**
 * 自建排序池的候选：所有启用提供商里的全部聊天模型，**不按 modelId 分组**。
 * 用户自己挑成员、自己排序，这里只负责把可调用的模型列全（含协议族，界面要标出来）。
 */
fun poolCandidates(providers: List<ProviderSetting>): List<ChannelTarget> = buildList {
    providers
        .filter { it.enabled && it !is ProviderSetting.WebAccount }
        .forEach { entry ->
            entry.models
                .filter { it.type == ModelType.CHAT && it.modelId.isNotBlank() }
                .forEach { model ->
                    channelTargetOf(providers, entry, model)?.let { target ->
                        if (none { existing -> existing.sameChannelAs(target) }) add(target)
                    }
                }
        }
}

/**
 * 把设置里的一个渠道槽解析成可调用的渠道；解析不到时返回 null（失效槽位跳过）。
 *
 * 只接受：provider 存在且启用、模型在它的模型列表里、模型是 CHAT、不是 web 账号。
 */
fun channelTarget(
    providers: List<ProviderSetting>,
    providerId: String,
    modelId: String,
): ChannelTarget? {
    if (providerId.isBlank() || modelId.isBlank()) return null
    val entry = providers.firstOrNull { it.id.toString().equals(providerId.trim(), ignoreCase = true) }
        ?: return null
    val model = entry.models.firstOrNull { it.modelId.trim().equals(modelId.trim(), ignoreCase = true) }
        ?: return null
    return channelTargetOf(providers, entry, model)
}

/** 某个 provider 条目里的某个模型能否作为渠道；不能时返回 null。 */
private fun channelTargetOf(
    providers: List<ProviderSetting>,
    entry: ProviderSetting,
    model: Model,
): ChannelTarget? {
    if (!entry.enabled || entry is ProviderSetting.WebAccount) return null
    if (model.type != ModelType.CHAT) return null
    // 模型自己带 providerOverwrite 时，实际地址/凭据以覆盖配置为准，和主路径同一个口径
    val effective = model.findProvider(providers) ?: return null
    if (!effective.enabled || effective is ProviderSetting.WebAccount) return null
    return ChannelTarget(providerId = entry.id.toString(), providerSetting = effective, model = model)
}

/** 设置页里可以加进某个模型组的候选渠道（同一个模型 + 同一个协议族）。 */
data class ChannelCandidateGroup(
    val modelId: String,
    /** 协议族 id，见 [channelProtocolFamily] */
    val protocolFamily: String,
    val channels: List<ChannelTarget>,
)

/**
 * 把当前所有 provider 的 CHAT 模型按「modelId + 协议族」分组，给设置页的"新建/添加渠道"用。
 *
 * 按协议族分开是必须的：跨协议的渠道不能互换（见文件头第 4 条），混在一个组里只会让用户白排。
 */
fun channelCandidateGroups(providers: List<ProviderSetting>): List<ChannelCandidateGroup> {
    val byGroup = LinkedHashMap<Pair<String, String>, MutableList<ChannelTarget>>()
    providers
        .filter { it.enabled && it !is ProviderSetting.WebAccount }
        .forEach { entry ->
            entry.models
                .filter { it.type == ModelType.CHAT && it.modelId.isNotBlank() }
                .forEach { model ->
                    val target = channelTargetOf(providers, entry, model)
                    if (target != null) {
                        val key = model.modelId.trim().lowercase() to target.protocolFamily
                        byGroup.getOrPut(key) { mutableListOf() }.add(target)
                    }
                }
        }
    return byGroup.map { (key, channels) ->
        ChannelCandidateGroup(
            modelId = channels.first().model.modelId.trim(),
            protocolFamily = key.second,
            channels = channels.toList(),
        )
    }
}

/** 某个模型组当前可用的候选渠道（设置页画顺序、标失效用）；[protocolFamily] 为空时取第一个组。 */
fun channelCandidatesFor(
    providers: List<ProviderSetting>,
    modelId: String,
    protocolFamily: String = "",
): List<ChannelTarget> {
    val groups = channelCandidateGroups(providers)
        .filter { it.modelId.equals(modelId.trim(), ignoreCase = true) }
    if (groups.isEmpty()) return emptyList()
    val group = if (protocolFamily.isBlank()) {
        groups.first()
    } else {
        groups.firstOrNull { it.protocolFamily == protocolFamily } ?: return emptyList()
    }
    return group.channels
}

/**
 * 模型组已配置渠道的协议族：取第一个能解析出来的槽位，用于加渠道时过滤候选
 * （跨族槽位不能互换，设置页不该把它们列出来）。
 */
fun channelRouteFamily(
    providers: List<ProviderSetting>,
    modelId: String,
    slots: List<ModelChannelSlot>,
): String {
    slots.firstNotNullOfOrNull { slot ->
        channelTarget(providers, slot.providerId, slot.modelId)?.protocolFamily
    }?.let { return it }
    return channelCandidatesFor(providers, modelId).firstOrNull()?.protocolFamily.orEmpty()
}

/** 这个错误要不要换下一个渠道。判定只在这里，别在别处再写一份 if/else。 */
fun channelFailoverSwitchable(error: Throwable): Boolean {
    // 用户取消不是渠道故障，绝不能因为取消去调下一个渠道
    if (error is CancellationException) return false
    // 整张网不通 / 换网换路由：换渠道还是同一个网络，重发只会在连接超时上白等
    if (error is NetworkUnavailableException ||
        error is NetworkChangedException ||
        error is NetworkRouteChangedException
    ) {
        return false
    }
    // web 会话的登录态/协议问题：另一个渠道没有同样的会话，换了也不会好
    if (error is WebSessionException || error is WebProtocolException) return false
    return when (retryCategoryOf(error)) {
        // 渠道自己的问题：鉴权、额度、服务端 5xx/429、连接失败、空响应/半截流
        RetryErrorCategory.AUTH,
        RetryErrorCategory.QUOTA,
        RetryErrorCategory.SERVER,
        RetryErrorCategory.NETWORK,
        RetryErrorCategory.EMPTY_RESPONSE,
        -> true
        // 请求本身的格式/参数问题：同一个请求发到哪个渠道都一样，换渠道只会多打几次
        RetryErrorCategory.CUSTOM_MATCH -> true
        RetryErrorCategory.REQUEST_FORMAT,
        RetryErrorCategory.UNKNOWN,
        -> false
    }
}

/** 这个流分块是不是模型产物。只有纯元数据（用量、结束标记）不算法产物。 */
private fun StreamChunk.carriesModelOutput(): Boolean = when (this) {
    is StreamChunk.Usage, is StreamChunk.Finish -> false
    else -> true
}

/** 非流式结果里有没有真的内容（纯 usage / 空消息算没干活）。 */
private fun TextGenerationResult.carriesModelOutput(): Boolean = message.parts.any { part ->
    when (part) {
        is UIMessagePart.Text -> part.text.isNotBlank()
        is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
        else -> true
    }
}

/**
 * 把一次 provider 调用变成「按用户排的顺序逐个渠道试」的调用。
 *
 * 只有两个抽象是必需的：[providerFor]（按渠道拿 provider 实例，通常是
 * `providerManager.getProviderByType`，外面再包 UsageRecorder）和 [rebuildParams]
 * （按目标渠道重建 model / 自定义头 / 兼容档，见 ChatRequestFactory；**必须**在原有参数上改，
 * 不能丢 prepareStep 算出来的 maxTokens / tools / disableToolCalls 等步级参数）。
 *
 * 状态只有两个：当前渠道下标和本轮已切换次数，都在锁里。AgentLoop 的模型请求是顺序的，
 * 锁只是让状态在并发工具批次下的读也安全。
 */
class ChannelFailoverProvider(
    channels: List<ChannelTarget>,
    private val providerFor: (ChannelTarget) -> Provider<ProviderSetting>,
    private val rebuildParams: (TextGenerationParams, ChannelTarget) -> TextGenerationParams,
    private val switchable: (Throwable) -> Boolean = ::channelFailoverSwitchable,
    /** 切换时的回调：只用于界面提示/日志，不能在这里发请求或改会话；它抛异常也不会影响这次请求。 */
    private val onSwitch: (from: ChannelTarget, to: ChannelTarget, error: Throwable) -> Unit = { _, _, _ -> },
    /** 整轮最多切换几次：默认每个渠道最多成为当前渠道一次。 */
    private val maxSwitchesPerTurn: Int = (channels.size - 1).coerceAtLeast(0),
) : Provider<ProviderSetting> {
    private val channels: List<ChannelTarget> = channels.toList()
    private val lock = Any()
    private var cursor = 0
    private var turnSwitches = 0

    init {
        // 允许只有一个渠道：会话当前渠道被删/被禁用时，白名单里唯一剩下的那个仍然要能用
        require(this.channels.isNotEmpty()) { "Channel failover needs at least one channel" }
    }

    /** 当前固定使用的渠道（成功过/正在试的那个）。 */
    fun currentChannel(): ChannelTarget = synchronized(lock) { channels[cursor] }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
        providerFor(channels.first()).listModels(providerSetting)

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        var index = synchronized(lock) { cursor }
        var attempts = 0
        var lastFailure: Throwable? = null
        while (attempts < channels.size) {
            attempts++
            val target = channels[index]
            // 纯元数据先攒着：这个渠道要是没出内容就整段丢掉，别把两个渠道的用量接到一起
            val buffered = mutableListOf<StreamChunk>()
            var sawOutput = false
            try {
                providerFor(target)
                    .streamText(target.providerSetting, messages, paramsFor(params, target))
                    .collect { chunk ->
                        if (chunk.carriesModelOutput()) {
                            // 先标记，再给下游发事件：下游处理异常绝不能被当成渠道错误重发。
                            sawOutput = true
                            if (buffered.isNotEmpty()) {
                                buffered.forEach { emit(it) }
                                buffered.clear()
                            }
                            emit(chunk)
                        } else if (sawOutput) {
                            emit(chunk)
                        } else {
                            buffered += chunk
                        }
                    }
                if (sawOutput) {
                    // 正常结束：这个渠道就是本轮固定渠道
                    synchronized(lock) { cursor = index }
                    return@flow
                }
                // 空 stream / 只有 Finish / 只有 usage：这个渠道没干活，按空响应换下一个
                lastFailure = StreamTruncatedException(
                    "empty stream response from ${target.providerSetting.name}/${target.model.modelId}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 已经往下游发过模型产物：换渠道重发会把两段内容拼在一起，宁可直接报错，
                // 交给 ChatAutoRecovery 的检查点续跑（它保留已完成工具与已出正文）。
                if (sawOutput) throw e
                lastFailure = e
            }
            val failure = lastFailure ?: continue
            if (!switchable(failure)) throw failure
            val next = nextIndexAfter(index) ?: throw failure
            notifySwitch(target, channels[next], failure)
            index = next
        }
        // 兜底：每个渠道都试过一遍仍没成功，抛出最后一次的真实错误（上层按它做分类/自动恢复）
        throw lastFailure ?: IllegalStateException("Channel failover exhausted without a result")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        var index = synchronized(lock) { cursor }
        var attempts = 0
        var lastFailure: Throwable? = null
        while (attempts < channels.size) {
            attempts++
            val target = channels[index]
            try {
                val result = providerFor(target)
                    .generateText(target.providerSetting, messages, paramsFor(params, target))
                if (result.carriesModelOutput()) {
                    synchronized(lock) { cursor = index }
                    return result
                }
                // 空 nonstream 结果同样算这个渠道没干活
                lastFailure = StreamTruncatedException(
                    "empty response from ${target.providerSetting.name}/${target.model.modelId}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
            val failure = lastFailure ?: continue
            if (!switchable(failure)) throw failure
            val next = nextIndexAfter(index) ?: throw failure
            notifySwitch(target, channels[next], failure)
            index = next
        }
        throw lastFailure ?: IllegalStateException("Channel failover exhausted without a result")
    }

    /**
     * 只有目标渠道不是参数里原本的模型时才重建；主渠道原样发，
     * 保证"没切换的时候请求体和以前完全一样"。
     */
    private fun paramsFor(params: TextGenerationParams, target: ChannelTarget): TextGenerationParams =
        if (target.model.id == params.model.id) params else rebuildParams(params, target)

    /** 切到下一个渠道；没有额度、已经在最后一个或只有自己一个渠道时返回 null（调用方按原错误抛出）。 */
    private fun nextIndexAfter(index: Int): Int? = synchronized(lock) {
        if (turnSwitches >= maxSwitchesPerTurn) return null
        // 只向后走，绝不绕回前面的渠道
        val next = index + 1
        if (next > channels.lastIndex) return null
        turnSwitches++
        cursor = next
        next
    }

    /** 切换提示只是界面/日志：回调自己出错也不能影响这次请求，更不能因此重复发一遍。 */
    private fun notifySwitch(from: ChannelTarget, to: ChannelTarget, error: Throwable) {
        try {
            onSwitch(from, to, error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 忽略：提示失败不影响已经做出的切换决定
        }
    }
}
