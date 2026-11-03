package me.rerere.rikkahub.ui.floating

import android.net.Uri
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

/**
 * 收起态角色气泡（[FloatingCompanionBubble]）的真实交互回归。
 *
 * 只用 Compose rule + 自己提供的 `LocalLifecycleOwner`，不启动 Koin、不碰网络、不依赖真实悬浮窗
 * WindowManager：被测的就是设置页预览走的那条生产路径（[FloatingCompanionBubble] 本体）。
 *
 * 覆盖（都能用语义/时序断言证伪）：
 * - 猫/龙两种内置角色都能点，点出来的台词来自 `stringArrayResourceFor` 里该预设的默认台词；
 * - 点击后短台词立刻出现，`FloatingCompanionLimits.TAP_DELAY_MILLIS` 之后回调才来，且只来一次；
 * - 连点被 `tapArmed` 挡住，不补发第二次会话回调，冷却完还能再点；
 * - 手指位移超过 touchSlop 的拖动不触发回调（同一节点上无位移的点按仍然触发，排除"指针输入坏了"）；
 * - 关掉动效后气泡照样可点、可达 idle（没有循环动画挂着，`waitForIdle` 不超时）；
 * - 生命周期不到 RESUMED 时（`animate` 分支被关掉）气泡仍然显示、可点；
 * - 自定义预设但图片缺失时退回内置角色，仍然显示、可点、会说话。
 *
 * 明确**不**断言的东西（避免用截图冒充证据）：
 * - 呼吸/摆尾/眨眼/粒子/抬手的视觉表现、帧率、以及"生命周期停止后动画真的停了"。这些只能靠像素
 *   或帧时钟间接推断，仪器测试里截图既不稳定也不能证明"循环停了"，所以这里一条都不声称。
 *   本文件对动效的唯一间接断言是"关掉动效的合成能到达 idle"，即没有无限循环动画在排帧。
 * - Coil 真的把自定义图片解码出来（自定义图只在"文件缺失"这条确定性分支上测；真图解码要真实
 *   素材与 ImageLoader，属于另开的视觉/解码测试）。
 * - LOGO 预设的气泡（由 `ChatFloatingBubble` -> `LogoChatFloatingBubble` 渲染，不在本文件范围），
 *   这里只断言"自定义没图时 effectivePreset 退回 LOGO"这条数据级事实。
 */
