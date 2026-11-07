package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.agent.CustomRetryRule
import me.rerere.ai.agent.CustomRetryRules
import me.rerere.ai.agent.RetryErrorCategory
import me.rerere.ai.agent.RetryScope
import me.rerere.ai.agent.RetryScopeMode
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.controller.TtsPlaybackSettings
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val ttsPlaybackSettings: TtsPlaybackSettings = TtsPlaybackSettings(),
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
    // fork: 工作区与子代理的运行时配置，见 ForkSettings.kt
    val workspaceRuntime: WorkspaceRuntimeSettings = WorkspaceRuntimeSettings(),
    val subagent: SubagentSettings = SubagentSettings(),
    val autoCompress: AutoCompressSettings = AutoCompressSettings(),
    // fork: 同模型多渠道优先级与故障切换，见 ChannelFailoverSettings.kt；默认关闭
    val channelFailover: ChannelFailoverSettings = ChannelFailoverSettings(),
    val backgroundToolSettings: BackgroundToolSettings = BackgroundToolSettings(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

/** 重试等待的取法：固定秒数，或每次在区间里随机。 */
@Serializable
enum class RetryDelayMode {
    @SerialName("fixed")
    FIXED,

    @SerialName("range")
    RANGE,
}

/**
 * 归一化之后的重试等待配置：秒数一定落在 [NetworkSetting.MIN_RETRY_DELAY_SECONDS]~[NetworkSetting.MAX_RETRY_DELAY_SECONDS]，
 * 且 [minSeconds] <= [maxSeconds]。界面和重试策略都读这个，不直接读原始字段。
 */
data class RetryDelayConfig(
    val mode: RetryDelayMode,
    val fixedSeconds: Int,
    val minSeconds: Int,
    val maxSeconds: Int,
)

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
    /** 自动重试的最大次数，0 表示不重试（总开关打开也无效）。 */
    val maxRetries: Int = 3,
    /**
     * 重试前怎么等。默认固定 60 秒：旧版本只写了"单次等待最长 60 秒"，
     * 实际是 1s、2s、4s……的指数退避，改成本字段之后等的时长由用户决定。
     */
    val retryDelayMode: RetryDelayMode = RetryDelayMode.FIXED,
    /** [RetryDelayMode.FIXED] 时每次重试前固定的秒数。 */
    val retryDelaySeconds: Int = DEFAULT_RETRY_DELAY_SECONDS,
    /** [RetryDelayMode.RANGE] 时区间下界（含）。 */
    val retryDelayMinSeconds: Int = DEFAULT_RETRY_DELAY_MIN_SECONDS,
    /** [RetryDelayMode.RANGE] 时区间上界（含）。 */
    val retryDelayMaxSeconds: Int = DEFAULT_RETRY_DELAY_MAX_SECONDS,
    /**
     * 重试范围模式，默认 [RetryScopeMode.CLASSIFIED]（老版本行为：只试网络、408/429/5xx、半截流）。
     * 老配置里没有这个字段，解码后拿到的就是默认值，行为不变。
     */
    val retryScopeMode: RetryScopeMode = RetryScopeMode.CLASSIFIED,
    /**
     * [RetryScopeMode.CUSTOM] 下**追加**勾选的类别；空集合等于老分类。
     * 勾上的类别不会替换默认分类，所以界面写"额外重试"是字面意思。
     */
    val retryExtraCategories: Set<RetryErrorCategory> = emptySet(),
    /** 一直重试到用户停止（取消生成 / 关闭总开关）。默认 false，仍然受 [maxRetries] 限制。 */
    val retryUnlimited: Boolean = false,
    /**
     * m3「异常停止自动续接」总开关，默认关闭。
     *
     * 打开后，一轮以 [me.rerere.ai.agent.AgentFinishReason.StepLimitReached] 结束（或 r21 已标记为可恢复的
     * 传输失败）时，从原会话已有的检查点继续写同一条回复：不插入新的 user「继续」消息、不另开会话、
     * 不 regenerate 覆盖已落地内容。次数、等待时长分别沿用 [maxRetries] 那张网段的既有设置与
     * [autoResumeMaxAttempts]；用户点停止立刻取消等待中的续接。
     */
    val autoResumeEnabled: Boolean = false,
    /** 一整轮最多自动续接几次，有界 1..10，默认 3（传越界值会被夹到区间里，不是无上限）。 */
    val autoResumeMaxAttempts: Int = 3,
    /**
     * 用户自己写的「额外重试的错误匹配类型」，默认空 = 老行为一个字都不变。
     *
     * 规则只在 [RetryErrorCategory.CUSTOM_MATCH] 被勾选时才生效；命中后仍然不能越过
     * `isSafeToReplay`：已经执行过非幂等写工具的一轮不会被自动重试。
     */
    val retryCustomRules: List<CustomRetryRule> = emptyList(),
) {
    /** 这个设置对应的重试范围。判定只有 [RetryScope] 一份，[toRetryPolicy] 和自动恢复都读它。 */
    fun retryScope(): RetryScope = RetryScope(
        mode = retryScopeMode,
        // 有可用自定义规则时自动补上 CUSTOM_MATCH，用户不用再额外勾一次"自定义匹配"
        categories = retryExtraCategories.withCustomMatchIfNeeded(retryCustomRules),
        customRules = retryCustomRules,
    )

    /**
     * 把可能存在坏值的原始字段归整成一份合法配置。
     *
     * - 秒数越界（0、负数、超过 600）直接夹到 1~600，不因为一个坏值丢掉整个配置；
     * - 区间写反了就互换两端，等价于用户想要的区间。
     */
    fun normalizedRetryDelay(): RetryDelayConfig {
        val low = retryDelayMinSeconds.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)
        val high = retryDelayMaxSeconds.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)
        return RetryDelayConfig(
            mode = retryDelayMode,
            fixedSeconds = retryDelaySeconds.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS),
            minSeconds = minOf(low, high),
            maxSeconds = maxOf(low, high),
        )
    }

    companion object {
        const val MIN_RETRY_DELAY_SECONDS = 1
        const val MAX_RETRY_DELAY_SECONDS = 600
        const val DEFAULT_RETRY_DELAY_SECONDS = 60
        const val DEFAULT_RETRY_DELAY_MIN_SECONDS = 30
        const val DEFAULT_RETRY_DELAY_MAX_SECONDS = 60
    }
}

