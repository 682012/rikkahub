package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸聊天的面板预算（纯函数，不需要设备）。
 *
 * 三条底线：
 * 1. 正文在任何壁纸明暗下都要 >= 4.5:1 —— 保护不够就是坏；
 * 2. 任何情况下都不许退回 r9 的整块卡片（正文底 > [ImmersivePanels.MAX_SURFACE] 就算回归）；
 * 3. 同一份“面板强度”滑块在沉浸模式下只改动文字底厚度，不新开开关。
 */
class ImmersivePanelsTest {

    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    private fun backdrops(vararg luminance: Float): List<Color> = luminance.map { grayFromLuminance(it) }

    private fun panelBases(scheme: ColorScheme): List<Color> =
        listOf(scheme.surfaceContainerLow, scheme.surfaceContainerHigh, scheme.primaryContainer)

    private fun plan(scheme: ColorScheme, beneath: List<Color>, requestedScrim: Float = 0.2f): ImmersiveContrastPlan =
        immersiveContrastPlan(
            background = scheme.background,
            foreground = scheme.onSurface,
            beneath = beneath,
            requestedSurface = immersiveTextBase(0.86f),
            requestedScrim = requestedScrim,
            panelBases = panelBases(scheme),
        )

    private fun assertEveryPanelReadable(label: String, scheme: ColorScheme, plan: ImmersiveContrastPlan) {
        (listOf(scheme.background) + panelBases(scheme)).forEach { base ->
            assertTrue(
                "$label：$base 在 alpha=${plan.surface} 下读不清",
                immersivePanelReadable(base, scheme.onSurface, plan, plan.surface),
            )
        }
    }

