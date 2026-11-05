package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r21「轨道」（ORBIT）第四种聊天风格的回归线（纯函数，不需要设备）。
 *
 * 四条底线：
 * 1. 四个风格都有 spec，`of()` 穷尽，加风格不会漏映射；
 * 2. 老的三种（CLASSIC / STUDIO / FOCUS）布局参数一个都没被新风格蹭改；
 * 3. ORBIT 和另外三种在"标题栏形态 + 圆角 + 间距 + 阅读宽度"上真的不一样，不是换皮空壳；
 * 4. 旧的序列化名照旧能读回来，ORBIT 自己用 "orbit"，默认风格仍是 STUDIO（重启后选择保留）。
 */
class OrbitStyleSpecTest {

    private fun encode(value: AppearanceSettings): String =
        JsonInstant.encodeToString(AppearanceSettings.serializer(), value)

    private fun decode(json: String): AppearanceSettings =
        JsonInstant.decodeFromString(AppearanceSettings.serializer(), json)

    private fun encodedStyle(value: AppearanceSettings): String =
        JsonInstant.parseToJsonElement(encode(value)).jsonObject
            .getValue("chatUiStyle").jsonPrimitive.content

    @Test
    fun everyStyleHasItsOwnSpecAndOrbitIsTheOnlyTaskRail() {
        assertEquals(4, ChatUiStyle.entries.size)
        val specs = ChatUiStyle.entries.map { ChatStyleSpec.of(it) }
        specs.forEachIndexed { index, spec ->
            assertEquals("第 $index 个风格的 spec 映射错了", ChatUiStyle.entries[index], spec.style)
        }
        assertEquals(
            "任务栏轨道只应该属于 ORBIT",
            listOf(ChatUiStyle.ORBIT),
            specs.filter { it.headerTaskRail }.map { it.style },
        )
        assertEquals(
            "只有 ORBIT 需要轨道上下文表盘",
            listOf(ChatUiStyle.ORBIT),
            specs.filter { it.railContextGauge }.map { it.style },
        )
    }

    /** 老三种的布局参数是用户已经习惯的样子，加风格不许动它们。 */
    @Test
    fun legacyStylesKeepTheirOriginalLayout() {
        val classic = ChatStyleSpec.of(ChatUiStyle.CLASSIC)
        assertEquals(false, classic.headerFloating)
        assertEquals(12.dp, classic.messageSpacing)
        assertEquals(16.dp, classic.listSidePadding)
        assertEquals(24.dp, classic.bubbleCorner)
        assertEquals(6.dp, classic.bubbleTailCorner)
        assertEquals(28.dp, classic.inputCorner)
        assertFalse(classic.inputFloating)

        val studio = ChatStyleSpec.of(ChatUiStyle.STUDIO)
        assertTrue(studio.headerFloating)
        assertEquals(880.dp, studio.contentMaxWidth)
        assertEquals(18.dp, studio.bubbleCorner)
        assertEquals(18.dp, studio.bubbleTailCorner)
        assertTrue(studio.inputFloating)
        assertFalse("浮岛风格不该被轨道参数污染", studio.headerTaskRail)

        val focus = ChatStyleSpec.of(ChatUiStyle.FOCUS)
        assertEquals(680.dp, focus.contentMaxWidth)
        assertEquals(20.dp, focus.messageSpacing)
        assertEquals(16.dp, focus.bubbleCorner)
        assertEquals(16.dp, focus.bubbleTailCorner)
        assertTrue(focus.drawerHeaderCompact)
        assertFalse(focus.headerTaskRail)
    }

    @Test
    fun orbitIsVisuallyDifferentFromEveryOtherStyle() {
        val orbit = ChatStyleSpec.of(ChatUiStyle.ORBIT)

        // 阅读区铺满 + 近方角 + 靠头像侧的小尾巴：和 STUDIO/FOCUS（无尾巴全圆角）与 CLASSIC（24dp 大圆角）都不同
        assertEquals(Dp.Infinity, orbit.contentMaxWidth)
        assertEquals(14.dp, orbit.bubbleCorner)
        assertEquals(4.dp, orbit.bubbleTailCorner)

        val others = ChatUiStyle.entries.filter { it != ChatUiStyle.ORBIT }.map { ChatStyleSpec.of(it) }
        others.forEach { other ->
            // 阅读宽度：ORBIT 和 CLASSIC 都是铺满（Infinity），只能和限宽的风格逐项比
            if (other.contentMaxWidth != Dp.Infinity) {
                assertNotEquals(
                    "ORBIT 的阅读宽度不该和 ${other.style} 一样",
                    other.contentMaxWidth,
                    orbit.contentMaxWidth,
                )
            }
            assertNotEquals(
                "ORBIT 的气泡圆角不该和 ${other.style} 一样",
                other.bubbleCorner to other.bubbleTailCorner,
                orbit.bubbleCorner to orbit.bubbleTailCorner,
            )
            assertNotEquals(
                "ORBIT 的消息间距不该和 ${other.style} 一样",
                other.messageSpacing,
                orbit.messageSpacing,
            )
            // 组合起来也必须不同：换风格不是只换一个数值就交差
            assertNotEquals(
                "ORBIT 的整体布局参数组合不该和 ${other.style} 一样",
                listOf(other.contentMaxWidth, other.messageSpacing, other.bubbleCorner, other.bubbleTailCorner),
                listOf(orbit.contentMaxWidth, orbit.messageSpacing, orbit.bubbleCorner, orbit.bubbleTailCorner),
            )
        }
    }

    @Test
    fun orbitSurvivesRestartAndKeepsItsOwnSerialName() {
        // 落盘的永远是稳定名字，读回来还是 ORBIT：改 UI 不会让用户的选择在重启后丢失
        assertEquals("orbit", encodedStyle(AppearanceSettings(chatUiStyle = ChatUiStyle.ORBIT)))
        assertEquals(
            ChatUiStyle.ORBIT,
            decode("""{"chatUiStyle":"orbit"}""").chatUiStyle,
        )
        assertEquals(
            ChatUiStyle.ORBIT,
            decode(encode(AppearanceSettings(chatUiStyle = ChatUiStyle.ORBIT))).chatUiStyle,
        )
        // 默认风格不变：老用户升级后不会突然被换成轨道
        assertEquals("studio", encodedStyle(AppearanceSettings()))
        assertEquals(ChatUiStyle.STUDIO, AppearanceSettings().chatUiStyle)
    }

    /** 旧选项保留：历史配置里的三个名字一个都不能失效。 */
    @Test
    fun legacyStyleSerialNamesStillDecode() {
        mapOf(
            "classic" to ChatUiStyle.CLASSIC,
            "studio" to ChatUiStyle.STUDIO,
            "focus" to ChatUiStyle.FOCUS,
        ).forEach { (name, expected) ->
            assertEquals("旧选项 $name 没读回来", expected, decode("""{"chatUiStyle":"$name"}""").chatUiStyle)
            assertEquals("旧选项 $name 写回去变了名", name, encodedStyle(AppearanceSettings(chatUiStyle = expected)))
        }
    }
}
