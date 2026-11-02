package me.rerere.rikkahub.ui.theme

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.setting.AppearanceSettingsCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外观设置的真实渲染 + 真实持久化。
 *
 * 用的是设备上真正的那份 SettingsStore（同一个 DataStore 单例），不启动 Koin、不碰网络和供应商：
 * 点 AppearanceSettingsCard 里的风格按钮 -> 走生产同样的 update 路径 -> 从 store 读回 -> 再从
 * settings.preferences_pb 的原始字节里确认确实落盘了。用例结束后把用户的 appearance 原样写回去。
 */
@RunWith(AndroidJUnit4::class)
class AppearanceIntegrationTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var scope: AppScope
    private lateinit var store: SettingsStore
    private var originalAppearance: AppearanceSettings? = null

    @Before
    fun setUp() {
        scope = AppScope()
        store = SettingsStore(context, scope)
        originalAppearance = runBlocking { store.awaitLoaded().displaySetting.appearance }
    }

    @After
    fun tearDown() = runBlocking {
        originalAppearance?.let { saved ->
            store.update { it.copy(displaySetting = it.displaySetting.copy(appearance = saved)) }
        }
        scope.coroutineContext.job.cancelAndJoin()
    }

    private suspend fun save(appearance: AppearanceSettings) {
        store.update { it.copy(displaySetting = it.displaySetting.copy(appearance = appearance)) }
    }

    private fun stored(): AppearanceSettings = store.settingsFlow.value.displaySetting.appearance

    private fun label(resId: Int): String = context.getString(resId)

    private fun styleLabel(style: ChatUiStyle): String = label(
        when (style) {
            ChatUiStyle.CLASSIC -> R.string.appearance_style_classic
            ChatUiStyle.STUDIO -> R.string.appearance_style_studio
            ChatUiStyle.FOCUS -> R.string.appearance_style_focus
        }
    )

    private fun settingsWith(appearance: AppearanceSettings): Settings =
        Settings.dummy().copy(displaySetting = DisplaySetting(appearance = appearance))

    private fun settingsFileText(): String {
        val file = File(context.filesDir, "datastore/settings.preferences_pb")
        assertTrue("找不到设置文件 ${file.absolutePath}", file.exists())
        return file.readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test
    fun cardSwitchesEveryStyleAndStoreReadsTheChoiceBack() {
        val written = mutableListOf<ChatUiStyle>()
        rule.setContent {
            val density = LocalDensity.current
            var appearance by remember { mutableStateOf(AppearanceSettings(chatUiStyle = ChatUiStyle.STUDIO)) }
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp)) {
                            AppearanceSettingsCard(
                                appearance = appearance,
                                onChange = { next ->
                                    appearance = next
                                    scope.launch {
                                        save(next)
                                        written += next.chatUiStyle
                                    }
                                },
                                scrollable = true,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        ChatUiStyle.entries.forEach { style ->
            val option = rule.onNode(hasText(styleLabel(style)) and isSelectable())
            option.performClick()
            rule.waitUntil(15_000) { written.contains(style) }
            rule.runOnIdle { assertEquals("store 里读回的风格不对", style, stored().chatUiStyle) }
            option.assertIsSelected()
            AppearanceTestSupport.capture(rule, "card-${style.name.lowercase()}-light")
        }
        val freshScope = AppScope()
        try {
            val reloaded = SettingsStore(context, freshScope)
            assertEquals("新设置实例重新载入磁盘后风格不对", ChatUiStyle.FOCUS,
                runBlocking { reloaded.awaitLoaded() }.displaySetting.appearance.chatUiStyle)
        } finally { runBlocking { freshScope.coroutineContext.job.cancelAndJoin() } }
        assertTrue("最后选中的风格没有写进设置文件", settingsFileText().contains("\"chatUiStyle\":\"focus\""))
    }

    @Test
    fun darkThemeWithLargeFontRendersEveryStyleAndOpensWallpaperSheet() {
        val written = mutableListOf<ChatUiStyle>()
        rule.setContent {
            val density = LocalDensity.current
            var appearance by remember { mutableStateOf(AppearanceSettings()) }
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            AppearanceSettingsCard(
                                appearance = appearance,
                                onChange = { next ->
                                    appearance = next
                                    scope.launch {
                                        save(next)
                                        written += next.chatUiStyle
                                    }
                                },
                                scrollable = true,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(label(R.string.appearance_style_section)).assertIsDisplayed()
        ChatUiStyle.entries.forEach { style ->
            rule.onNode(hasText(styleLabel(style)) and isSelectable()).performScrollTo().performClick()
            rule.waitUntil(15_000) { written.contains(style) }
            AppearanceTestSupport.capture(rule, "card-${style.name.lowercase()}-dark-font1.3")
        }
        rule.runOnIdle { assertEquals(ChatUiStyle.FOCUS, stored().chatUiStyle) }

        rule.onNodeWithText(label(R.string.appearance_wallpaper_pick)).performScrollTo().performClick()
        rule.onNodeWithText(label(R.string.appearance_wallpaper_pick_gallery)).assertIsDisplayed()
        AppearanceTestSupport.capture(rule, rule.onNode(isDialog()), "dialog-source-dark-font1.3")
        rule.onNodeWithText(label(R.string.appearance_cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(label(R.string.appearance_wallpaper_pick_gallery)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.appearance_style_section)).assertExists()
    }

    @Test
    fun cardShowsWallpaperPreviewDialogAndRemovesTheWallpaper() {
        val source = AppearanceTestSupport.extreme("preview-bright", bright = true)
        val written = mutableListOf<AppearanceSettings>()
        rule.setContent {
            var appearance by remember { mutableStateOf(AppearanceSettings(wallpaper = source)) }
            val scope = rememberCoroutineScope()
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        AppearanceSettingsCard(
                            appearance = appearance,
                            onChange = { next ->
                                appearance = next
                                scope.launch {
                                    save(next)
                                    written += next
                                }
                            },
                            scrollable = true,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(label(R.string.appearance_wallpaper_preview_desc))
            .performScrollTo()
            .performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(label(R.string.appearance_confirm)).assertIsDisplayed()
        AppearanceTestSupport.capture(rule, rule.onNode(isDialog()), "dialog-preview-light")
        rule.onNodeWithText(label(R.string.appearance_confirm)).performClick()
        rule.waitForIdle()
        rule.onNode(isDialog()).assertDoesNotExist()

        rule.onNodeWithText(label(R.string.appearance_wallpaper_remove)).performScrollTo().performClick()
        rule.waitUntil(15_000) { written.any { it.wallpaper == null } }
        rule.runOnIdle { assertNull("删除壁纸没有写回设置", stored().wallpaper) }
    }

    @Test
    fun globalHostUnlocksTranslucentPagesOnlyAfterTheWallpaperLoads() {
        val bright = AppearanceTestSupport.extreme("host-bright", bright = true)
        val darkSource = AppearanceTestSupport.extreme("host-dark", bright = false)
        val noise = AppearanceTestSupport.noise("host-noise")
        fun host(image: String) =
            settingsWith(AppearanceSettings(wallpaper = image, wallpaperScrim = 0f, wallpaperOpacity = 1f))

        val settings = mutableStateOf(host(bright))
        var active: Boolean? = null
        var page: Color? = null
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                AppAppearanceHost(settings.value) {
                    val wallpaperActive = LocalWallpaperActive.current
                    val pageContainer = LocalPageContainer.current
                    SideEffect {
                        active = wallpaperActive
                        page = pageContainer
                    }
                    Text("probe")
                }
            }
        }

        fun show(name: String, image: String, brightImage: Boolean?) {
            rule.runOnIdle {
                active = null
                page = null
                settings.value = host(image)
            }
            rule.waitUntil(20_000) { active == true }
            rule.runOnIdle {
                assertEquals("$name 没有解锁沉浸壁纸", true, active)
                assertEquals("$name 页面容器应该是透明的", Color.Transparent, page)
            }
            val shot = rule.onRoot().captureToImage()
            AppearanceTestSupport.assertNear(
                AppearanceTestSupport.sample(shot, 0.25f, 0.5f),
                Color.Red,
                0.15f,
                "$name 壁纸中央的标记没画出来",
            )
            when (brightImage) {
                true -> {
                    val tone = AppearanceTestSupport.sample(shot, 0.62f, 0.5f)
                    assertTrue("$name 极亮壁纸被压暗了: $tone", tone.red > 0.8f && tone.green > 0.8f && tone.blue > 0.8f)
                }
                false -> {
                    val tone = AppearanceTestSupport.sample(shot, 0.62f, 0.5f)
                    assertTrue("$name 极暗壁纸被提亮了: $tone", tone.red < 0.25f && tone.green < 0.25f && tone.blue < 0.25f)
                }
                null -> {
                    val first = AppearanceTestSupport.sample(shot, 0.62f, 0.42f)
                    val second = AppearanceTestSupport.sample(shot, 0.75f, 0.58f)
                    assertTrue("$name 噪声壁纸被抹平了: $first / $second", first != second)
                }
            }
            AppearanceTestSupport.capture(rule, "host-$name")
        }

        show("bright", bright, true)
        show("dark", darkSource, false)
        show("noise", noise, null)
    }

    @Test fun transparentHostsKeepTheCurrentThemeForegroundInBothModes() {
        var dark by mutableStateOf(false)
        var appForeground: Color? = null
        var chatForeground: Color? = null
        val settings = settingsWith(AppearanceSettings())
        rule.setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialExpressiveTheme(colorScheme = scheme) {
                AppAppearanceHost(settings) {
                    val app = androidx.compose.material3.LocalContentColor.current
                    ChatAppearanceHost(settings, settings.getCurrentAssistant()) {
                        val chat = androidx.compose.material3.LocalContentColor.current
                        SideEffect { appForeground = app; chatForeground = chat }
                        Text("Foreground probe")
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            rule.runOnIdle { dark = night }
            rule.waitForIdle()
            val expected = (if (night) darkColorScheme() else lightColorScheme()).onBackground
            rule.runOnIdle {
                assertEquals("transparent app host lost theme foreground", expected, appForeground)
                assertEquals("transparent chat host lost theme foreground", expected, chatForeground)
            }
        }
    }

    @Test
    fun brokenWallpaperKeepsTheSolidPageInsteadOfGoingTransparent() {
        val missing = android.net.Uri.fromFile(File(context.cacheDir, "r6-missing-${System.nanoTime()}.png")).toString()
        val appearance = AppearanceSettings(wallpaper = missing, wallpaperScrim = 0f)
        var active: Boolean? = null
        var page: Color? = null
        var probe: Boolean? = null
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                AppAppearanceHost(settingsWith(appearance)) {
                    val wallpaperActive = LocalWallpaperActive.current
                    val pageContainer = LocalPageContainer.current
                    SideEffect {
                        active = wallpaperActive
                        page = pageContainer
                    }
                    // 同一个地址再挂一份探针：它报 onLoaded(false) 才说明这次加载是真失败了，而不是还没加载完
                    WallpaperBackdrop(missing, appearance, onLoaded = { probe = it })
                    Text("probe")
                }
            }
        }
        rule.waitUntil(20_000) { probe == false }
        rule.runOnIdle {
            assertEquals("加载失败不能解锁沉浸壁纸", false, active)
            val container = page
            assertNotNull(container)
            assertEquals("加载失败时页面容器必须是主题底色，不能是透明", lightColorScheme().background, container)
            assertEquals(1f, container!!.alpha, 0f)
        }
        AppearanceTestSupport.assertNear(
            AppearanceTestSupport.sample(rule.onRoot().captureToImage(), 0.5f, 0.5f),
            lightColorScheme().background,
            0.05f,
            "加载失败后落回来的不是主题底色",
        )
        AppearanceTestSupport.capture(rule, "host-broken-fallback")
    }

    @Test
    fun conversationAssistantBackgroundWinsOverTheSelectedAssistant() {
        val red = AppearanceTestSupport.solid("assistant-red", 0xFFFF0000.toInt())
        val blue = AppearanceTestSupport.solid("assistant-blue", 0xFF0000FF.toInt())
        val green = AppearanceTestSupport.solid("assistant-green", 0xFF00C000.toInt())
        val selected = Assistant(name = "drawer", background = red)
        val restored = Assistant(name = "conversation", background = blue)
        val immersive = settingsWith(
            AppearanceSettings(wallpaper = green, wallpaperScrim = 0f, immersiveWallpaper = true),
        ).copy(assistants = listOf(selected, restored), assistantId = selected.id)
        assertEquals("设置里当前选中的应该还是抽屉里那个助手", selected, immersive.getCurrentAssistant())

        val settings = mutableStateOf(immersive)
        val assistant = mutableStateOf(restored)
        var active: Boolean? = null
        var page: Color? = null
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                ChatAppearanceHost(settings.value, assistant.value) {
                    val wallpaperActive = LocalWallpaperActive.current
                    val pageContainer = LocalPageContainer.current
                    SideEffect {
                        active = wallpaperActive
                        page = pageContainer
                    }
                    Text("probe")
                }
            }
        }
        rule.waitUntil(20_000) { active == true }
        rule.runOnIdle { assertEquals(Color.Transparent, page) }

        val center = AppearanceTestSupport.sample(rule.onRoot().captureToImage(), 0.5f, 0.5f)
        assertTrue("恢复的会话没有用自己的助手背景: $center", center.blue > 0.6f && center.red < 0.25f)
        AppearanceTestSupport.capture(rule, "assistant-own-background")

        // 会话助手自己没有背景、全局壁纸又不是沉浸式时，才轮到全局壁纸
        rule.runOnIdle {
            active = null
            settings.value = settingsWith(
                AppearanceSettings(wallpaper = green, wallpaperScrim = 0f, immersiveWallpaper = false),
            ).copy(assistants = listOf(selected, restored), assistantId = selected.id)
            assistant.value = restored.copy(background = null)
        }
        rule.waitUntil(20_000) { active == true }
        val fallback = AppearanceTestSupport.sample(rule.onRoot().captureToImage(), 0.5f, 0.5f)
        assertTrue("没有助手背景时应该用全局壁纸: $fallback", fallback.green > 0.45f && fallback.red < 0.3f && fallback.blue < 0.3f)
        AppearanceTestSupport.capture(rule, "assistant-falls-back-to-global-wallpaper")
    }
}
