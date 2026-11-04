package me.rerere.rikkahub.data.datastore

import java.io.File
import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.withRestoredWallpaperPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r12 定向回归：每角色覆盖 + 三只新 enum + 备份路径。
 *
 * 只断言"用户能看到的行为"：切角色来回之后名字/台词/人设/图片各自还在，全局模型跟着走，
 * 旧 JSON 仍能读，备份重定位覆盖所有角色的图片。
 */
class FloatingCompanionR12Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `legacy cat and dragon json keep their approved looks`() {
        val cat = json.decodeFromString<FloatingCompanionSettings>("""{"preset":"cat","name":"喵丸"}""")
        assertEquals(FloatingCompanionPreset.CAT, cat.preset)
        // 旧 CAT 显示成人设认可的小鲸女仆，不是空白
        assertEquals(FloatingCompanionPreset.WHALE_MAID, cat.effectivePreset)
        assertEquals("喵丸", cat.name)

        val dragon = json.decodeFromString<FloatingCompanionSettings>("""{"preset":"dragon"}""")
        assertEquals(FloatingCompanionPreset.DRAGON, dragon.preset)
        assertEquals(FloatingCompanionPreset.DRAGON_GIRL, dragon.effectivePreset)

        // r11 及以前的 JSON 没有 characterOverrides，读出来必须是空表而不是崩
        assertTrue(cat.characterOverrides.isEmpty())
    }

    @Test
    fun `the three r12 presets round trip with snake case names`() {
        val cases = mapOf(
            FloatingCompanionPreset.GEMINI_CAT to "gemini_cat",
            FloatingCompanionPreset.GLM_FOX to "glm_fox",
            FloatingCompanionPreset.QWEN_GIRL to "qwen_girl",
        )
        for ((preset, serial) in cases) {
            val encoded = json.encodeToString(FloatingCompanionSettings(preset = preset))
            assertTrue("$preset 应序列化为 $serial：$encoded", encoded.contains("\"$serial\""))
            val decoded = json.decodeFromString<FloatingCompanionSettings>("""{"preset":"$serial"}""")
            assertEquals(preset, decoded.preset)
            // 新角色是真实立绘，不能再被退回别的角色
            assertEquals(preset, decoded.effectivePreset)
        }
    }

    @Test
    fun `switching characters keeps custom fields per character`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val idleA = file(dir, "a-idle.png")
        val tapA = file(dir, "a-tap.png")
        val idleB = file(dir, "b-idle.png")
        val tapB = file(dir, "b-tap.png")

        val a = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            customImagePath = idleA.toURI().toString(),
            customTapImagePath = tapA.toURI().toString(),
            name = "喵丸",
            tapLines = listOf("在呢"),
            aiPersonaPrompt = "persona-a",
        )

        // 切到一个从没自定义过的角色：当前投影必须清空，不能把上一个角色的设置借过来
        val toNew = a.selectPreset(FloatingCompanionPreset.GEMINI_CAT)
        assertEquals(FloatingCompanionPreset.GEMINI_CAT, toNew.preset)
        assertEquals("fallback", toNew.displayName("fallback"))
        assertTrue(toNew.tapLines.isEmpty())
        assertNull(toNew.customImagePath)
        assertNull(toNew.customTapImagePath)
        assertEquals("", toNew.aiPersonaPrompt)

        val b = toNew.copy(
            customImagePath = idleB.toURI().toString(),
            customTapImagePath = tapB.toURI().toString(),
            name = "小橘",
            tapLines = listOf("喵"),
            aiPersonaPrompt = "persona-b",
        )

        // 切回去：A 的自定义原样回来，B 的写进覆盖表
        val back = b.selectPreset(FloatingCompanionPreset.WHALE_MAID)
        assertEquals(FloatingCompanionPreset.WHALE_MAID, back.preset)
        assertEquals("喵丸", back.name)
        assertEquals(listOf("在呢"), back.tapLines)
        assertEquals("persona-a", back.aiPersonaPrompt)
        assertEquals(idleA.toURI().toString(), back.customImagePath)
        assertEquals(tapA.toURI().toString(), back.customTapImagePath)
        assertTrue(back.characterOverrides.isNotEmpty())

        // 再切到第三只再切回 B：中间那只不能把 B 的设置覆盖掉
        val third = back.selectPreset(FloatingCompanionPreset.QWEN_GIRL)
        assertEquals("fallback", third.displayName("fallback"))
        val bAgain = third.selectPreset(FloatingCompanionPreset.GEMINI_CAT)
        assertEquals("小橘", bAgain.name)
        assertEquals(listOf("喵"), bAgain.tapLines)
        assertEquals("persona-b", bAgain.aiPersonaPrompt)
        assertEquals(idleB.toURI().toString(), bAgain.customImagePath)
        assertEquals(tapB.toURI().toString(), bAgain.customTapImagePath)
    }

    @Test
    fun `model and global switches survive character changes`() {
        val modelId = Uuid.random()
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            aiModelMode = CompanionModelMode.FIXED,
            aiModelId = modelId,
            autoEventsEnabled = true,
            sizeDp = 88,
            animationEnabled = false,
            minimized = false,
        )

        for (next in listOf(
            FloatingCompanionPreset.GEMINI_CAT,
            FloatingCompanionPreset.GLM_FOX,
            FloatingCompanionPreset.WHALE_MAID,
        )) {
            val switched = settings.selectPreset(next)
            assertEquals(next, switched.preset)
            // 全局模型/开关不是每角色设置：换角色不能把"用户挑过的模型"换掉，也不能偷偷省钱或偷偷花钱
            assertEquals(CompanionModelMode.FIXED, switched.aiModelMode)
            assertEquals(modelId, switched.aiModelId)
            assertTrue(switched.autoEventsEnabled)
            assertEquals(88, switched.sizeDp)
            assertEquals(false, switched.animationEnabled)
            assertEquals(false, switched.minimized)
            assertTrue(switched.aiEnabled)
        }
    }

    @Test
    fun `normalized is idempotent and still cleans the current projection`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val idle = file(dir, "idle.png")
        val raw = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.QWEN_GIRL,
            customImagePath = idle.toURI().toString(),
            name = "  小 橘  ",
            tapLines = listOf("  喵  ", "", "  ", "喵"),
            sizeDp = 400,
            aiModelMode = CompanionModelMode.FIXED,
            aiModelId = Uuid.random(),
        ).selectPreset(FloatingCompanionPreset.GLM_FOX).copy(
            name = "  阿狸  ",
            tapLines = listOf("  在  ", "在"),
            customImagePath = "https://example.test/fox.png",
            sizeDp = 20,
        )

        val once = raw.normalized()
        assertEquals("阿狸", once.name)
        assertEquals(listOf("在"), once.tapLines)
        assertEquals(FloatingCompanionLimits.SIZE_MIN_DP, once.sizeDp)
        assertNull(once.customImagePath)
        // 幂等：再跑一次不会再变（备份/设置写入会连着调两次）
        assertEquals(once, once.normalized())
        // 归一化不能把别的角色的覆盖弄丢
        assertEquals(raw.characterOverrides.size, once.characterOverrides.size)
    }

    @Test
    fun `owned files cover every character, not only the current one`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val idleA = file(dir, "a-idle.png")
        val tapA = file(dir, "a-tap.png")
        val idleB = file(dir, "b-idle.png")
        val tapB = file(dir, "b-tap.png")

        val a = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.WHALE_MAID,
            customImagePath = idleA.toURI().toString(),
            customTapImagePath = tapA.toURI().toString(),
            name = "喵丸",
        )
        val b = a.selectPreset(FloatingCompanionPreset.GEMINI_CAT).copy(
            customImagePath = idleB.toURI().toString(),
            customTapImagePath = tapB.toURI().toString(),
            name = "小橘",
        )

        // 当前角色是 B：备份/清理必须同时看到 A 的四张图，否则切回去就是空白
        val owned = FloatingCompanionPaths.ownedFiles(b, dir).map { it.name }.sorted()
        assertEquals(listOf("a-idle.png", "a-tap.png", "b-idle.png", "b-tap.png"), owned)

        // 当前角色的图和覆盖表里的同一张不重复算
        val shared = b.copy(customImagePath = idleA.toURI().toString())
        val deduped = FloatingCompanionPaths.ownedFiles(shared, dir).map { it.name }
        assertTrue(deduped.size < 4)
        assertTrue(deduped.contains("a-idle.png"))
        assertNotEquals(deduped.size, owned.size)
    }

    @Test
    fun `backup rebase moves the images of every character`() {
        val oldA = "file:///old/files/floating_companions/a%20one.png"
        val oldB = "file:///old/files/floating_companions/b%20two.png"
        val settings = Settings.dummy().let { base ->
            val companion = FloatingCompanionSettings(
                preset = FloatingCompanionPreset.WHALE_MAID,
                customImagePath = oldA,
            ).selectPreset(FloatingCompanionPreset.GEMINI_CAT).copy(customImagePath = oldB)
            base.copy(displaySetting = base.displaySetting.copy(floatingCompanion = companion))
        }

        val restored = settings.withRestoredWallpaperPath(File("/new/files"))
        val companion = restored.displaySetting.floatingCompanion
        assertEquals("file:/new/files/floating_companions/b%20two.png", companion.customImagePath)

        // 切回旧角色：它的图片也必须已经指向新目录，而不是继续挂在旧包名下面
        val back = companion.selectPreset(FloatingCompanionPreset.WHALE_MAID)
        assertEquals("file:/new/files/floating_companions/a%20one.png", back.customImagePath)
    }
}
