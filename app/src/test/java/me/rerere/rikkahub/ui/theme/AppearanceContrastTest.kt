package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸上的文字对比度（纯函数，不需要设备）。
 *
 * 两条底线：
 * 1. 每个预置主题的正文色压在面板色上，无论壁纸是纯黑还是纯白，对比度都要 >= 4.5；
 * 2. 达不到的主题只能整块不透明，helper 不许"降一点透明度也算过"。
 */
class AppearanceContrastTest {

    private val extremes = listOf(Color.Black, Color.White)

    private fun assertReadable(label: String, surface: Color, text: Color) {
        val base = surfaceContrast(surface, text)
        assertTrue("$label 面板底色本身就只有 $base 对比度", base >= 4.5f)
        assertEquals("$label 请求 1.0 时不该再减透明度", 1f, safeSurfaceOpacity(surface, text, 1f), 0f)
        listOf(0.6f, 0.86f).forEach { requested ->
            val alpha = safeSurfaceOpacity(surface, text, requested)
            assertTrue("$label 忽略了请求值：$alpha < $requested", alpha >= requested - 1e-4f)
            assertTrue("$label 返回了非法透明度 $alpha", alpha >= 0f && alpha <= 1f)
            extremes.forEach { beneath ->
                val painted = surface.copy(alpha = alpha).compositeOver(beneath)
                val ratio = surfaceContrast(painted, text)
                assertTrue("$label 在 $beneath 上 alpha=$alpha 只剩 $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `every preset theme keeps readable text over both wallpaper extremes`() {
        assertTrue("预置主题没加载出来", PresetThemes.isNotEmpty())
        PresetThemes.forEach { preset ->
            assertReadable("${preset.id}/light", preset.standardLight.surface, preset.standardLight.onSurface)
            assertReadable("${preset.id}/dark", preset.standardDark.surface, preset.standardDark.onSurface)
            assertReadable("${preset.id}/light-bg", preset.standardLight.background, preset.standardLight.onBackground)
            assertReadable("${preset.id}/dark-bg", preset.standardDark.background, preset.standardDark.onBackground)
        }
    }

    @Test
    fun `translucency is raised instead of silently breaking contrast`() {
        // 深色面板 + 浅色文字：直接按 0.6 铺在纯白壁纸上会糊成一片，必须提高不透明度
        val surface = Color(0xFF202020)
        val text = Color(0xFFE0E0E0)
        val alpha = safeSurfaceOpacity(surface, text, requested = 0.6f)
        assertTrue("深色面板没有提高不透明度：$alpha", alpha > 0.6f)
        assertTrue("不该直接跳到全不透明：$alpha", alpha < 1f)
        extremes.forEach { beneath ->
            val painted = surface.copy(alpha = alpha).compositeOver(beneath)
            assertTrue("alpha=$alpha 在 $beneath 上对比度不足", surfaceContrast(painted, text) >= 4.5f)
        }
        // 请求 0 也不能真的给出 0：那样文字直接压在壁纸上
        assertTrue("请求 0 时直接把面板抹平了", safeSurfaceOpacity(surface, text, 0f) > 0f)
    }

    @Test
    fun `low contrast custom theme stays opaque and does not pretend to be readable`() {
        val surface = Color(0xFF7E7E7E)
        val text = Color(0xFF8A8A8A)
        assertTrue("这一对颜色的对比度本来就该不达标", surfaceContrast(surface, text) < 4.5f)

        // 达不到就整块不透明，而不是挑一个"看起来还行"的透明度
        assertEquals(1f, safeSurfaceOpacity(surface, text, requested = 0.6f), 0f)

        // 不透明的结果依旧不达标：helper 只是不再把情况变得更糟，并没有虚报合规
        extremes.forEach { beneath ->
            val painted = surface.copy(alpha = 1f).compositeOver(beneath)
            assertTrue("低对比主题被当成达标了", surfaceContrast(painted, text) < 4.5f)
        }
    }

    @Test
    fun `non finite and out of range opacity requests are clamped before use`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 4f).forEach { requested ->
            assertEquals("$requested 应退回全不透明", 1f, safeSurfaceOpacity(Color.White, Color.Black, requested), 0f)
        }
        val zero = safeSurfaceOpacity(Color.White, Color.Black, -1f)
        assertTrue("负数请求被夹到 0 之后不能再压过两极：$zero", zero > 0f && zero < 1f)
        extremes.forEach { beneath ->
            assertTrue(surfaceContrast(Color.White.copy(alpha = zero).compositeOver(beneath), Color.Black) >= 4.5f)
        }
    }

    @Test
    fun `surface contrast is symmetric and reads 21 to 1 for black on white`() {
        assertEquals(21f, surfaceContrast(Color.Black, Color.White), 0.01f)
        assertEquals(
            surfaceContrast(Color.White, Color.Black),
            surfaceContrast(Color.Black, Color.White),
            1e-4f,
        )
        assertEquals(1f, surfaceContrast(Color(0xFF336699), Color(0xFF336699)), 1e-4f)
    }
}