/**
 * 有可用自定义规则时把 [RetryErrorCategory.CUSTOM_MATCH] 补进追加集合。
 *
 * 界面把"自定义匹配"和那四个内置类别放在同一组多选里；用户只要加了至少一条有效规则，
 * 就默认视为勾上了这一类，不必再手动点一次，避免"规则写了但没生效"的困惑。
 */
private fun Set<RetryErrorCategory>.withCustomMatchIfNeeded(
    rules: List<CustomRetryRule>,
): Set<RetryErrorCategory> = if (CustomRetryRules.usable(rules).isEmpty()) {
    this
} else {
    this + RetryErrorCategory.CUSTOM_MATCH
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    // One data pipeline, two presentations; existing users can keep the classic layout.
    val useStudioDisplay: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableNotificationOnCompression: Boolean = true,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val collapseLongMessages: Boolean = true,
    /** Initial state only; an explicit per-message choice still wins. */
    val collapseLongMessagesByDefault: Boolean = true,
    val floatingCompanion: FloatingCompanionSettings = FloatingCompanionSettings(),
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val multiImageSend: MultiImageSendSettings = MultiImageSendSettings(),
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    // 外观（聊天界面风格与壁纸）单独一份，见 AppearanceSettings.kt；旧配置没有这个字段，读出来就是默认值
    val appearance: AppearanceSettings = AppearanceSettings(),
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
