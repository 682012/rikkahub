package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置：老配置读出来要落在默认值上、坏浮点数要收敛、三种风格都要能原样存取。
 */
class AppearanceSettingsTest {

    private fun encodeAppearance(value: AppearanceSettings): String =
        JsonInstant.encodeToString(AppearanceSettings.serializer(), value)

    private fun encodeAppearanceObject(value: AppearanceSettings): JsonObject =
        JsonInstant.parseToJsonElement(encodeAppearance(value)).jsonObject

    private fun decodeAppearance(json: String): AppearanceSettings =
        JsonInstant.decodeFromString(AppearanceSettings.serializer(), json)

    @Test
    fun `old display setting without appearance keeps the defaults`() {
        // 旧版本落到磁盘的 displaySetting：没有 appearance 字段
        val legacyJson = """
            {
              "showUserAvatar": false,
              "bubbleOpacity": 0.8,
              "useStudioDisplay": false,
              "fontSizeRatio": 1.2,
              "conversationSortOrder": "create_time"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString(DisplaySetting.serializer(), legacyJson)

        assertEquals(AppearanceSettings(), decoded.appearance)
        assertEquals(AppearanceSettings().chatUiStyle, decoded.appearance.chatUiStyle)
        assertEquals(AppearanceSettings().surfaceOpacity, decoded.appearance.surfaceOpacity, 0f)
        // 旧字段本身不能被顺手改掉
        assertFalse(decoded.showUserAvatar)
        assertEquals(0.8f, decoded.bubbleOpacity, 0f)
        assertEquals(ConversationSortOrder.CREATE_TIME, decoded.conversationSortOrder)
    }

    @Test
    fun `appearance is written into display setting json so it survives a restart`() {
        val display = DisplaySetting(
            appearance = AppearanceSettings(
                chatUiStyle = ChatUiStyle.FOCUS,
                wallpaper = "file:///data/user/0/me.rerere.rikkahub/files/appearance_wallpapers/a.jpg",
                wallpaperOpacity = 0.7f,
            )
        )

        val json = JsonInstant.encodeToString(DisplaySetting.serializer(), display)
        val decoded = JsonInstant.decodeFromString(DisplaySetting.serializer(), json)

        assertTrue(json.contains("\"appearance\""))
        assertEquals(display.appearance, decoded.appearance)
        assertTrue(json.contains("appearance_wallpapers/a.jpg"))
        assertTrue(decoded.showUserAvatar)
        assertEquals(ChatUiStyle.FOCUS, decoded.appearance.chatUiStyle)
        assertEquals(0.7f, decoded.appearance.wallpaperOpacity, 0f)
    }

    @Test
    fun `empty appearance object falls back to every default`() {
        val decoded = decodeAppearance("{}")

        assertEquals(AppearanceSettings(), decoded)
        assertEquals(ChatUiStyle.STUDIO, decoded.chatUiStyle)
        assertEquals(WallpaperScale.CROP, decoded.wallpaperScale)
        assertNull(decoded.wallpaper)
        assertTrue(decoded.immersiveWallpaper)
    }

    @Test
    fun `every ui style and scale roundtrips through json`() {
        ChatUiStyle.entries.forEach { style ->
            WallpaperScale.entries.forEach { scale ->
                val original = AppearanceSettings(
                    chatUiStyle = style,
                    wallpaper = "https://example.com/wallpaper-${style.name.lowercase()}.jpg",
                    immersiveWallpaper = style != ChatUiStyle.CLASSIC,
                    wallpaperOpacity = 0.42f,
                    wallpaperScrim = 0.31f,
                    surfaceOpacity = 0.78f,
                    wallpaperBlur = 6f,
                    wallpaperScale = scale,
                    wallpaperAlignmentX = 0f,
                    wallpaperAlignmentY = 1f,
                )

                val json = encodeAppearance(original)
                val decoded = decodeAppearance(json)

                assertEquals(original, decoded)
                assertEquals(style, decoded.chatUiStyle)
                assertEquals(scale, decoded.wallpaperScale)
            }
        }
    }

    @Test
    fun `enum values are stored by their stable serial names`() {
        val classic = encodeAppearanceObject(AppearanceSettings(chatUiStyle = ChatUiStyle.CLASSIC))
        val focus = encodeAppearanceObject(
            AppearanceSettings(chatUiStyle = ChatUiStyle.FOCUS, wallpaperScale = WallpaperScale.FIT)
        )

        // 改名/换顺序时旧配置不能读崩，写的永远是这些字符串
        assertEquals("classic", classic.getValue("chatUiStyle").jsonPrimitive.content)
        assertEquals(
            "studio",
            encodeAppearanceObject(AppearanceSettings()).getValue("chatUiStyle").jsonPrimitive.content,
        )
        assertEquals("focus", focus.getValue("chatUiStyle").jsonPrimitive.content)
        assertEquals("fit", focus.getValue("wallpaperScale").jsonPrimitive.content)
        assertEquals("crop", classic.getValue("wallpaperScale").jsonPrimitive.content)
    }

    @Test
    fun `normalized turns non finite floats back into field defaults`() {
        val broken = AppearanceSettings(
            wallpaper = "   ",
            wallpaperOpacity = Float.NaN,
            wallpaperScrim = Float.POSITIVE_INFINITY,
            surfaceOpacity = Float.NEGATIVE_INFINITY,
            wallpaperBlur = Float.NaN,
            wallpaperAlignmentX = Float.NEGATIVE_INFINITY,
            wallpaperAlignmentY = Float.NaN,
        )

        val normalized = broken.normalized()

        assertNull("只有空白的路径当作没设置", normalized.wallpaper)
        assertEquals(1f, normalized.wallpaperOpacity, 0f)
        assertEquals(0.20f, normalized.wallpaperScrim, 0f)
        assertEquals(0.86f, normalized.surfaceOpacity, 0f)
        assertEquals(0f, normalized.wallpaperBlur, 0f)
        assertEquals(0.5f, normalized.wallpaperAlignmentX, 0f)
        assertEquals(0.5f, normalized.wallpaperAlignmentY, 0f)
    }

    @Test
    fun `normalized clamps out of range values`() {
        val wild = AppearanceSettings(
            wallpaperOpacity = 3.5f,
            wallpaperScrim = -2f,
            surfaceOpacity = 0.1f,
            wallpaperBlur = 100f,
            wallpaperAlignmentX = -1f,
            wallpaperAlignmentY = 4f,
        )

        val normalized = wild.normalized()

        assertEquals(1f, normalized.wallpaperOpacity, 0f)
        assertEquals(0f, normalized.wallpaperScrim, 0f)
        assertEquals(AppearanceSettings.MIN_SURFACE_OPACITY, normalized.surfaceOpacity, 0f)
        assertEquals(AppearanceSettings.MAX_BLUR_DP, normalized.wallpaperBlur, 0f)
        assertEquals(0f, normalized.wallpaperAlignmentX, 0f)
        assertEquals(1f, normalized.wallpaperAlignmentY, 0f)
    }

    @Test
    fun `normalized keeps good values and is idempotent`() {
        val good = AppearanceSettings(
            chatUiStyle = ChatUiStyle.CLASSIC,
            wallpaper = "file:///wallpaper.jpg",
            immersiveWallpaper = false,
            wallpaperOpacity = 0.9f,
            wallpaperScrim = 0.4f,
            surfaceOpacity = 0.7f,
            wallpaperBlur = 12f,
            wallpaperScale = WallpaperScale.FIT,
            wallpaperAlignmentX = 0.25f,
            wallpaperAlignmentY = 0.75f,
        )

        val once = good.normalized()

        assertEquals(good, once)
        assertEquals(once, once.normalized())
        assertTrue(once.hasWallpaper())
    }

    @Test
    fun `wallpaper path is trimmed and blank means no wallpaper`() {
        assertEquals("file:///a.jpg", AppearanceSettings(wallpaper = "  file:///a.jpg  ").normalized().wallpaper)
        assertNull(AppearanceSettings(wallpaper = "").normalized().wallpaper)
        assertNull(AppearanceSettings(wallpaper = " \n ").normalized().wallpaper)
        assertFalse(AppearanceSettings(wallpaper = "").hasWallpaper())
        assertTrue(AppearanceSettings(wallpaper = "https://example.com/a.jpg").hasWallpaper())
    }
}