@RunWith(AndroidJUnit4::class)
class FloatingCompanionInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val description get() = context.getString(R.string.floating_companion_bubble_desc)

    // ------------------------------------------------------------------ helpers

    /** 可以手动推进的 LifecycleOwner：只影响被测 composable 里的 `LocalLifecycleOwner`。 */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun lifecycleOwnerAt(state: Lifecycle.State): TestLifecycleOwner {
        val owner = TestLifecycleOwner()
        // LifecycleRegistry 只允许在主线程改状态
        rule.runOnUiThread { owner.moveTo(state) }
        return owner
    }

    private fun setBubble(
        companion: () -> FloatingCompanionSettings,
        owner: LifecycleOwner,
        onClick: () -> Unit,
    ) {
        val state = previewCompanionState()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                    FloatingCompanionBubble(state = state, companion = companion(), onClick = onClick)
                }
            }
        }
    }

    /** 生产代码里该预设会说的话，资源里读出来，不硬编码文案。 */
    private fun defaultLineCandidates(preset: FloatingCompanionPreset): List<String> = when (preset) {
        FloatingCompanionPreset.DRAGON -> listOf(
            context.getString(R.string.floating_companion_default_line_dragon_1),
            context.getString(R.string.floating_companion_default_line_dragon_2),
        )

        FloatingCompanionPreset.CAT -> listOf(
            context.getString(R.string.floating_companion_default_line_cat_1),
            context.getString(R.string.floating_companion_default_line_cat_2),
        )

        // CUSTOM 和 LOGO 都走 stringArrayResourceFor 的 else 分支，只有一句猫台词
        else -> listOf(context.getString(R.string.floating_companion_default_line_cat_1))
    }

    private fun textShown(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun visibleLines(candidates: List<String>): List<String> = candidates.filter(::textShown)

    private fun bubbleNode() = rule.onNodeWithContentDescription(description)

    /** 语义层：内容描述、Role、onClick 的 label、可点尺寸，全部按生产值校验。 */
    private fun assertClickableBubble() {
        val node = bubbleNode()
        node.assertIsDisplayed()
        node.assertWidthIsAtLeast(FloatingCompanionLimits.MIN_TOUCH_TARGET_DP.dp)
        node.assertHeightIsAtLeast(FloatingCompanionLimits.MIN_TOUCH_TARGET_DP.dp)
        node.assertHasClickAction()
        val config = node.fetchSemanticsNode().config
        assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
        assertEquals(description, config[SemanticsActions.OnClick].label?.toString())
    }

    /** 点一下 → 该预设默认台词里恰好出现一句 → 回调恰好一次。 */
    private fun assertSpeaksDefaultLine(preset: FloatingCompanionPreset) {
        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        setBubble(
            { FloatingCompanionSettings(preset = preset, animationEnabled = false) },
            owner,
        ) { clicks.incrementAndGet() }
        rule.waitForIdle()

        assertClickableBubble()
        assertTrue("$preset 还没点就不该有台词", visibleLines(defaultLineCandidates(preset)).isEmpty())

        bubbleNode().performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { visibleLines(defaultLineCandidates(preset)).isNotEmpty() }
        assertEquals("$preset 一次点击只能出一句台词", 1, visibleLines(defaultLineCandidates(preset)).size)

        // 台词必须是这个预设的，不能是隔壁角色的
        val otherPreset = if (preset == FloatingCompanionPreset.DRAGON) {
            FloatingCompanionPreset.CAT
        } else {
            FloatingCompanionPreset.DRAGON
        }
        assertTrue(
            "$preset 显示了别的预设的台词: ${visibleLines(defaultLineCandidates(otherPreset))}",
            visibleLines(defaultLineCandidates(otherPreset)).isEmpty(),
        )

        rule.waitUntil(TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun catBubbleSpeaksItsDefaultLineOncePerTap() {
        assertSpeaksDefaultLine(FloatingCompanionPreset.CAT)
    }

    @Test
    fun dragonBubbleSpeaksItsDefaultLineOncePerTap() {
        assertSpeaksDefaultLine(FloatingCompanionPreset.DRAGON)
    }

    /** 短台词先来、callback 后到：这条是靠真实时间差断言的，不靠截图。 */
    @Test
    fun tapShowsShortLineLongBeforeThePanelCallback() {
        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        val line = "回归专用台词"
        setBubble(
            {
                FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.CAT,
                    tapLines = listOf(line),
                    animationEnabled = false,
                )
            },
            owner,
        ) { clicks.incrementAndGet() }
        rule.waitForIdle()
        assertClickableBubble()
        assertFalse("还没点就不该有台词", textShown(line))

        val clickNanos = System.nanoTime()
        bubbleNode().performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { textShown(line) }
        val lineSeenMs = (System.nanoTime() - clickNanos) / 1_000_000

        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        val callbackMs = (System.nanoTime() - clickNanos) / 1_000_000

        assertTrue(
            "台词应在 ${FloatingCompanionLimits.TAP_DELAY_MILLIS}ms 的 callback 之前出现，实际 ${lineSeenMs}ms",
            lineSeenMs < FloatingCompanionLimits.TAP_DELAY_MILLIS - 100,
        )
        assertTrue(
            "callback 至少等 ${FloatingCompanionLimits.TAP_DELAY_MILLIS}ms，实际 ${callbackMs}ms",
            callbackMs >= FloatingCompanionLimits.TAP_DELAY_MILLIS - 100,
        )
        assertTrue("callback 来得太晚: ${callbackMs}ms", callbackMs <= CALLBACK_TIMEOUT_MILLIS)
        assertEquals("第一次点击只允许一次 callback", 1, clicks.get())
    }

    /** 连点不补发 callback；冷却结束后重新武装。 */
    @Test
    fun rapidMultiTapCallsBackOnceThenRearms() {
        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        val line = "连点台词"
        setBubble(
            {
                FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.CAT,
                    tapLines = listOf(line),
                    animationEnabled = false,
                )
            },
            owner,
        ) { clicks.incrementAndGet() }
        rule.waitForIdle()
        assertClickableBubble()

        val armedWindowMillis =
            FloatingCompanionLimits.TAP_DELAY_MILLIS + FloatingCompanionLimits.TAP_FEEDBACK_MILLIS
        val startNanos = System.nanoTime()
        repeat(3) { bubbleNode().performClick() }
        val burstMillis = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue(
            "三次点击必须在重新武装窗口 ${armedWindowMillis}ms 内完成，实际 ${burstMillis}ms（环境太慢，这条前提没成立）",
            burstMillis < armedWindowMillis,
        )

        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() >= 1 }
        // 窗口 400+700=1100ms，多等一会儿确认不会补发
        Thread.sleep(armedWindowMillis + 500)
        assertEquals("连点只允许一次 callback", 1, clicks.get())

        // 冷却过后重新武装：还能再点一次
        bubbleNode().performClick()
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 2 }
        assertEquals(2, clicks.get())
    }

    /** 拖动超过 touchSlop 不算点击；同一节点上的无位移点按仍然算点击。 */
    @Test
    fun dragBeyondTouchSlopDoesNotCallBackButPlainTapDoes() {
        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        setBubble(
            { FloatingCompanionSettings(preset = FloatingCompanionPreset.CAT, animationEnabled = false) },
            owner,
        ) { clicks.incrementAndGet() }
        rule.waitForIdle()
        assertClickableBubble()

        bubbleNode().performTouchInput {
            swipe(start = center, end = center + Offset(240f, 0f), durationMillis = 150L)
        }
        rule.waitForIdle()
        assertTrue("拖动不该冒出台词", visibleLines(defaultLineCandidates(FloatingCompanionPreset.CAT)).isEmpty())
        Thread.sleep(FloatingCompanionLimits.TAP_DELAY_MILLIS + 800)
        assertEquals("拖动不该触发 callback", 0, clicks.get())

        // 对照组：位移为 0 的点按必须触发，否则上面的 0 可能只是指针输入坏了
        bubbleNode().performTouchInput {
            down(center)
            up()
        }
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())
    }

    /** 关掉动效仍响应；并且合成能到 idle（没有循环动画一直排帧）。 */
    @Test
    fun animationDisabledStaysResponsiveAndReachesIdle() {
        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        val line = "静态台词"
        setBubble(
            {
                FloatingCompanionSettings(
                    preset = FloatingCompanionPreset.DRAGON,
                    tapLines = listOf(line),
                    animationEnabled = false,
                )
            },
            owner,
        ) { clicks.incrementAndGet() }

        // 这条 waitForIdle 是"无循环动画"的间接证据：真有无限动画在跑，idle 就到不了
        rule.waitForIdle()
        assertClickableBubble()

        bubbleNode().performClick()
        rule.waitUntil(TIMEOUT_MILLIS) { textShown(line) }
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        rule.waitForIdle()
        assertEquals(1, clicks.get())
    }

    /**
     * 生命周期不到 RESUMED：生产代码里 `animate = animationEnabled && RESUMED` 会被关掉。
     * 这里只证明功能没坏（还显示、还能点、台词和 callback 都到），不声称动画"停了"——
     * 那是帧级的，截图证不了。
     */
    @Test
    fun lifecycleBelowResumedKeepsBubbleResponsive() {
        val owner = lifecycleOwnerAt(Lifecycle.State.CREATED)
        val clicks = AtomicInteger()
        setBubble(
            { FloatingCompanionSettings(preset = FloatingCompanionPreset.DRAGON) },
            owner,
        ) { clicks.incrementAndGet() }
        rule.waitForIdle()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        assertClickableBubble()
        bubbleNode().performClick()
        rule.waitUntil(TIMEOUT_MILLIS) {
            visibleLines(defaultLineCandidates(FloatingCompanionPreset.DRAGON)).isNotEmpty()
        }
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())

        // 生命周期停着也不能挂着循环动画
        rule.waitForIdle()
    }

    /** 自定义预设但图片缺失：退回内置角色，仍然显示、可点、会说话。 */
    @Test
    fun customPresetWithMissingImageFallsBackToAClickableBubble() {
        val missing = File(FloatingCompanionFiles.directory(context), "missing-${System.nanoTime()}.png")
        assertFalse("前提：这个文件不能存在", missing.exists())
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.CUSTOM,
            customImagePath = Uri.fromFile(missing).toString(),
            animationEnabled = false,
        )
        assertEquals("有路径就还是 CUSTOM", FloatingCompanionPreset.CUSTOM, settings.effectivePreset)
        assertNull(
            "文件不存在时 UI 拿不到可加载地址，只能走内置角色",
            FloatingCompanionFiles.resolve(context, settings.customImagePath),
        )

        val owner = lifecycleOwnerAt(Lifecycle.State.RESUMED)
        val clicks = AtomicInteger()
        setBubble({ settings }, owner) { clicks.incrementAndGet() }
        rule.waitForIdle()

        assertClickableBubble()
        bubbleNode().performClick()
        val expected = context.getString(R.string.floating_companion_default_line_cat_1)
        rule.waitUntil(TIMEOUT_MILLIS) { textShown(expected) }
        rule.waitUntil(CALLBACK_TIMEOUT_MILLIS) { clicks.get() == 1 }
        assertEquals(1, clicks.get())
    }

    /** 数据级兜底：自定义没图时 effectivePreset 就是 LOGO（那条路径由 ChatFloatingBubble 渲染）。 */
    @Test
    fun customPresetWithoutImageFallsBackToLogo() {
        assertEquals(FloatingCompanionPreset.LOGO, FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM).effectivePreset)
        assertEquals(
            FloatingCompanionPreset.LOGO,
            FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM, customImagePath = "   ").effectivePreset,
        )
        assertEquals(
            FloatingCompanionPreset.CUSTOM,
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.CUSTOM,
                customImagePath = "file:///data/user/0/me.rerere.rikkahub/files/floating_companions/a.png",
            ).effectivePreset,
        )
    }

    private companion object {
        /** 一句台词 / 一次重组 / 一次 callback 的正常等待上限。 */
        const val TIMEOUT_MILLIS = 3_000L

        /** 回调要等 400ms + 真实调度，留足余量但不至于挂死。 */
        const val CALLBACK_TIMEOUT_MILLIS = 5_000L
    }
}
