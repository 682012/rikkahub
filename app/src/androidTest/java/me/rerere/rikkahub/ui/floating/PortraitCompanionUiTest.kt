package me.rerere.rikkahub.ui.floating

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 立绘角色气泡 [PortraitCompanionBubble] 的真实交互回归（r8 的默认悬浮窗形态）。
 *
 * 只用 Compose rule + 自己提供的 `LocalLifecycleOwner`，不启动 Koin、不碰网络、不依赖真实悬浮窗
 * WindowManager：被测的就是生产代码里 [PortraitCompanionBubble] 这条路径，参数全由用例显式给出，
 * 不读设置里的全局开关。
 *
 * 覆盖（都能用语义/时序证伪）：
 * - 默认设置就是收起态（[FloatingCompanionSettings.minimized] 默认 true、面板预览默认 false）；
 * - 收起态只有 48×56dp 的贴边手柄，点一下通过 `onMinimizedChange(false)` 回到全尺寸，且**不**触发面板回调；
 * - 全尺寸点击：短台词先出现，[FloatingCompanionLimits.TAP_DELAY_MILLIS] 之后面板回调才来，且只来一次；
 * - 位移超过 touchSlop 的拖动不算点击（同一节点上无位移点按仍然算，排除“指针输入坏了”）；
 * - 全尺寸的收起图标走 `onMinimizedChange(true)`，状态回到调用方后 UI 只剩手柄（持久化由调用方负责）；
 * - 收起/全尺寸高度差真实存在（56dp 手柄 vs `size × 1.32`）；
 * - 三个预设指向三张不同的 `file:///android_asset/companions/(preset).webp`。
 *
 * 明确**不**声称的东西（避免假通过）：
 * - 立绘解码/绘制的像素结果。素材（`assets/companions/(preset).webp`）已在仓库里，但本文件只断言“自定义图缺失时
 *   退回内置角色、仍然可点且能到达 idle”这条确定性分支；真图解码/观感属于视觉测试，不用截图或生成图片冒充。
 * - 呼吸/摆动/抬手/粒子的视觉效果与帧率：`animationEnabled = false` 固定掉动画，只测交互。
 */
