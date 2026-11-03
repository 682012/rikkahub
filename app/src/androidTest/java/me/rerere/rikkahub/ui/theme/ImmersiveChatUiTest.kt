package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.ChatListRootTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r10 沉浸聊天：有壁纸时的真实渲染 + 像素断言。
 *
 * 断言的都是可测事实，不靠“看起来不错”：
 * - 消息外框区域和同一高度的裸壁纸区域**像素相同** → 外框真的没了；
 * - 顶部栏/正文面板比裸壁纸亮/暗一档，但和正文的对比度仍 >= 4.5:1 → 薄但可读；
 * - 关掉沉浸开关后外框重新变成不透明面板 → “恢复卡片”有效；
 * - 没有壁纸时三种面板色与主题原色**完全相等** → 默认外观一点没变。
 *
 * 这里是主题层（`chatPanel` / `chatPanelStrong` / `chatFrame` / `WallpaperProtection`）的验收；
 * ChatMessage 里那条外框走同一个 [chatFrame]，切换后像素结论一致。
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveChatUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val lightScheme = lightColorScheme()

    private class Probes {
        var immersive: Boolean? = null
        var active: Boolean? = null
        var plan: ImmersiveContrastPlan? = null
        var glass: Color? = null
        var base: Color? = null
        var frame: Color? = null
        var schemeBackground: Color? = null
    }

    private fun settingsFor(appearance: AppearanceSettings): Settings =
        Settings.dummy().copy(displaySetting = DisplaySetting(appearance = appearance))

    private fun settingsFor(wallpaper: String?, immersive: Boolean, scrim: Float = 0f): Settings =
        settingsFor(AppearanceSettings(wallpaper = wallpaper, wallpaperScrim = scrim, immersiveChat = immersive))

    /**
     * 等壁纸解码 + 采样完成。
     *
     * 采样到之前预算是按最坏情况（纯黑 + 纯白）算的，直接截图会和真实壁纸对不上，
     * 所以这里等到两端不再是 0/1 再断言。
     */
    private fun awaitSampled(probes: Probes) {
        rule.waitUntil(20_000) { probes.active == true }
        rule.waitUntil(20_000) { probes.immersive == true }
        rule.waitUntil(20_000) {
            val backdrops = probes.plan?.backdrops
            !backdrops.isNullOrEmpty() && backdrops.none { it.red <= 0.001f || it.red >= 0.999f }
        }
    }

    @Composable
    private fun Probe(modifier: Modifier, color: Color, tag: String) {
        Surface(
            modifier = modifier.testTag(tag),
            color = color,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {}
    }

    @Test
    fun immersiveChatDropsTheCardAndKeepsEveryPanelReadable() {
        val wallpaper = AppearanceTestSupport.solid("r10-mid-blue", android.graphics.Color.rgb(80, 120, 200))
        var immersiveOn by mutableStateOf(true)
        val probes = Probes()
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(settingsFor(wallpaper, immersiveOn)) {
                    val scheme = MaterialTheme.colorScheme
                    val glass = chatPanelStrong(scheme.surfaceContainerLow)
                    val base = chatPanel(scheme.surfaceContainerHigh)
                    val frame = chatFrame(scheme.surfaceContainerLow)
                    // CompositionLocal.current 是 @Composable getter，只能在组合里读；
                    // SideEffect 里只做采样写入，避免在 lambda 里调用 @Composable。
                    val immersive = LocalImmersiveChat.current
                    val wallpaperActive = LocalWallpaperActive.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.glass = glass
                        probes.base = base
                        probes.frame = frame
                        probes.immersive = immersive
                        probes.active = wallpaperActive
                        probes.plan = contrastPlan
                        probes.schemeBackground = scheme.background
                    }
                    Column(Modifier.fillMaxSize()) {
                        // 上排：裸壁纸 vs 消息外框（同一高度 → 同一个遮罩强度，像素必须一致）
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("bare"))
                            Probe(Modifier.weight(1f).fillMaxHeight(), frame, "frame")
                        }
                        // 下排：顶部/输入区薄玻璃 vs 正文文字底
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Probe(Modifier.weight(1f).fillMaxHeight(), glass, "glass")
                            Probe(Modifier.weight(1f).fillMaxHeight(), base, "base")
                        }
                    }
                }
            }
        }
        awaitSampled(probes)

        rule.runOnIdle {
            assertEquals("沉浸模式下消息外框必须透明", Color.Transparent, probes.frame)
            val base = requireNotNull(probes.base)
            val glass = requireNotNull(probes.glass)
            assertTrue("正文底还是 r9 的厚卡片：${base.alpha}", base.alpha <= ImmersivePanels.MAX_SURFACE + 1e-4f)
            assertTrue("顶部/输入区玻璃太厚：${glass.alpha}", glass.alpha <= ImmersiveContrastPlan.MAX_GLASS + 1e-4f)
            assertTrue("玻璃应该比正文底厚一点点：$glass vs $base", glass.alpha > base.alpha)
        }

        val shot = rule.onRoot().captureToImage()
        val bare = AppearanceTestSupport.sample(shot, 0.25f, 0.25f)
        val frame = AppearanceTestSupport.sample(shot, 0.75f, 0.25f)
        AppearanceTestSupport.assertNear(frame, bare, 0.02f, "消息外框位置还压着一层底，壁纸没透出来")
        assertTrue("裸壁纸区域看不出壁纸：$bare", bare.blue > bare.red + 0.05f)

        val glass = AppearanceTestSupport.sample(shot, 0.25f, 0.75f)
        val base = AppearanceTestSupport.sample(shot, 0.75f, 0.75f)
        assertTrue(
            "薄玻璃和裸壁纸一模一样，等于没有面板",
            abs(glass.red - bare.red) > 0.02f || abs(glass.blue - bare.blue) > 0.02f,
        )
        assertTrue(
            "薄玻璃上正文只剩 ${surfaceContrast(glass, lightScheme.onSurface)} 对比度",
            surfaceContrast(glass, lightScheme.onSurface) >= 4.5f,
        )
        assertTrue(
            "正文薄底上正文只剩 ${surfaceContrast(base, lightScheme.onSurface)} 对比度",
            surfaceContrast(base, lightScheme.onSurface) >= 4.5f,
        )
        AppearanceTestSupport.capture(rule, "r10-immersive-thin-panels")

        // 关掉沉浸 → 恢复 r9 的卡片
        rule.runOnIdle { immersiveOn = false }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("关了沉浸外框还是透明的：${probes.frame}", requireNotNull(probes.frame).alpha >= 0.6f)
            assertTrue("关了沉浸正文底还是薄的：${probes.base}", requireNotNull(probes.base).alpha >= 0.6f)
        }
        val restored = rule.onRoot().captureToImage()
        val bareAfter = AppearanceTestSupport.sample(restored, 0.25f, 0.25f)
        val frameAfter = AppearanceTestSupport.sample(restored, 0.75f, 0.25f)
        assertTrue(
            "关掉沉浸后外框应该重新压住壁纸：$frameAfter vs $bareAfter",
            maxOf(abs(frameAfter.red - bareAfter.red), abs(frameAfter.blue - bareAfter.blue)) > 0.05f,
        )
        AppearanceTestSupport.capture(rule, "r10-immersive-restored-cards")
    }

    @Test
    fun withoutAWallpaperEveryPanelKeepsTheExactDefaultColor() {
        val probes = Probes()
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(settingsFor(wallpaper = null, immersive = true)) {
                    val scheme = MaterialTheme.colorScheme
                    // @Composable getter 先取值，再在 SideEffect 里写采样。
                    val frame = chatFrame(scheme.surfaceContainerLow)
                    val base = chatPanel(scheme.surfaceContainerHigh)
                    val glass = chatPanelStrong(scheme.surfaceContainerLow)
                    val immersive = LocalImmersiveChat.current
                    val wallpaperActive = LocalWallpaperActive.current
                    SideEffect {
                        probes.immersive = immersive
                        probes.active = wallpaperActive
                        probes.frame = frame
                        probes.base = base
                        probes.glass = glass
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("没有壁纸就不该进入沉浸", false, probes.immersive)
            assertEquals("没有壁纸时消息外框必须原样", lightScheme.surfaceContainerLow, probes.frame)
            assertEquals("没有壁纸时正文底必须原样", lightScheme.surfaceContainerHigh, probes.base)
            assertEquals("没有壁纸时顶部/输入区必须原样", lightScheme.surfaceContainerLow, probes.glass)
        }
        val shot = rule.onRoot().captureToImage()
        AppearanceTestSupport.assertNear(
            AppearanceTestSupport.sample(shot, 0.5f, 0.5f),
            lightScheme.background,
            0.02f,
            "没有壁纸时页面底色不该变",
        )
        AppearanceTestSupport.capture(rule, "r10-no-wallpaper-default")
    }

    @Test
    fun immersiveTopEdgeLetsTheWallpaperThrough() {
        val wallpaper = AppearanceTestSupport.solid("r10-edge", android.graphics.Color.rgb(80, 120, 200))
        var immersiveOn by mutableStateOf(true)
        val probes = Probes()
        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(settingsFor(wallpaper, immersiveOn)) {
                    val active = LocalWallpaperActive.current
                    val scheme = MaterialTheme.colorScheme
                    // CompositionLocal.current 是 @Composable getter，先取值再进 SideEffect。
                    val immersive = LocalImmersiveChat.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.active = active
                        probes.immersive = immersive
                        probes.plan = contrastPlan
                        probes.schemeBackground = scheme.background
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        awaitSampled(probes)
        val shot = rule.onRoot().captureToImage()
        val top = AppearanceTestSupport.sample(shot, 0.5f, 0.004f)
        val middle = AppearanceTestSupport.sample(shot, 0.5f, 0.5f)
        assertTrue("壁纸没露出来：$middle", middle.blue > middle.red)
        assertTrue(
            "顶部还被压成一块底色（大黑块）：$top vs $middle",
            maxOf(abs(top.red - middle.red), abs(top.blue - middle.blue)) > 0.05f,
        )
        AppearanceTestSupport.capture(rule, "r10-immersive-top-edge")

        rule.runOnIdle { immersiveOn = false }
        rule.waitForIdle()
        val restored = rule.onRoot().captureToImage()
        val restoredTop = AppearanceTestSupport.sample(restored, 0.5f, 0.004f)
        val background = requireNotNull(probes.schemeBackground)
        AppearanceTestSupport.assertNear(restoredTop, background, 0.06f, "关掉沉浸后 r9 的顶部压暗应该回来")
        AppearanceTestSupport.capture(rule, "r10-immersive-top-edge-r9")
    }

    @Test
    fun chatListCanvasNeverPaintsOverTheWallpaper() {
        val wallpaper = AppearanceTestSupport.solid("r10-canvas", android.graphics.Color.rgb(80, 120, 200))
        val settings = settingsFor(wallpaper, immersive = true)
        val probes = Probes()
        rule.setContent {
            val conversation = remember {
                Conversation(assistantId = kotlin.uuid.Uuid.random(), title = "空会话", messageNodes = emptyList())
            }
            val listState: LazyListState = rememberLazyListState()
            val hazeState = remember { HazeState() }
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(settings) {
                    // CompositionLocal.current 只能在组合里读，SideEffect 里只做采样写入。
                    val wallpaperActive = LocalWallpaperActive.current
                    val immersive = LocalImmersiveChat.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.active = wallpaperActive
                        probes.immersive = immersive
                        probes.plan = contrastPlan
                    }
                    CompositionLocalProvider(LocalSettings provides settings) {
                        ChatList(
                            innerPadding = PaddingValues(0.dp),
                            conversation = conversation,
                            state = listState,
                            loading = false,
                            previewMode = false,
                            settings = settings,
                            hazeState = hazeState,
                        )
                    }
                }
            }
        }
        awaitSampled(probes)
        rule.onNodeWithTag(ChatListRootTag).assertIsDisplayed()
        val shot = rule.onRoot().captureToImage()
        val center = AppearanceTestSupport.sample(shot, 0.5f, 0.5f)
        assertTrue("消息画布自己铺了不透明底，壁纸没透出来：$center", center.blue > center.red + 0.05f)
        AppearanceTestSupport.capture(rule, "r10-chat-canvas-wallpaper")
    }
}
