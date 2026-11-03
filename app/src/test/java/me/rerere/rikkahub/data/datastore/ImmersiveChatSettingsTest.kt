package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r10 沉浸聊天的设置项：
 *
 * - 默认开着，但只有真的有壁纸/渐变时才有区别（没壁纸的用户看到的就是原来的默认外观）；
 * - 老配置（没有这个字段）读出来必须还是默认开，不能因为缺字段就退回 r9 的厚卡片；
 * - 关掉 = “恢复卡片”，并且这个选择要能落盘。
 */
class ImmersiveChatSettingsTest {

    private fun encode(value: AppearanceSettings): String =
        JsonInstant.encodeToString(AppearanceSettings.serializer(), value)

    @Test
    fun `immersive chat is on by default`() {
        assertTrue(AppearanceSettings().immersiveChat)
        assertTrue("有壁纸时默认就该是沉浸", AppearanceSettings(wallpaper = "file:///a.jpg").immersiveChat)
    }

    @Test
    fun `old settings without the field keep the immersive default`() {
        val legacy = """
            {
              "chatUiStyle": "studio",
              "wallpaper": "file:///data/w.jpg",
              "surfaceOpacity": 0.86,
              "chatHeaderExpanded": false
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString(AppearanceSettings.serializer(), legacy)

        assertTrue("旧配置缺字段必须落回默认的沉浸", decoded.immersiveChat)
        assertEquals("file:///data/w.jpg", decoded.wallpaper)
    }

    @Test
    fun `turning immersion off survives a round trip`() {
        val json = encode(AppearanceSettings(immersiveChat = false))
        assertTrue("关闭状态要写进 JSON", json.contains("\"immersiveChat\":false"))
        val encoded = JsonInstant.parseToJsonElement(json).jsonObject
        assertEquals("false", encoded.getValue("immersiveChat").jsonPrimitive.content)
        assertFalse(JsonInstant.decodeFromString(AppearanceSettings.serializer(), json).immersiveChat)
    }

    @Test
    fun `immersion only matters when something is actually behind the chat`() {
        val on = AppearanceSettings(immersiveChat = true)
        assertFalse("没有壁纸时不该进入沉浸", on.immersiveChatActive(backgroundActive = false))
        assertTrue(on.immersiveChatActive(backgroundActive = true))

        val off = AppearanceSettings(immersiveChat = false)
        assertFalse("关掉之后有壁纸也按 r9 画", off.immersiveChatActive(backgroundActive = true))
        assertFalse(off.immersiveChatActive(backgroundActive = false))
    }

    @Test
    fun `immersion reuses the existing panel intensity slider`() {
        // 不新增“沉浸透明度”字段：强度还是那一份 surfaceOpacity。
        val customized = AppearanceSettings(surfaceOpacity = 0.72f, immersiveChat = true)
        assertEquals(0.72f, customized.normalized().surfaceOpacity, 0f)
        assertEquals(customized, customized.normalized())
    }
}