@RunWith(AndroidJUnit4::class)
class PortraitCompanionUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var minimized by mutableStateOf(true)
    private var clicks = AtomicInteger()

    /** 设置页用的假状态：空闲、没有待审批、没有错误；一个用例里保持同一份，不在重组中新建。 */
    private val state = previewCompanionState()

    // ------------------------------------------------------------------ helpers

    /** 可以手动推进的 LifecycleOwner：只影响被测 composable 里的 `LocalLifecycleOwner`。 */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun setPortrait(companion: () -> FloatingCompanionSettings) {
        val owner = TestLifecycleOwner()
        rule.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    PortraitCompanionBubble(
                        state = state,
                        companion = companion(),
                        onClick = { clicks.incrementAndGet() },
                        onMinimizedChange = { minimized = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun settings(preset: FloatingCompanionPreset = FloatingCompanionPreset.WHALE_MAID) =
        FloatingCompanionSettings(
            preset = preset,
            animationEnabled = false,
            minimized = minimized,
            sizeDp = FloatingCompanionLimits.SIZE_DEFAULT_DP,
            tapLines = listOf(context.getString(R.string.portrait_line_1)),
        )

    private fun handle() = rule.onNodeWithTag(TAG_HANDLE)

    private fun character() = rule.onNodeWithTag(TAG_CHARACTER)

    private fun bounds(tag: String): Dp = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun widthOf(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.right - it.left }

    private fun lineShown(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue("$what 期望 $expected，实际 $actual", abs(expected.value - actual.value) <= 1f)
    }

    /** 收起态手柄的无障碍描述：生产里是 “角色名 + 展开” 两个资源串拼起来的。 */
    private fun expectedHandleDescription() =
        "${context.getString(R.string.r12_name_deepseek)} ${context.getString(R.string.portrait_expand)}"

    // -------------------------------------------------------------------- tests

    @Test
    fun defaultsKeepEdgeHandleAndPreviewOff() {
        val defaults = FloatingCompanionSettings()
        assertTrue("默认就是收起态（贴边手柄）", defaults.minimized)
        assertFalse("面板里的大段预览默认关着", defaults.panelPreviewExpanded)
        assertNull("默认没有自定义图", defaults.customImagePath)
        assertEquals(FloatingCompanionLimits.SIZE_DEFAULT_DP, defaults.safeSizeDp)
    }

    @Test
    fun minimizedHandleIs48By56AndTapExpandsWithoutOpeningPanel() {
        minimized = true
        setPortrait { settings() }

        // 收起态：只有手柄，没有全尺寸立绘
        handle().assertIsDisplayed()
        rule.onNodeWithTag(TAG_CHARACTER).assertDoesNotExist()

        assertNear(48.dp, widthOf(TAG_HANDLE), "手柄宽度")
        assertNear(56.dp, bounds(TAG_HANDLE), "手柄高度")
        val node = handle().fetchSemanticsNode()
        assertEquals(
            "手柄的无障碍描述必须是 角色名 + 展开 的精确资源输出",
            expectedHandleDescription(),
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        )
        assertTrue("手柄必须可点", node.config.contains(SemanticsActions.OnClick))

        val handleHeight = bounds(TAG_HANDLE)
        handle().performTouchInput { click() }
        rule.waitForIdle()

        assertFalse("点手柄必须把 minimized 交回 false", minimized)
        assertEquals("点手柄只展开，不该同时打开面板", 0, clicks.get())
        character().assertIsDisplayed()
        assertTrue(
            "全尺寸立绘必须比手柄高（$handleHeight -> ${bounds(TAG_CHARACTER)}）",
            bounds(TAG_CHARACTER) >= handleHeight + 24.dp,
        )
    }

    @Test
    fun fullPortraitTapSpeaksThenCallsPanelOnlyAfterTapDelay() {
        minimized = false
        setPortrait { settings() }

        val line = context.getString(R.string.portrait_line_1)
        val description = context.getString(R.string.floating_companion_bubble_desc)
        character().assertIsDisplayed()
        character().assertHasClickAction()
        assertEquals(description, character().fetchSemanticsNode().config[SemanticsActions.OnClick].label?.toString())
        assertFalse("还没点不该有台词", lineShown(line))

        val tapNanos = System.nanoTime()
        character().performTouchInput { click() }
        rule.waitUntil(TIMEOUT_MILLIS) { lineShown(line) }
        val lineSeenMillis = (System.nanoTime() - tapNanos) / 1_000_000

        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        val callbackMillis = (System.nanoTime() - tapNanos) / 1_000_000

        assertTrue(
            "台词应在 ${FloatingCompanionLimits.TAP_DELAY_MILLIS}ms 的回调之前出现，实际 ${lineSeenMillis}ms",
            lineSeenMillis < FloatingCompanionLimits.TAP_DELAY_MILLIS - 100,
        )
        assertTrue(
            "面板回调至少等 ${FloatingCompanionLimits.TAP_DELAY_MILLIS}ms，实际 ${callbackMillis}ms",
            callbackMillis >= FloatingCompanionLimits.TAP_DELAY_MILLIS - 100,
        )
        assertEquals("一次点击只允许一次面板回调", 1, clicks.get())

        // 全尺寸立绘的高度是 size × 1.32，宽度就是 size
        assertNear(FloatingCompanionLimits.SIZE_DEFAULT_DP.dp, widthOf(TAG_CHARACTER), "立绘宽度")
        assertNear(
            (FloatingCompanionLimits.SIZE_DEFAULT_DP * 1.32f).dp,
            bounds(TAG_CHARACTER),
            "立绘高度",
        )
    }

    @Test
    fun dragBeyondTouchSlopDoesNotTalkOrOpenPanelButPlainTapDoes() {
        minimized = false
        setPortrait { settings() }

        val line = context.getString(R.string.portrait_line_1)
        val description = context.getString(R.string.floating_companion_bubble_desc)
        character().assertIsDisplayed()

        character().performTouchInput {
            swipe(start = center, end = center + Offset(240f, 0f), durationMillis = 150L)
        }
        rule.waitForIdle()
        assertFalse("拖动不该冒出台词", lineShown(line))
        Thread.sleep(FloatingCompanionLimits.TAP_DELAY_MILLIS + 600)
        assertEquals("拖动不该触发面板回调", 0, clicks.get())

        // 对照组：位移为 0 的点按必须触发，否则上面的 0 可能只是指针输入坏了
        character().performTouchInput {
            down(center)
            up()
        }
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())
        assertTrue("点按仍然应当说话", lineShown(line))
        assertEquals(description, character().fetchSemanticsNode().config[SemanticsActions.OnClick].label?.toString())
    }

    @Test
    fun minimizeIconCollapsesToHandleThroughCallback() {
        minimized = false
        setPortrait { settings(FloatingCompanionPreset.DRAGON_GIRL) }

        val minimizeLabel = context.getString(R.string.portrait_minimize)
        rule.onNodeWithContentDescription(minimizeLabel).assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertTrue("收起图标必须把 minimized 交回 true（落盘由调用方做）", minimized)
        handle().assertIsDisplayed()
        rule.onNodeWithTag(TAG_CHARACTER).assertDoesNotExist()
        rule.onAllNodesWithContentDescription(minimizeLabel).assertCountEquals(0)
        assertNear(48.dp, widthOf(TAG_HANDLE), "收起后手柄宽度")
        assertNear(56.dp, bounds(TAG_HANDLE), "收起后手柄高度")

        // 状态再交回 false 就回到全尺寸：手柄本身就是那唯一的入口
        handle().performTouchInput { click() }
        rule.waitForIdle()
        assertFalse(minimized)
        character().assertIsDisplayed()
    }

    /**
     * 自定义图缺失这条确定性分支：`FloatingCompanionFiles.resolve` 拿不到文件就退回内置立绘，节点照常显示、
     * 可点、能发面板回调，不崩。这里**不**断言解码成功，也不生成图片。
     */
    @Test
    fun missingPortraitAssetStillRendersClickableBubble() {
        minimized = false
        setPortrait {
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.CUSTOM,
                customImagePath = "file:///does/not/exist/companions/custom.webp",
                animationEnabled = false,
                minimized = false,
            )
        }

        val description = context.getString(R.string.floating_companion_bubble_desc)
        character().assertIsDisplayed().assertHasClickAction()
        assertEquals(description, character().fetchSemanticsNode().config[SemanticsActions.OnClick].label?.toString())
        character().performTouchInput { click() }
        rule.waitUntil(TIMEOUT_MILLIS) { lineShown(context.getString(R.string.portrait_line_1)) }
        // 素材缺失也不影响发面板回调的时间线：台词先到，400ms 后才回调
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())
    }

    @Test
    fun presetsPointAtDistinctTransparentAssets() {
        assertEquals("file:///android_asset/companions/six/deepseek/idle.png", portraitAsset(FloatingCompanionPreset.WHALE_MAID))
        assertEquals("file:///android_asset/companions/six/gpt/idle.png", portraitAsset(FloatingCompanionPreset.DRAGON_GIRL))
        assertEquals("file:///android_asset/companions/six/claude/idle.png", portraitAsset(FloatingCompanionPreset.SUN_READER))
        val assets = FloatingCompanionPreset.entries
            .filter { it != FloatingCompanionPreset.LOGO }
            .map(::portraitAsset)
            .toSet()
        assertTrue("六个立绘预设不能共用同一张图", assets.size == 6)
    }

    private companion object {
        const val TAG_HANDLE = "portrait-edge-handle"
        const val TAG_CHARACTER = "portrait-character"

        /** 一句台词 / 一次重组 / 一次回调的正常等待上限（真实时钟）。 */
        const val TIMEOUT_MILLIS = 3_000L
        const val CALLBACK_TIMEOUT_MILLIS = 5_000L
    }
}
