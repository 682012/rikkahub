package me.rerere.rikkahub.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r10 沉浸聊天的“对抗”视觉回归：专门挑会翻车的输入，而不是再走一遍顺风局。
 *
 * 与已有用例的分工（不重复）：
 * - [ImmersiveChatUiTest] 用中蓝色壁纸走通“卡片消失 + 薄面板”的主干，并检查无壁纸时的精确原色；
 * - [AppearanceIntegrationTest] 走设置卡片、壁纸预览、坏壁纸的**冷启动**回退；
 * - r9 的 `MessageAlignmentUiTest` 已经覆盖了折叠容器内部 8 种对齐，这里只在端到端的 [ChatMessage] 上补 RTL；
 * - 本文件是四组新的对抗点：
 *   1. [splitBlackWhiteWallpaperKeepsRenderedContrastInBothThemes]
 *      一张图同时含近黑与近白两半 → 一个屏幕上同时出现两种最坏底色：浅色主题的深字压在黑半、深色主题的浅字压在白半。
 *      断言**屏幕真实像素**与正文色的对比度 >= 4.5，且壁纸没被压成一块纯色。
 *   2. [wallpaperFailureAfterASuccessLeavesOpaquePages]
 *      “先成功、后坏掉”的状态迁移：已经解锁的半透明页面必须收回去，旧壁纸不许留残影（冷启动用例覆盖不到）。
 *   3. [deepFontScaleRtlKeepsUserAtEndAndAssistantAtStartWithoutTheOuterCard]
 *      深色主题 + 1.3 倍字号 + RTL，跑**生产 ChatMessage**：用户走 End、助手走 Start（RTL 下 End 是物理左侧），
 *      并用“消息自己的空白侧像素 == 同一高度的裸壁纸像素”证明那条外大卡片真的没了，关掉沉浸再回来。
 *   4. [removingTheWallpaperDropsTheBackdropWithoutRemnant]
 *      从沉浸态**撤掉**壁纸：页面底色与面板色必须回到主题原值，屏幕上不许留旧壁纸的残影。
 *
 * 关键约束：
 * - 断言取的是屏幕像素和真实布局边界，不是“某个 Text 节点存在”；
 * - 面板色一律来自生产 helper（[chatFrame] / [chatPanel] / [chatPanelStrong]），不自己造颜色；
 * - 关掉 `wallpaperAdaptiveTint` 只是把“按壁纸色调微调主题”这个变量摘掉，好拿未着色的 onSurface/background 当参照。
 *
 * ChatMessage 外框现状：目前 ChatMessage 用 `wallpaperPanel`（有壁纸且非沉浸才垫卡片）实现同样的语义，
 * 这里断言的是**像素结果**，所以主代理把这段换成 `chatFrame(...)` 之后用例照样成立，
 * 并且能拦住“换实现时又把卡片带回来”。
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveAdversarialR10Test {

    @get:Rule
    val rule = createComposeRule()

    private val lightScheme = lightColorScheme()
    private val darkScheme = darkColorScheme()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val assistant = Assistant(name = "r10 adversarial")
    private val navigator = Navigator(mutableListOf())

    // ------------------------------------------------------------------ 素材

    /**
     * 左右各一半的极端壁纸：左近黑、右近白。
     *
     * 刻意不用纯 0x000000/0xFFFFFF：纯极端的采样灰度正好落在 0 和 1 上，就和“还没采样、按最坏情况兜底”
     * 分不开了；近黑近白既是最坏对比度，又能让用例等到“采样真的完成”。
     */
    private fun splitWallpaper(name: String, dark: Int = 0x0E0E0E, light: Int = 0xF2F2F2): String {
        val dir = File(context.cacheDir, "rk-r10-adversarial").apply { mkdirs() }
        val file = File(dir, "$name.png")
        val w = 240
        val h = 480
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = dark
        canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
        paint.color = light
        canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), paint)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        assertTrue("壁纸没写出来: ${file.absolutePath}", file.length() > 0)
        return Uri.fromFile(file).toString()
    }

    private fun missingWallpaper(): String =
        Uri.fromFile(File(context.cacheDir, "r10-adversarial-missing-${System.nanoTime()}.png")).toString()

    // ------------------------------------------------------------------ 设置

    private fun settingsFor(appearance: AppearanceSettings): Settings =
        Settings.dummy().copy(displaySetting = DisplaySetting(appearance = appearance))

    private fun wallpaperSettings(
        wallpaper: String?,
        immersiveChat: Boolean = true,
        surfaceOpacity: Float = 0.86f,
    ): Settings = settingsFor(
        AppearanceSettings(
            wallpaper = wallpaper,
            immersiveWallpaper = true,
            wallpaperOpacity = 1f,
            wallpaperScrim = 0f,
            surfaceOpacity = surfaceOpacity,
            wallpaperBlur = 0f,
            wallpaperAdaptiveTint = false,
            immersiveChat = immersiveChat,
        )
    )

    // ------------------------------------------------------------------ 探针与像素工具

    private class Probes {
        var active: Boolean? = null
        var immersive: Boolean? = null
        var plan: ImmersiveContrastPlan? = null
        var background: Color? = null
        var onSurface: Color? = null
        var frame: Color? = null
        var base: Color? = null
        var glass: Color? = null
        var page: Color? = null
    }

    /** 等到壁纸真的解码 + 采样完成：采样前预算按纯黑/纯白兜底，直接截图会和真实壁纸对不上。 */
    private fun awaitSampled(probes: Probes) {
        rule.waitUntil(20_000) { probes.active == true }
        rule.waitUntil(20_000) { probes.immersive == true }
        rule.waitUntil(20_000) {
            val backdrops = probes.plan?.backdrops
            !backdrops.isNullOrEmpty() && backdrops.none { it.red <= 0.001f || it.red >= 0.999f }
        }
    }

    private fun delta(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun rel(value: Dp, start: Dp, span: Dp): Float = (value - start) / span

    /** 把 dp 坐标换算成整屏截图里的相对位置。 */
    private fun at(x: Dp, y: Dp, root: DpRect): Pair<Float, Float> =
        rel(x, root.left, root.right - root.left) to rel(y, root.top, root.bottom - root.top)

    private fun midY(bounds: DpRect): Dp = bounds.top + (bounds.bottom - bounds.top) / 2f

    private fun midX(bounds: DpRect): Dp = bounds.left + (bounds.right - bounds.left) / 2f

    // ================================================================== 1. 黑白极端壁纸的对比度

    /**
     * 一张左右分割的壁纸（左近黑、右近白）+ 两套主题：一屏之内同时出现两种最坏组合。
     *
     * 断言：
     * - 屏幕真实像素上，正文薄底与薄玻璃对正文色的对比度 >= 4.5（容差 4.35 留给 8bit 取整）；
     * - 消息外框（[chatFrame]）位置的像素与同一高度的裸壁纸一致 → 外大卡片没有偷偷回来；
     * - 两半的裸壁纸像素仍明显不同，且至少半边与主题底色可分辨 → 壁纸没被压成一块纯色；
     * - 预算自己的压暗与文字底都不超过 [ImmersivePanels] 的上限。
     */
    @Test
    fun splitBlackWhiteWallpaperKeepsRenderedContrastInBothThemes() {
        val wallpaper = splitWallpaper("r10-adv-split-bw")
        var dark by mutableStateOf(false)
        val probes = Probes()

        rule.setContent {
            val scheme = if (dark) darkScheme else lightScheme
            MaterialExpressiveTheme(colorScheme = scheme) {
                AppAppearanceHost(wallpaperSettings(wallpaper)) {
                    val current = MaterialTheme.colorScheme
                    val frame = chatFrame(current.surfaceContainerLow)
                    val base = chatPanel(current.surfaceContainerHigh)
                    val glass = chatPanelStrong(current.surfaceContainerLow)
                    // CompositionLocal.current 是 @Composable getter，只能在组合里读。
                    val wallpaperActive = LocalWallpaperActive.current
                    val immersiveChat = LocalImmersiveChat.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.active = wallpaperActive
                        probes.immersive = immersiveChat
                        probes.plan = contrastPlan
                        probes.background = current.background
                        probes.onSurface = current.onSurface
                        probes.frame = frame
                        probes.base = base
                        probes.glass = glass
                    }
                    // 八个等宽竖条，左右各四条：裸壁纸 / 外框 / 正文薄底 / 薄玻璃
                    Row(Modifier.fillMaxSize()) {
                        repeat(2) {
                            Box(Modifier.weight(1f).fillMaxHeight())
                            Box(Modifier.weight(1f).fillMaxHeight().background(frame))
                            Box(Modifier.weight(1f).fillMaxHeight().background(base))
                            Box(Modifier.weight(1f).fillMaxHeight().background(glass))
                        }
                    }
                }
            }
        }
        awaitSampled(probes)

        fun probeColumns(shot: ImageBitmap, name: String) {
            fun px(x: Float) = AppearanceTestSupport.sample(shot, x, 0.5f)
            val bareDark = px(0.0625f)
            val frameDark = px(0.1875f)
            val baseDark = px(0.3125f)
            val glassDark = px(0.4375f)
            val bareLight = px(0.5625f)
            val frameLight = px(0.6875f)
            val baseLight = px(0.8125f)
            val glassLight = px(0.9375f)

            val onSurface = requireNotNull(probes.onSurface) { "没读到正文色" }
            fun assertReadable(pixel: Color, what: String) {
                val value = surfaceContrast(pixel, onSurface)
                assertTrue(
                    "$name：$what 在屏幕上只剩 $value:1（像素 $pixel / 正文 $onSurface）",
                    value >= 4.35f,
                )
            }
            assertReadable(baseDark, "近黑半的正文薄底")
            assertReadable(glassDark, "近黑半的薄玻璃")
            assertReadable(baseLight, "近白半的正文薄底")
            assertReadable(glassLight, "近白半的薄玻璃")

            // 外大卡片：外框位置必须和同高度的裸壁纸同像素
            AppearanceTestSupport.assertNear(
                frameDark, bareDark, 0.03f,
                "$name：近黑半的外框位置还压着一层卡片，壁纸没透出来",
            )
            AppearanceTestSupport.assertNear(
                frameLight, bareLight, 0.03f,
                "$name：近白半的外框位置还压着一层卡片，壁纸没透出来",
            )
            // 但薄底/薄玻璃得看得见，否则等于没铺
            assertTrue("$name：正文薄底和裸壁纸一模一样，等于没铺", delta(baseDark, bareDark) > 0.008f)
            assertTrue("$name：薄玻璃和裸壁纸一模一样，等于没铺", delta(glassDark, bareDark) > 0.008f)

            // 壁纸本身还在：两半仍有明显差别，且至少半边不是主题底色
            assertTrue(
                "$name：左右两半被压成了同一个色，壁纸已不可辨认（$bareDark / $bareLight）",
                delta(bareDark, bareLight) > 0.05f,
            )
            val background = requireNotNull(probes.background) { "没读到主题底色" }
            assertTrue(
                "$name：整屏都成了主题底色，壁纸被压没了（$bareDark / $bareLight / $background）",
                delta(bareDark, background) > 0.02f || delta(bareLight, background) > 0.02f,
            )
        }

        // 浅色主题：近黑半是最坏组合
        rule.waitUntil(20_000) { probes.plan?.scrimColor == lightScheme.background }
        probeColumns(rule.onRoot().captureToImage(), "浅色主题")
        rule.runOnIdle {
            val plan = requireNotNull(probes.plan) { "沉浸预算丢了" }
            assertTrue("压暗超过预算上限：${plan.scrim}", plan.scrim <= ImmersivePanels.MAX_SCRIM + 1e-4f)
            assertTrue("文字底超过预算上限：${plan.surface}", plan.surface <= ImmersivePanels.MAX_SURFACE + 1e-4f)
        }
        AppearanceTestSupport.capture(rule, "r10-adversarial-split-bw-light")

        // 深色主题：近白半是最坏组合
        rule.runOnIdle { dark = true }
        rule.waitUntil(20_000) { probes.plan?.scrimColor == darkScheme.background }
        rule.waitForIdle()
        probeColumns(rule.onRoot().captureToImage(), "深色主题")
        rule.runOnIdle {
            assertTrue(
                "深色主题读到的正文色不是浅色：${probes.onSurface}",
                requireNotNull(probes.onSurface).luminance() > 0.3f,
            )
        }
        AppearanceTestSupport.capture(rule, "r10-adversarial-split-bw-dark")
    }

    // ================================================================== 2. 先成功、后加载失败

    /**
     * 壁纸先成功（页面透明、壁纸可见），再换成一张坏图。
     *
     * 冷启动的坏壁纸用例只能证明“一开始就没解锁”；这里要证明**已经解锁过之后**必须收回去：
     * 沉浸关掉、预算清空、页面底色不透明且等于主题底色、面板回到原色，屏幕上不许残留上一张壁纸的蓝。
     */
    @Test
    fun wallpaperFailureAfterASuccessLeavesOpaquePages() {
        val good = AppearanceTestSupport.solid("r10-adv-blue", AndroidColor.rgb(40, 80, 220))
        val broken = missingWallpaper()
        var source by mutableStateOf(good)
        val probes = Probes()

        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(wallpaperSettings(source)) {
                    val current = MaterialTheme.colorScheme
                    val base = chatPanel(current.surfaceContainerHigh)
                    val page = LocalPageContainer.current
                    // 先取值再进 SideEffect：`current` 是 @Composable getter。
                    val wallpaperActive = LocalWallpaperActive.current
                    val immersiveChat = LocalImmersiveChat.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.active = wallpaperActive
                        probes.immersive = immersiveChat
                        probes.plan = contrastPlan
                        probes.background = current.background
                        probes.base = base
                        probes.page = page
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        awaitSampled(probes)

        val loaded = rule.onRoot().captureToImage()
        val visible = AppearanceTestSupport.sample(loaded, 0.5f, 0.5f)
        assertTrue("成功载入后壁纸没画出来：$visible", visible.blue > visible.red + 0.08f)
        rule.runOnIdle {
            assertEquals("成功载入后页面容器应该透明", Color.Transparent, probes.page)
            assertTrue("成功载入后正文底应该变薄", requireNotNull(probes.base).alpha < 1f)
        }
        AppearanceTestSupport.capture(rule, "r10-adversarial-before-failure")

        // 换成坏图：onLoaded(false) 必须把已经解锁的一切收回去
        rule.runOnIdle { source = broken }
        rule.waitUntil(20_000) { probes.active == false }
        rule.waitUntil(20_000) { probes.page == lightScheme.background }

        rule.runOnIdle {
            assertEquals("加载失败后还留在沉浸模式", false, probes.immersive)
            assertNull("加载失败后沉浸预算没清空", probes.plan)
            val page = requireNotNull(probes.page) { "页面容器丢了" }
            assertEquals("加载失败后页面容器必须是主题底色", lightScheme.background, page)
            assertEquals("加载失败后页面容器必须完全不透明", 1f, page.alpha, 0f)
            val base = requireNotNull(probes.base) { "面板色丢了" }
            assertEquals("加载失败后正文底必须回到原色", lightScheme.surfaceContainerHigh, base)
            assertEquals("加载失败后正文底必须完全不透明", 1f, base.alpha, 0f)
        }

        val after = rule.onRoot().captureToImage()
        listOf(0.2f, 0.5f, 0.8f).forEach { x ->
            val pixel = AppearanceTestSupport.sample(after, x, 0.5f)
            AppearanceTestSupport.assertNear(
                pixel, lightScheme.background, 0.02f,
                "x=$x 处加载失败后没有落回主题底色（旧壁纸/半透明底还在）",
            )
            assertTrue("x=$x 处还残留上一张壁纸的蓝：$pixel", pixel.blue - pixel.red < 0.06f)
        }
        AppearanceTestSupport.capture(rule, "r10-adversarial-after-failure")
    }

    // ================================================================== 3. 深色 + 1.3 倍字号 + RTL 的真实消息

    /**
     * 用**生产 [ChatMessage]**（没有任何模仿布局）跑最挤的组合：深色主题、fontScale 1.3、RTL、红色壁纸、沉浸开。
     *
     * 断言：
     * - LTR：用户正文贴列结束边（物理右），助手正文贴列起始边（物理左）；
     * - RTL：End 换成物理左侧，用户正文贴左、助手正文贴右 → 语义是 End/Start，不是写死“右/左”；
     * - 1.3 倍字号下正文确实比 1.0 倍更高，且没溢出屏幕；
     * - 外大卡片：沉浸时消息空白侧的像素 == 同一高度的裸壁纸像素；关掉沉浸后同一位置被卡片盖住。
     *
     * 消息列只占屏宽 72%，右边留一条裸壁纸当参照：同一 y、同一个遮罩梯度，像素可以直接比。
     */
    @Test
    fun deepFontScaleRtlKeepsUserAtEndAndAssistantAtStartWithoutTheOuterCard() {
        val wallpaper = AppearanceTestSupport.solid("r10-adv-red", AndroidColor.RED)
        var rtl by mutableStateOf(false)
        var fontScale by mutableStateOf(1.3f)
        var immersiveOn by mutableStateOf(true)

        rule.setContent {
            val baseDensity = LocalDensity.current
            val settings = wallpaperSettings(wallpaper, immersiveChat = immersiveOn)
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalNavController provides navigator,
                    LocalSettings provides settings,
                ) {
                    AppAppearanceHost(settings) {
                        val immersiveNow = LocalImmersiveChat.current
                        SideEffect { probeImmersive = immersiveNow }
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(0.72f).fillMaxHeight().testTag(COLUMN_TAG)) {
                                Box(Modifier.fillMaxWidth().testTag(USER_TAG)) {
                                    ChatMessage(
                                        node = userNode,
                                        assistant = assistant,
                                        onFork = {}, onRegenerate = {}, onEdit = {},
                                        onShare = {}, onDelete = {}, onUpdate = {},
                                    )
                                }
                                Box(Modifier.fillMaxWidth().testTag(ASSISTANT_TAG)) {
                                    ChatMessage(
                                        node = assistantNode,
                                        assistant = assistant,
                                        // 助手的气泡上带 TTS 按钮，那个 Local 需要真实 Koin 服务；
                                        // 让最后一条消息处于“生成中”，操作条整体不组合，避免与外观无关的依赖。
                                        lastMessage = true,
                                        loading = true,
                                        onFork = {}, onRegenerate = {}, onEdit = {},
                                        onShare = {}, onDelete = {}, onUpdate = {},
                                    )
                                }
                            }
                            Box(Modifier.weight(0.28f).fillMaxHeight().testTag(BARE_TAG))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { probeImmersive == true }

        fun gap(a: Dp, b: Dp): Float = abs((a - b).value)

        val column = rule.onNodeWithTag(COLUMN_TAG).getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val userText = rule.onNodeWithText(USER_TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val assistantText = rule.onNodeWithText(ASSISTANT_TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val columnMid = column.left + (column.right - column.left) / 2f

        // LTR：用户走 End（物理右），助手走 Start（物理左）
        assertTrue(
            "LTR 用户正文右端 ${userText.right} 应贴列结束边 ${column.right}",
            gap(userText.right, column.right) <= 32f,
        )
        assertTrue("LTR 用户正文不该落在列左半边", userText.right > columnMid)
        assertTrue(
            "LTR 助手正文左端 ${assistantText.left} 应贴列起始边 ${column.left}",
            gap(assistantText.left, column.left) <= 32f,
        )
        assertTrue("LTR 助手正文不该落在列右半边", assistantText.left < columnMid)
        assertTrue("LTR 消息溢出屏幕底部：${userText.bottom} > ${root.bottom}", userText.bottom <= root.bottom + 0.5.dp)

        val largeUserHeight = (userText.bottom - userText.top).value

        // 1.3 倍字号必须真的更高（同样文案）
        rule.runOnIdle { fontScale = 1f }
        rule.waitForIdle()
        val smallUser = rule.onNodeWithText(USER_TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val smallUserHeight = (smallUser.bottom - smallUser.top).value
        assertTrue("1.3 倍字号下正文高 $largeUserHeight 应高于 1.0 倍 $smallUserHeight", largeUserHeight > smallUserHeight + 1f)

        // RTL：End 变成物理左侧
        rule.runOnIdle {
            fontScale = 1.3f
            rtl = true
        }
        rule.waitForIdle()
        val rtlColumn = rule.onNodeWithTag(COLUMN_TAG).getUnclippedBoundsInRoot()
        val rtlMid = rtlColumn.left + (rtlColumn.right - rtlColumn.left) / 2f
        val rtlUser = rule.onNodeWithText(USER_TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rtlAssistant = rule.onNodeWithText(ASSISTANT_TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "RTL 用户正文左端 ${rtlUser.left} 应贴列结束边（物理左）${rtlColumn.left}",
            gap(rtlUser.left, rtlColumn.left) <= 32f,
        )
        assertTrue("RTL 用户正文不该落在列右半边", rtlUser.left < rtlMid)
        assertTrue(
            "RTL 助手正文右端 ${rtlAssistant.right} 应贴列起始边（物理右）${rtlColumn.right}",
            gap(rtlAssistant.right, rtlColumn.right) <= 32f,
        )
        assertTrue("RTL 助手正文不该落在列左半边", rtlAssistant.right > rtlMid)

        // 外大卡片：用户消息的空白侧（RTL 下是物理右侧）应该就是壁纸
        val bareBox = rule.onNodeWithTag(BARE_TAG).getUnclippedBoundsInRoot()
        val userBox = rule.onNodeWithTag(USER_TAG).getUnclippedBoundsInRoot()
        val probeX = rtlColumn.right - 8.dp
        // 裸壁纸参照取它自己的中线：RTL 下 Row 会把这一条放到物理左侧，不能写死“右边那条”
        val bareX = midX(bareBox)
        val (immersiveUserX, immersiveUserY) = at(probeX, midY(userBox), root)
        val (immersiveBareX, immersiveBareY) = at(bareX, midY(userBox), root)
        val shot = rule.onRoot().captureToImage()
        val userFree = AppearanceTestSupport.sample(shot, immersiveUserX, immersiveUserY)
        val bareRef = AppearanceTestSupport.sample(shot, immersiveBareX, immersiveBareY)
        AppearanceTestSupport.assertNear(
            userFree, bareRef, 0.03f,
            "沉浸模式下消息自己的空白侧还压着一层卡片（外大卡片没去掉）",
        )
        assertTrue(
            "消息空白侧看不出是壁纸（红色没透出来）：$userFree",
            userFree.red > userFree.green + 0.06f && userFree.red > userFree.blue + 0.06f,
        )
        AppearanceTestSupport.capture(rule, "r10-adversarial-rtl-font1.3-immersive")

        // 关掉沉浸 → 同一位置必须被卡片重新盖住
        rule.runOnIdle { immersiveOn = false }
        rule.waitForIdle()
        rule.waitUntil(20_000) { probeImmersive == false }
        val restoredRoot = rule.onRoot().getUnclippedBoundsInRoot()
        val restoredUser = rule.onNodeWithTag(USER_TAG).getUnclippedBoundsInRoot()
        val (restoredUserX, restoredUserY) = at(probeX, midY(restoredUser), restoredRoot)
        val (restoredBareX, restoredBareY) = at(bareX, midY(restoredUser), restoredRoot)
        val restoredShot = rule.onRoot().captureToImage()
        val restoredFree = AppearanceTestSupport.sample(restoredShot, restoredUserX, restoredUserY)
        val restoredBare = AppearanceTestSupport.sample(restoredShot, restoredBareX, restoredBareY)
        assertTrue(
            "关掉沉浸后消息空白侧还透出壁纸（卡片没回来）：$restoredFree vs 裸壁纸 $restoredBare",
            delta(restoredFree, restoredBare) > 0.15f,
        )
        AppearanceTestSupport.capture(rule, "r10-adversarial-rtl-font1.3-restored-cards")
    }

    // ================================================================== 4. 撤掉壁纸

    /**
     * 从沉浸态直接把壁纸删掉（`wallpaper = null`）。
     *
     * “恢复原样”不是靠“顺手没画”，而是：
     * - 沉浸标志与预算都清空；
     * - 页面容器、正文底、外框、薄玻璃都精确等于主题原色（生产 helper 的恒等语义）；
     * - 屏幕上三个采样点都精确落回主题底色，原来最亮那半张壁纸的位置没有残影。
     */
    @Test
    fun removingTheWallpaperDropsTheBackdropWithoutRemnant() {
        val wallpaper = splitWallpaper("r10-adv-remove-bw")
        var source by mutableStateOf<String?>(wallpaper)
        val probes = Probes()

        rule.setContent {
            MaterialExpressiveTheme(colorScheme = lightScheme) {
                AppAppearanceHost(wallpaperSettings(source)) {
                    val current = MaterialTheme.colorScheme
                    val frame = chatFrame(current.surfaceContainerLow)
                    val base = chatPanel(current.surfaceContainerHigh)
                    val glass = chatPanelStrong(current.surfaceContainerLow)
                    val page = LocalPageContainer.current
                    // 先取值再进 SideEffect：`current` 是 @Composable getter。
                    val wallpaperActive = LocalWallpaperActive.current
                    val immersiveChat = LocalImmersiveChat.current
                    val contrastPlan = LocalImmersiveContrast.current
                    SideEffect {
                        probes.active = wallpaperActive
                        probes.immersive = immersiveChat
                        probes.plan = contrastPlan
                        probes.background = current.background
                        probes.frame = frame
                        probes.base = base
                        probes.glass = glass
                        probes.page = page
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        awaitSampled(probes)

        val before = rule.onRoot().captureToImage()
        val darkHalf = AppearanceTestSupport.sample(before, 0.15f, 0.5f)
        val lightHalf = AppearanceTestSupport.sample(before, 0.85f, 0.5f)
        assertTrue("载入后左右两半应该明显不同（$darkHalf / $lightHalf）", delta(darkHalf, lightHalf) > 0.05f)

        rule.runOnIdle { source = null }
        rule.waitUntil(20_000) { probes.immersive == false }
        rule.waitUntil(20_000) { probes.active == false }
        rule.waitUntil(20_000) { probes.page == lightScheme.background }

        rule.runOnIdle {
            assertNull("删掉壁纸后沉浸预算没清空", probes.plan)
            assertEquals("删掉壁纸后消息外框必须原样", lightScheme.surfaceContainerLow, probes.frame)
            assertEquals("删掉壁纸后正文底必须原样", lightScheme.surfaceContainerHigh, probes.base)
            assertEquals("删掉壁纸后薄玻璃必须原样", lightScheme.surfaceContainerLow, probes.glass)
            assertEquals("删掉壁纸后页面容器必须完全不透明", 1f, requireNotNull(probes.page).alpha, 0f)
        }

        val after = rule.onRoot().captureToImage()
        listOf(0.15f, 0.5f, 0.85f).forEach { x ->
            AppearanceTestSupport.assertNear(
                AppearanceTestSupport.sample(after, x, 0.5f), lightScheme.background, 0.02f,
                "x=$x 处删掉壁纸后没回到主题底色（有残影）",
            )
        }
        // 没有壁纸时整屏必须是平的：连上下两端也不许留着 r9 的压暗带
        AppearanceTestSupport.assertNear(
            AppearanceTestSupport.sample(after, 0.5f, 0.02f), lightScheme.background, 0.02f,
            "删掉壁纸后顶部还压着旧的壁纸遮罩",
        )
        AppearanceTestSupport.assertNear(
            AppearanceTestSupport.sample(after, 0.5f, 0.98f), lightScheme.background, 0.02f,
            "删掉壁纸后底部还压着旧的壁纸遮罩",
        )
        AppearanceTestSupport.capture(rule, "r10-adversarial-wallpaper-removed")
    }

    // ------------------------------------------------------------------ 消息素材

    private val userNode = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(USER_TEXT)))
        ),
        selectIndex = 0,
    )

    private val assistantNode = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(ASSISTANT_TEXT)))
        ),
        selectIndex = 0,
    )

    /** [LocalImmersiveChat] 只能在组合里读，用一个字段把状态捞出来。 */
    @Volatile
    private var probeImmersive: Boolean? = null

    private companion object {
        const val COLUMN_TAG = "r10-adv-column"
        const val USER_TAG = "r10-adv-user"
        const val ASSISTANT_TAG = "r10-adv-assistant"
        const val BARE_TAG = "r10-adv-bare"

        const val USER_TEXT = "hi r10 user"
        const val ASSISTANT_TEXT = "ok r10 assistant"
    }
}