    @Test
    fun `panel slider maps into the thin range instead of adding a new control`() {
        assertEquals(
            ImmersivePanels.MIN_TEXT_BASE,
            immersiveTextBase(AppearanceSettings.MIN_SURFACE_OPACITY),
            1e-4f,
        )
        assertEquals(ImmersivePanels.MAX_TEXT_BASE, immersiveTextBase(1f), 1e-4f)

        val samples = listOf(0.6f, 0.7f, 0.86f, 0.95f, 1f).map { immersiveTextBase(it) }
        assertEquals("映射必须单调，滑块才有方向感", samples.sorted(), samples)

        val default = immersiveTextBase(AppearanceSettings().surfaceOpacity)
        assertTrue(
            "默认值 $default 不该贴在最薄或最厚那头",
            default > ImmersivePanels.MIN_TEXT_BASE && default < ImmersivePanels.MAX_TEXT_BASE,
        )

        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, 9f).forEach { value ->
            val mapped = immersiveTextBase(value)
            assertTrue("$value 映射成了非法值 $mapped", mapped in ImmersivePanels.MIN_TEXT_BASE..ImmersivePanels.MAX_TEXT_BASE)
        }
    }

    @Test
    fun `every wallpaper tone keeps every panel readable and stays under the card ceiling`() {
        val tones = listOf(0f, 0.02f, 0.18f, 0.35f, 0.55f, 0.8f, 0.95f, 1f)
        for (scheme in listOf(light, dark)) {
            for (low in tones) {
                for (high in tones) {
                    if (high < low) continue
                    val plan = plan(scheme, backdrops(low, high))
                    assertTrue("scrim ${plan.scrim} 越界", plan.scrim in 0f..ImmersivePanels.MAX_SCRIM)
                    assertTrue(
                        "surface ${plan.surface} 越界",
                        plan.surface in ImmersivePanels.MIN_TEXT_BASE..ImmersivePanels.MAX_SURFACE,
                    )
                    assertTrue("用户选的遮罩被调薄了", plan.scrim >= 0.2f - 1e-5f)
                    assertEveryPanelReadable("壁纸 $low..$high", scheme, plan)
                    assertEquals(
                        "薄玻璃只比正文底厚一点点，但不到整块卡片",
                        true,
                        plan.glass >= plan.surface && plan.glass <= ImmersiveContrastPlan.MAX_GLASS,
                    )
                }
            }
        }
    }

    @Test
    fun `a bright wallpaper behind a dark theme gets dimmed instead of becoming a card`() {
        val plan = plan(dark, backdrops(0.55f, 0.95f), requestedScrim = 0f)
        assertTrue("亮壁纸 + 深色主题必须压暗：${plan.scrim}", plan.scrim > 0.1f)
        assertTrue("压到上限也不该把壁纸盖没：${plan.scrim}", plan.scrim < 0.9f)
        assertTrue("文字底不该靠加厚硬顶：${plan.surface}", plan.surface < ImmersivePanels.MAX_SURFACE)
        assertEveryPanelReadable("亮壁纸/深色主题", dark, plan)
    }

    @Test
    fun `a dark wallpaper in a dark theme needs almost no scrim`() {
        val plan = plan(dark, backdrops(0.01f, 0.08f), requestedScrim = 0f)
        assertTrue("暗壁纸不该被额外压暗：${plan.scrim}", plan.scrim <= 0.05f)
        assertTrue("暗壁纸 + 浅字本来就是好底子，正文底不用很厚：${plan.surface}", plan.surface <= 0.4f)
        assertEveryPanelReadable("暗壁纸/深色主题", dark, plan)
    }

    @Test
    fun `a dark wallpaper in a light theme is never brightened just to help the text`() {
        // 浅色主题压黑照片：正确做法是加厚文字底，而不是给整张照片盖一层白雾。
        val plan = plan(light, backdrops(0.01f, 0.05f), requestedScrim = 0f)
        assertEquals("壁纸被提亮了：scrim=${plan.scrim}", 0f, plan.scrim, 1e-5f)
        assertTrue("文字底应该加厚到能读：${plan.surface}", plan.surface > ImmersivePanels.MIN_TEXT_BASE)
        assertTrue("加厚过头就成卡片了：${plan.surface}", plan.surface <= ImmersivePanels.MAX_SURFACE)
        assertEveryPanelReadable("黑壁纸/浅色主题", light, plan)
    }

    @Test
    fun `a bright wallpaper in a light theme keeps the wallpaper bright`() {
        val plan = plan(light, backdrops(0.55f, 0.9f), requestedScrim = 0f)
        assertTrue("浅色主题 + 亮壁纸本来就好读，不该再压暗：${plan.scrim}", plan.scrim <= 0.05f)
        assertEveryPanelReadable("亮壁纸/浅色主题", light, plan)
    }

    @Test
    fun `every panel gets its own thin alpha that is still readable`() {
        for (scheme in listOf(light, dark)) {
            val plan = plan(scheme, backdrops(0.05f, 0.7f))
            panelBases(scheme).forEach { base ->
                val alpha = immersiveSurfaceAlpha(base, scheme.onSurface, plan, plan.surface)
                assertTrue("$base 的面板透明度 $alpha 超了卡片上限", alpha <= ImmersivePanels.MAX_SURFACE + 1e-5f)
                assertTrue("$base 在 alpha=$alpha 下读不清", immersivePanelReadable(base, scheme.onSurface, plan, alpha))
            }
        }
    }

    @Test
    fun `user supplied bubble alpha is treated as a ceiling`() {
        val plan = plan(light, backdrops(0f, 1f), requestedScrim = 0f)
        // 调用方自己带了 0.2 的 alpha：沉浸模式不该替他加厚到 0.55
        val ceiling = 0.2f
        val alpha = immersiveSurfaceAlpha(
            base = light.primaryContainer,
            foreground = light.onSurface,
            plan = plan,
            requested = minOf(plan.surface, ceiling),
            maxSurface = ceiling,
        )
        assertTrue("自己带的薄 alpha 被覆盖了：$alpha", alpha <= ceiling + 1e-5f)
    }

    @Test
    fun `missing sample falls back to the worst case, not to a lucky guess`() {
        for (scheme in listOf(light, dark)) {
            val unknown = plan(scheme, emptyList(), requestedScrim = 0f)
            listOf(Color.Black, Color.White).forEach { extreme ->
                val painted = scheme.background.copy(alpha = unknown.surface)
                    .compositeOver(unknown.scrimColor.copy(alpha = unknown.scrim).compositeOver(extreme))
                val ratio = surfaceContrast(painted, scheme.onSurface)
                assertTrue("没采到壁纸明暗时在 $extreme 上只剩 $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `non finite requests never produce non finite alphas`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            val plan = immersiveContrastPlan(
                background = dark.background,
                foreground = dark.onSurface,
                beneath = backdrops(0.1f, 0.6f),
                requestedSurface = bad,
                requestedScrim = bad,
                panelBases = panelBases(dark),
            )
            assertTrue("surface ${plan.surface}", plan.surface.isFinite() && plan.surface in 0f..ImmersivePanels.MAX_SURFACE)
            assertTrue("scrim ${plan.scrim}", plan.scrim.isFinite() && plan.scrim in 0f..ImmersivePanels.MAX_SCRIM)
            assertTrue(plan.glass.isFinite())
        }
    }

    @Test
    fun `immersive mask thins the endcaps without losing the status bar band`() {
        val base = Color(0xFF101014)
        val r9 = wallpaperProtectionStops(0.2f, immersive = false, base = base)
        assertEquals(listOf(0f, 0.10f, 0.32f, 0.78f, 1f), r9.map { it.first })
        assertEquals("r9 顶部 0.96 的 8-bit alpha 不能变", 245f / 255f, r9.first().second.alpha, 0f)
        assertEquals("r9 底部 0.96 的 8-bit alpha 不能变", 245f / 255f, r9.last().second.alpha, 0f)

        val immersive = wallpaperProtectionStops(0.2f, immersive = true, base = base)
        assertTrue("顶部还留着大黑块：${immersive.first().second.alpha}", immersive.first().second.alpha <= 0.5f)
        assertTrue("底部还留着大黑块：${immersive.last().second.alpha}", immersive.last().second.alpha <= 0.55f)
        immersive.filter { it.first in 0.06f..0.80f }.forEach {
            assertEquals("中段必须是均匀的整层压暗", 0.2f, it.second.alpha, 0f)
        }
        val heavier = wallpaperProtectionStops(0.6f, immersive = true, base = base)
        assertTrue("压暗要跟着用户滑块走", heavier.first().second.alpha > immersive.first().second.alpha)
    }

    @Test
    fun `wallpaper sample reads both luminance ends and ignores transparent pixels`() {
        val pixels = IntArray(100) { index ->
            if (index < 60) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
        }
        val sample = wallpaperSample(pixels) ?: error("应该采得到")
        assertTrue("暗端 ${sample.darkLuminance}", sample.darkLuminance < 0.05f)
        assertTrue("亮端 ${sample.lightLuminance}", sample.lightLuminance > 0.8f)
        assertEquals("两端顺序不能反", sample.darkLuminance <= sample.lightLuminance, true)

        assertNull("全透明图不该给出结论", wallpaperSample(IntArray(16) { 0x00123456 }))
        assertNull("空图不该给出结论", wallpaperSample(IntArray(0)))
    }

    @Test
    fun `input bottom scrim turns into a thin haze only in immersive mode`() {
        // r9：没壁纸就是原强度，有壁纸压到 0.6
        assertEquals(1f, immersiveInputScrim(1f, hasWallpaper = false, immersive = false), 1e-4f)
        assertEquals(0.6f, immersiveInputScrim(1f, hasWallpaper = true, immersive = false), 1e-4f)
        // 沉浸：只剩一层薄雾，绝不能比 r9 更重
        val immersive = immersiveInputScrim(1f, hasWallpaper = true, immersive = true)
        assertEquals(IMMERSIVE_INPUT_SCRIM, immersive, 1e-4f)
        assertTrue("沉浸模式反而把输入区压得更暗了：$immersive", immersive < 0.6f)
        assertEquals(immersiveInputScrim(0.55f, true, true) < immersiveInputScrim(0.55f, true, false), true)
        assertEquals("坏值按默认强度处理", IMMERSIVE_INPUT_SCRIM, immersiveInputScrim(Float.NaN, hasWallpaper = true, immersive = true), 1e-4f)
    }

    @Test
    fun `luminance round trips through gray colors`() {
        listOf(0f, 0.05f, 0.2f, 0.5f, 0.9f, 1f).forEach { value ->
            val roundTrip = grayFromLuminance(value).luminance()
            // sRGB Color stores 8-bit channels; half a channel step maps to at most ~0.0045 luminance.
            assertEquals("$value 反解后亮度漂了", value, roundTrip, 5e-3f)
        }
    }
}
