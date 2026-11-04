package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.agent.GenerationSkipReason
import me.rerere.ai.agent.GenerationTiming
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.withRestoredWallpaperPath
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r10 配置与备份兼容性：r9 落盘的 JSON 在 r10 读回来要「旧值一律保留、新字段落到默认」，
 * 用户自己调过的透明度/立绘/台词/折叠与记忆设置不能被顺手改掉，跟随模型和没配模型都不能崩。
 *
 * 旧 JSON 是照 r9（HEAD）的 data class 字段字面写的：老用户盘上真的是整份 encodeDefaults=true 的 JSON。
 * 新字段的断言尽量用「整对象相等」表达，只有需要点名默认值时才写字段名。
 */
class R10SettingsCompatibilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // 和 BackupManager.restoreJson 同一套配置：备份里多出来的旧 key 要能忽略，null 要能回落默认
    private val backupJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    // ---- r9 盘上的真实形状 ----

    /** r9 的 displaySetting：有 appearance / floatingCompanion，但没有 immersiveChat 和任何小助手 AI 字段。 */
    private val r9DisplaySettingJson = """
        {
          "userNickname": "阿岚",
          "showUserAvatar": false,
          "showAssistantBubble": true,
          "bubbleOpacity": 0.62,
          "showModelIcon": false,
          "showModelName": false,
          "showDateTimeInMessage": true,
          "showTokenUsage": false,
          "useStudioDisplay": false,
          "showThinkingContent": false,
          "autoCloseThinking": false,
          "updateCheckDisabledUntilEpochMillis": 1700000000000,
          "showMessageJumper": false,
          "messageJumperOnLeft": true,
          "fontSizeRatio": 1.15,
          "enableMessageGenerationHapticEffect": true,
          "skipCropImage": false,
          "enableNotificationOnMessageGeneration": true,
          "enableLiveUpdateNotification": true,
          "enableFloatingChatWindow": true,
          "collapseLongMessages": false,
          "codeBlockAutoWrap": true,
          "codeBlockAutoCollapse": true,
          "showLineNumbers": true,
          "ttsOnlyReadQuoted": true,
          "ttsOnlyReadOutsideBrackets": true,
          "autoPlayTTSAfterGeneration": true,
          "voiceModeListenWhileReplying": false,
          "pasteLongTextAsFile": true,
          "pasteLongTextThreshold": 2000,
          "sendOnEnter": true,
          "enableAutoScroll": false,
          "enableLatexRendering": false,
          "enableQuoteHighlight": true,
          "enableBlurEffect": true,
          "backgroundEffectType": "glass",
          "chatFontFamily": "serif",
          "chatCustomFontPath": "file:///data/user/0/me.rerere.rikkahub/files/fonts/user.ttf",
          "chatCustomFontName": "我的字体",
          "enableVolumeKeyScroll": true,
          "volumeKeyScrollRatio": 1.5,
          "conversationSortOrder": "create_time",
          "floatingCompanion": {
            "preset": "dragon_girl",
            "customImagePath": "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/girl.png",
            "customTapImagePath": "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/tap.png",
            "name": "小蓝",
            "tapLines": ["在呢", "摸鱼中"],
            "sizeDp": 84,
            "animationEnabled": false,
            "minimized": false,
            "panelPreviewExpanded": true
          },
          "appearance": {
            "chatUiStyle": "focus",
            "wallpaper": "file:///data/user/0/me.rerere.rikkahub/files/appearance_wallpapers/w.jpg",
            "immersiveWallpaper": false,
            "wallpaperOpacity": 0.35,
            "wallpaperScrim": 0.4,
            "surfaceOpacity": 0.72,
            "wallpaperBlur": 12.0,
            "wallpaperScale": "fit",
            "wallpaperAlignmentX": 0.25,
            "wallpaperAlignmentY": 0.75,
            "wallpaperAdaptiveTint": false,
            "chatHeaderExpanded": true
          }
        }
    """.trimIndent()

    /** 上面那份 r9 JSON 应该读成什么（r10 新增字段全部留默认）。 */
    private val r9DisplaySetting = DisplaySetting(
        userNickname = "阿岚",
        showUserAvatar = false,
        showAssistantBubble = true,
        bubbleOpacity = 0.62f,
        showModelIcon = false,
        showModelName = false,
        showDateTimeInMessage = true,
        showTokenUsage = false,
        useStudioDisplay = false,
        showThinkingContent = false,
        autoCloseThinking = false,
        updateCheckDisabledUntilEpochMillis = 1_700_000_000_000,
        showMessageJumper = false,
        messageJumperOnLeft = true,
        fontSizeRatio = 1.15f,
        enableMessageGenerationHapticEffect = true,
        skipCropImage = false,
        enableNotificationOnMessageGeneration = true,
        enableLiveUpdateNotification = true,
        enableFloatingChatWindow = true,
        collapseLongMessages = false,
        codeBlockAutoWrap = true,
        codeBlockAutoCollapse = true,
        showLineNumbers = true,
        ttsOnlyReadQuoted = true,
        ttsOnlyReadOutsideBrackets = true,
        autoPlayTTSAfterGeneration = true,
        voiceModeListenWhileReplying = false,
        pasteLongTextAsFile = true,
        pasteLongTextThreshold = 2000,
        sendOnEnter = true,
        enableAutoScroll = false,
        enableLatexRendering = false,
        enableQuoteHighlight = true,
        enableBlurEffect = true,
        backgroundEffectType = BackgroundEffectType.GLASS,
        chatFontFamily = ChatFontFamily.SERIF,
        chatCustomFontPath = "file:///data/user/0/me.rerere.rikkahub/files/fonts/user.ttf",
        chatCustomFontName = "我的字体",
        enableVolumeKeyScroll = true,
        volumeKeyScrollRatio = 1.5f,
        conversationSortOrder = ConversationSortOrder.CREATE_TIME,
        floatingCompanion = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.DRAGON_GIRL,
            customImagePath = "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/girl.png",
            customTapImagePath = "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/tap.png",
            name = "小蓝",
            tapLines = listOf("在呢", "摸鱼中"),
            sizeDp = 84,
            animationEnabled = false,
            minimized = false,
            panelPreviewExpanded = true,
        ),
        appearance = AppearanceSettings(
            chatUiStyle = ChatUiStyle.FOCUS,
            wallpaper = "file:///data/user/0/me.rerere.rikkahub/files/appearance_wallpapers/w.jpg",
            immersiveWallpaper = false,
            wallpaperOpacity = 0.35f,
            wallpaperScrim = 0.4f,
            surfaceOpacity = 0.72f,
            wallpaperBlur = 12f,
            wallpaperScale = WallpaperScale.FIT,
            wallpaperAlignmentX = 0.25f,
            wallpaperAlignmentY = 0.75f,
            wallpaperAdaptiveTint = false,
            chatHeaderExpanded = true,
        ),
    )

    // ---- 1. r9 JSON 升级：旧值原样留下，新字段落默认 ----

    @Test
    fun `r9 display setting JSON upgrades to r10 defaults without touching user values`() {
        val decoded = JsonInstant.decodeFromString(DisplaySetting.serializer(), r9DisplaySettingJson)

        // 整对象相等 = 旧字段一个没丢 + r10 新字段（immersiveChat、小助手 AI 那一组）全是默认值
        assertEquals(r9DisplaySetting, decoded)

        // 点名写清 r10 新增字段的默认值，免得将来默认值被悄悄改掉
        assertTrue(decoded.appearance.immersiveChat)
        val companion = decoded.floatingCompanion
        assertEquals(CompanionModelMode.OFF, companion.aiModelMode)
        assertNull(companion.aiModelId)
        assertEquals("", companion.aiPersonaPrompt)
        assertFalse(companion.autoEventsEnabled)
        assertFalse(companion.companionStatusExpanded)
        assertFalse(companion.aiEnabled)
    }

    @Test
    fun `r9 datastore key upgrades in place through the real settings field`() {
        // 老用户盘上就是这个 key 里躺着 r9 的 displaySetting JSON
        val prefs = mutablePreferencesOf().apply {
            this[SettingsStore.DISPLAY_SETTING] = r9DisplaySettingJson
        }

        val settings = prefs.toSettings()

        assertEquals(r9DisplaySetting, settings.displaySetting)
        // 读盘不做归一化，归一化之后旧值也得一模一样
        val normalized = settings.normalized().displaySetting
        assertEquals(r9DisplaySetting.appearance, normalized.appearance)
        assertEquals(r9DisplaySetting.floatingCompanion, normalized.floatingCompanion)
        assertEquals(0.62f, normalized.bubbleOpacity, 0f)
        assertFalse(normalized.collapseLongMessages)
    }

    @Test
    fun `missing display setting key still produces r10 defaults`() {
        val settings = mutablePreferencesOf().toSettings()

        assertEquals(DisplaySetting(), settings.displaySetting)
        assertEquals(AppearanceSettings(), settings.displaySetting.appearance)
        assertEquals(FloatingCompanionSettings(), settings.displaySetting.floatingCompanion)
        assertEquals(CompanionModelMode.OFF, settings.displaySetting.floatingCompanion.aiModelMode)
    }

    // ---- 2. 用户旧手感：透明度 / 立绘 / 台词 / 折叠与记忆 ----

    @Test
    fun `old user transparency values survive normalization untouched`() {
        val legacy = Settings.dummy().copy(
            displaySetting = DisplaySetting(
                bubbleOpacity = 0.62f,
                appearance = AppearanceSettings(
                    wallpaperOpacity = 0.35f,
                    wallpaperScrim = 0.4f,
                    surfaceOpacity = 0.72f,
                    wallpaperBlur = 12f,
                    immersiveChat = false,
                ),
            ),
        )

        val normalized = legacy.normalized().displaySetting

        // 用户自己拖出来的值一个都不许变（既不许被新默认值覆盖，也不许被夹到别的档位）
        assertEquals(0.62f, normalized.bubbleOpacity, 0f)
        assertEquals(0.35f, normalized.appearance.wallpaperOpacity, 0f)
        assertEquals(0.4f, normalized.appearance.wallpaperScrim, 0f)
        assertEquals(0.72f, normalized.appearance.surfaceOpacity, 0f)
        assertEquals(12f, normalized.appearance.wallpaperBlur, 0f)
        // 关掉沉浸聊天就是回到 r9 的厚卡片，这条用户选择不能在归一化里丢掉
        assertFalse(normalized.appearance.immersiveChat)
    }

    @Test
    fun `old portrait custom lines and name survive decode plus normalization`() {
        val decoded = JsonInstant.decodeFromString(DisplaySetting.serializer(), r9DisplaySettingJson)
        val companion = decoded.floatingCompanion.normalized()

        assertEquals(FloatingCompanionPreset.DRAGON_GIRL, companion.effectivePreset)
        assertEquals("小蓝", companion.displayName("默认名"))
        assertEquals(listOf("在呢", "摸鱼中"), companion.tapLinesOr(listOf("默认台词")))
        assertEquals(84, companion.sizeDp)
        // 私有目录里的图片路径不能被清掉，否则用户原立绘就没了
        assertEquals(
            "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/girl.png",
            companion.customImagePath,
        )
        assertEquals(
            "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/tap.png",
            companion.customTapImagePath,
        )
        assertFalse(companion.animationEnabled)
        assertFalse(companion.minimized)
        assertTrue(companion.panelPreviewExpanded)
        // 归一化是幂等的，反复读写不会越跑越偏
        assertEquals(companion, companion.normalized())
    }

    @Test
    fun `custom portrait without image still falls back instead of going blank`() {
        val legacy = JsonInstant.decodeFromString<FloatingCompanionSettings>(
            """{"preset":"custom","customImagePath":null,"tapLines":["还在吗"]}""",
        ).normalized()

        assertEquals(FloatingCompanionPreset.WHALE_MAID, legacy.effectivePreset)
        assertEquals(listOf("还在吗"), legacy.tapLinesOr(emptyList()))
    }

    @Test
    fun `collapse and memory settings are kept across upgrade`() {
        val memoryId = Uuid.parse("7d1c0a1e-1111-4c3b-9a10-000000000001")
        val compressId = Uuid.parse("7d1c0a1e-2222-4c3b-9a10-000000000002")
        val legacy = Settings.dummy().copy(
            memoryModelId = memoryId,
            autoCompress = AutoCompressSettings(
                enabled = false,
                thresholdPercent = 55,
                modelId = compressId,
            ),
            displaySetting = DisplaySetting(
                collapseLongMessages = false,
                appearance = AppearanceSettings(immersiveChat = false),
            ),
        )

        val upgraded = legacy.normalized()

        assertFalse(upgraded.displaySetting.collapseLongMessages)
        assertEquals(memoryId, upgraded.memoryModelId)
        assertEquals(
            AutoCompressSettings(enabled = false, thresholdPercent = 55, modelId = compressId),
            upgraded.autoCompress,
        )
        assertFalse(upgraded.displaySetting.appearance.immersiveChat)
    }

    // ---- 3. 归一化：NaN / 越界 ----

    @Test
    fun `appearance NaN and out of range values fall back to defaults not boundaries`() {
        val broken = AppearanceSettings(
            wallpaper = "   ",
            wallpaperOpacity = Float.NaN,
            wallpaperScrim = Float.POSITIVE_INFINITY,
            surfaceOpacity = Float.NaN,
            wallpaperBlur = Float.NEGATIVE_INFINITY,
            wallpaperAlignmentX = Float.NaN,
            wallpaperAlignmentY = 2.5f,
            immersiveChat = false,
        )

        val normalized = broken.normalized()

        assertNull(normalized.wallpaper)
        assertEquals(1f, normalized.wallpaperOpacity, 0f)
        assertEquals(0.20f, normalized.wallpaperScrim, 0f)
        assertEquals(0.86f, normalized.surfaceOpacity, 0f)
        assertEquals(0f, normalized.wallpaperBlur, 0f)
        assertEquals(0.5f, normalized.wallpaperAlignmentX, 0f)
        assertEquals(1f, normalized.wallpaperAlignmentY, 0f)
        // 布尔开关不参与坏值收敛，用户关掉就还是关着
        assertFalse(normalized.immersiveChat)
        assertEquals(normalized, normalized.normalized())
    }

    @Test
    fun `settings normalization cleans broken appearance and companion without dropping user choices`() {
        val broken = Settings.dummy().copy(
            displaySetting = DisplaySetting(
                bubbleOpacity = 0.62f,
                collapseLongMessages = false,
                appearance = AppearanceSettings(surfaceOpacity = Float.NaN, wallpaperBlur = 999f),
                floatingCompanion = FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.SUN_READER,
                    tapLines = listOf("", "  ", "在呢"),
                    sizeDp = 400,
                    aiPersonaPrompt = "  " + "人".repeat(900) + "  ",
                    autoEventsEnabled = true,
                ),
            ),
        )

        val normalized = broken.normalized().displaySetting

        assertEquals(0.86f, normalized.appearance.surfaceOpacity, 0f)
        assertEquals(AppearanceSettings.MAX_BLUR_DP, normalized.appearance.wallpaperBlur, 0f)
        assertEquals(FloatingCompanionPreset.SUN_READER, normalized.floatingCompanion.preset)
        assertEquals(FloatingCompanionLimits.SIZE_MAX_DP, normalized.floatingCompanion.sizeDp)
        assertEquals(listOf("在呢"), normalized.floatingCompanion.tapLines)
        assertEquals(
            // r16 起 normalized 不再改已有 persona：长文本原样保留，只在保存入口显式拒绝超限
            "  " + "人".repeat(900) + "  ",
            normalized.floatingCompanion.aiPersonaPrompt,
        )
        // AI 还关着时残留的自动互动 true 不能留：下次打开会凭空开始花钱
        assertFalse(normalized.floatingCompanion.autoEventsEnabled)
        // 旧手感字段不受新字段影响
        assertEquals(0.62f, normalized.bubbleOpacity, 0f)
        assertFalse(normalized.collapseLongMessages)
    }

    @Test
    fun `companion model choice is never erased by normalization`() {
        val fixedId = Uuid.parse("7d1c0a1e-3333-4c3b-9a10-000000000003")

        val off = FloatingCompanionSettings(
            aiModelMode = CompanionModelMode.OFF,
            aiModelId = fixedId,
        ).normalized()
        assertFalse(off.aiEnabled)
        assertEquals(fixedId, off.aiModelId)

        val fixed = FloatingCompanionSettings(
            aiModelMode = CompanionModelMode.FIXED,
            aiModelId = fixedId,
            aiPersonaPrompt = "你是小蓝",
            autoEventsEnabled = true,
        ).normalized()
        assertTrue(fixed.aiEnabled)
        assertEquals(CompanionModelMode.FIXED, fixed.aiModelMode)
        assertEquals(fixedId, fixed.aiModelId)
        assertEquals("你是小蓝", fixed.aiPersonaPrompt)
        assertTrue(fixed.autoEventsEnabled)
    }

    // ---- 4. 跟随模型 / 没配模型：不崩，报"没有模型" ----

    @Test
    fun `follow conversation and fixed mode with no configured model resolve to null instead of throwing`() {
        // 全新安装：所有提供商都还没有模型
        val settings = Settings()
        assertTrue(settings.isNotConfigured())

        val fixedId = Uuid.parse("7d1c0a1e-4444-4c3b-9a10-000000000004")
        val fixed = settings.copy(
            displaySetting = settings.displaySetting.copy(
                floatingCompanion = FloatingCompanionSettings(
                    aiModelMode = CompanionModelMode.FIXED,
                    aiModelId = fixedId,
                ),
            ),
        )

        // 指定的模型被删了 / 从未配过：查出来是 null，而不是抛异常或悄悄换成别的模型
        assertNull(fixed.findModelById(fixedId))
        assertNull(fixed.findModelById(null))

        // 跟随会话：会话模型拿不到时回落到全局聊天模型，一样是 null 而不是崩
        val follow = settings.copy(chatModelId = Uuid.random())
        assertNull(
            follow.findModelById(
                follow.getCurrentAssistant().chatModelId ?: follow.chatModelId,
            ),
        )

        // 归一化不会把用户选的模式和模型 ID 洗掉，界面上才有东西可以提示"没有配置模型"
        val normalized = fixed.normalized()
        assertEquals(CompanionModelMode.FIXED, normalized.displaySetting.floatingCompanion.aiModelMode)
        assertEquals(fixedId, normalized.displaySetting.floatingCompanion.aiModelId)
        assertNull(normalized.findModelById(normalized.displaySetting.floatingCompanion.aiModelId))
    }

    // ---- 5. 序列化 roundtrip：新字段真的写进 JSON 又读得回来 ----

    @Test
    fun `r10 settings roundtrip keeps every new field`() {
        val fixedId = Uuid.parse("7d1c0a1e-5555-4c3b-9a10-000000000005")
        val original = Settings.dummy().copy(
            displaySetting = DisplaySetting(
                bubbleOpacity = 0.62f,
                appearance = AppearanceSettings(
                    chatUiStyle = ChatUiStyle.FOCUS,
                    wallpaper = "file:///files/appearance_wallpapers/w.jpg",
                    surfaceOpacity = 0.72f,
                    immersiveChat = false,
                ),
                floatingCompanion = FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.WHALE_MAID,
                    tapLines = listOf("在呢"),
                    aiModelMode = CompanionModelMode.FIXED,
                    aiModelId = fixedId,
                    aiPersonaPrompt = "你是小蓝，说话短一点",
                    autoEventsEnabled = true,
                    companionStatusExpanded = true,
                ),
            ),
        )

        val restored = JsonInstant.decodeFromString(
            Settings.serializer(),
            JsonInstant.encodeToString(Settings.serializer(), original),
        )

        assertEquals(original.displaySetting.appearance, restored.displaySetting.appearance)
        assertEquals(original.displaySetting.floatingCompanion, restored.displaySetting.floatingCompanion)
        assertFalse(restored.displaySetting.appearance.immersiveChat)
        assertEquals(fixedId, restored.displaySetting.floatingCompanion.aiModelId)
        assertEquals(CompanionModelMode.FIXED, restored.displaySetting.floatingCompanion.aiModelMode)
        assertEquals("你是小蓝，说话短一点", restored.displaySetting.floatingCompanion.aiPersonaPrompt)
        assertTrue(restored.displaySetting.floatingCompanion.autoEventsEnabled)
        assertTrue(restored.displaySetting.floatingCompanion.companionStatusExpanded)
        // 旧字段不能在同一份 JSON 里被新字段挤掉
        assertEquals(0.62f, restored.displaySetting.bubbleOpacity, 0f)
        assertEquals(listOf("在呢"), restored.displaySetting.floatingCompanion.tapLines)
    }

    @Test
    fun `new appearance field is written to disk so it survives a restart`() {
        val encoded = JsonInstant.encodeToString(
            DisplaySetting.serializer(),
            DisplaySetting(appearance = AppearanceSettings(immersiveChat = false)),
        )

        assertTrue(encoded.contains("immersiveChat"))
        assertFalse(JsonInstant.decodeFromString(DisplaySetting.serializer(), encoded).appearance.immersiveChat)
    }

    @Test
    fun `r9 generation timing JSON keeps legacy speed semantics`() {
        val single = JsonInstant.decodeFromString(
            GenerationTiming.serializer(),
            """{"firstContentMs":120,"generationMs":1000,"requestMs":1500,"outputTokens":50,"measuredSteps":1,"totalSteps":1}""",
        )

        // r9 及更早的记录没有 measuredOutputTokens / skippedReasons，读出来必须是 0 / 空而不是乱猜
        assertEquals(0L, single.measuredOutputTokens)
        assertTrue(single.skippedReasons.isEmpty())
        assertEquals(0, single.skippedSteps)
        assertFalse(single.isPartial)
        assertEquals(50.0, single.speedTokensPerSecond!!, 1e-9)

        // 部分测到的老记录无从判断分子，只能不给速度，而不是把全部 token 混进去抬高
        val partial = JsonInstant.decodeFromString(
            GenerationTiming.serializer(),
            """{"generationMs":800,"outputTokens":40,"measuredSteps":1,"totalSteps":2}""",
        )
        assertEquals(0L, partial.measuredTokens)
        assertNull(partial.speedTokensPerSecond)
        assertEquals(1, partial.skippedSteps)
        assertTrue(partial.isPartial)
    }

    @Test
    fun `r10 generation timing roundtrip keeps new fields`() {
        val original = GenerationTiming(
            firstContentMs = 100,
            generationMs = 1000,
            requestMs = 1200,
            outputTokens = 80,
            measuredSteps = 2,
            totalSteps = 3,
            measuredOutputTokens = 60,
            skippedReasons = mapOf(GenerationSkipReason.SERVER_WAIT to 1),
        )

        val restored = JsonInstant.decodeFromString(
            GenerationTiming.serializer(),
            JsonInstant.encodeToString(GenerationTiming.serializer(), original),
        )

        assertEquals(original, restored)
        assertEquals(60.0, restored.speedTokensPerSecond!!, 1e-9)
        assertEquals(1, restored.skippedSteps)
    }

    // ---- 6. 备份：已有 helper 只改路径，不动内容 ----

    @Test
    fun `backup restore relocates owned paths and keeps r10 fields plus user content`() {
        val fixedId = Uuid.parse("7d1c0a1e-6666-4c3b-9a10-000000000006")
        val backup = Settings.dummy().copy(
            displaySetting = DisplaySetting(
                bubbleOpacity = 0.62f,
                collapseLongMessages = false,
                appearance = AppearanceSettings(
                    wallpaper = "file:///old/app/files/appearance_wallpapers/w%20one.png",
                    surfaceOpacity = 0.72f,
                    immersiveChat = false,
                ),
                floatingCompanion = FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.CUSTOM,
                    customImagePath = "file:///old/app/files/floating_companions/girl.png",
                    customTapImagePath = "file:///old/app/files/floating_companions/tap.png",
                    name = "小蓝",
                    tapLines = listOf("在呢"),
                    aiModelMode = CompanionModelMode.FIXED,
                    aiModelId = fixedId,
                    aiPersonaPrompt = "你是小蓝",
                    autoEventsEnabled = true,
                    companionStatusExpanded = true,
                ),
            ),
        )

        val restored = backup.withRestoredWallpaperPath(File("/new/app/files"))

        // 只有本应用私有目录里的路径被搬到新目录
        assertEquals(
            "file:/new/app/files/appearance_wallpapers/w%20one.png",
            restored.displaySetting.appearance.wallpaper,
        )
        assertEquals(
            "file:/new/app/files/floating_companions/girl.png",
            restored.displaySetting.floatingCompanion.customImagePath,
        )
        assertEquals(
            "file:/new/app/files/floating_companions/tap.png",
            restored.displaySetting.floatingCompanion.customTapImagePath,
        )
        // 其余内容一个字节都不该变
        val companion = restored.displaySetting.floatingCompanion
        assertEquals(FloatingCompanionPreset.CUSTOM, companion.preset)
        assertEquals("小蓝", companion.name)
        assertEquals(listOf("在呢"), companion.tapLines)
        assertEquals(CompanionModelMode.FIXED, companion.aiModelMode)
        assertEquals(fixedId, companion.aiModelId)
        assertEquals("你是小蓝", companion.aiPersonaPrompt)
        assertTrue(companion.autoEventsEnabled)
        assertTrue(companion.companionStatusExpanded)
        assertFalse(restored.displaySetting.appearance.immersiveChat)
        assertEquals(0.72f, restored.displaySetting.appearance.surfaceOpacity, 0f)
        assertEquals(0.62f, restored.displaySetting.bubbleOpacity, 0f)
        assertFalse(restored.displaySetting.collapseLongMessages)
        // 同一个目录下再搬一次不应该再改（幂等）
        assertEquals(restored, restored.withRestoredWallpaperPath(File("/new/app/files")))
    }

    @Test
    fun `backup restore never rewrites network or foreign paths`() {
        val backup = Settings.dummy().copy(
            displaySetting = DisplaySetting(
                appearance = AppearanceSettings(wallpaper = "https://example.test/a.png"),
                floatingCompanion = FloatingCompanionSettings(
                    customImagePath = "https://example.test/cat.png",
                    customTapImagePath = "file:///other/shared_images/x.png",
                ),
            ),
        )

        val restored = backup.withRestoredWallpaperPath(File("/new/app/files"))

        assertEquals("https://example.test/a.png", restored.displaySetting.appearance.wallpaper)
        assertEquals("https://example.test/cat.png", restored.displaySetting.floatingCompanion.customImagePath)
        assertEquals(
            "file:///other/shared_images/x.png",
            restored.displaySetting.floatingCompanion.customTapImagePath,
        )
        // 什么都没变时必须返回原对象，避免无意义的重写
        assertEquals(backup, restored)
    }

    @Test
    fun `backup settings json from r9 migrates and decodes with r10 defaults`() {
        // 老备份里的 settings.json：只挑和外观/记忆相关的那部分，providers/assistants 不参与这次迁移
        val r9BackupSettings = """
            {
              "developerMode": false,
              "memoryModelId": "7d1c0a1e-7777-4c3b-9a10-000000000007",
              "autoCompress": {"enabled": false, "thresholdPercent": 55, "modelId": null},
              "displaySetting": ${r9DisplaySettingJson},
              "launchCount": 42
            }
        """.trimIndent()

        val migrated = SettingsJsonMigrator.migrate(r9BackupSettings)
        val restored = backupJson.decodeFromString(Settings.serializer(), migrated)

        assertEquals(r9DisplaySetting, restored.displaySetting)
        assertEquals(Uuid.parse("7d1c0a1e-7777-4c3b-9a10-000000000007"), restored.memoryModelId)
        assertEquals(
            AutoCompressSettings(enabled = false, thresholdPercent = 55, modelId = null),
            restored.autoCompress,
        )
        assertEquals(CompanionModelMode.OFF, restored.displaySetting.floatingCompanion.aiModelMode)
        assertTrue(restored.displaySetting.appearance.immersiveChat)
        // launchCount 不是 Settings 的字段，忽略掉即可，不能因此整份恢复失败
        assertFalse(restored.init)
    }

    @Test
    fun `companion owned files helper only lists existing files inside the private folder`() {
        val owned = tempFolder.newFolder("floating_companions")
        val idle = File(owned, "idle.png").apply { writeBytes(byteArrayOf(1)) }
        File(owned, "tap.png").apply { writeBytes(byteArrayOf(2)) }
        val outside = tempFolder.newFolder("upload")
        File(outside, "x.png").apply { writeBytes(byteArrayOf(3)) }

        val settings = FloatingCompanionSettings(
            customImagePath = idle.toURI().toString(),
            customTapImagePath = File(outside, "x.png").toURI().toString(),
        )

        // 备份要拷的是"设置里记着、而且真的还在私有目录里"的图
        assertEquals(listOf("idle.png"), FloatingCompanionPaths.ownedFiles(settings, owned).map { it.name })
        assertEquals(
            1,
            FloatingCompanionPaths.ownedFiles(
                settings.copy(customTapImagePath = idle.toURI().toString()),
                owned,
            ).size,
        )
        // 设置里记着但文件已经被删掉的不再出现在备份名单里
        assertTrue(
            FloatingCompanionPaths.ownedFiles(
                FloatingCompanionSettings(
                    customImagePath = File(owned, "missing.png").toURI().toString(),
                    customTapImagePath = File(outside, "x.png").toURI().toString(),
                ),
                owned,
            ).isEmpty(),
        )

        val remote = FloatingCompanionSettings(customImagePath = "https://example.test/cat.png")
        assertTrue(FloatingCompanionPaths.ownedFiles(remote, owned).isEmpty())
    }

    @Test
    fun `r10 companion fields do not leak into new top level settings keys`() {
        // 新字段只能出现在 floatingCompanion / appearance 内部，不能另开顶层 key 让老版本读到坏数据
        val encoded = JsonInstant.encodeToString(
            Settings.serializer(),
            Settings.dummy().copy(
                displaySetting = DisplaySetting(
                    floatingCompanion = FloatingCompanionSettings(
                        aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
                        companionStatusExpanded = true,
                    ),
                    appearance = AppearanceSettings(immersiveChat = false),
                ),
            ),
        )
        val root = JsonInstant.parseToJsonElement(encoded) as JsonObject

        assertFalse(root.containsKey("floatingCompanion"))
        assertFalse(root.containsKey("appearance"))
        assertFalse(root.containsKey("immersiveChat"))
        assertTrue(encoded.contains("follow_conversation"))
    }
}
