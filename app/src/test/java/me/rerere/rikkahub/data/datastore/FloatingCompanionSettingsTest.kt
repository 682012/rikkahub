package me.rerere.rikkahub.data.datastore

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FloatingCompanionSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default keeps the original app logo bubble`() {
        val settings = FloatingCompanionSettings()
        assertEquals(FloatingCompanionPreset.WHALE_MAID, settings.preset)
        assertEquals(FloatingCompanionPreset.WHALE_MAID, settings.effectivePreset)
        assertEquals(FloatingCompanionLimits.SIZE_DEFAULT_DP, settings.sizeDp)
        assertTrue(settings.animationEnabled)
        assertNull(settings.customImagePath)
    }

    @Test
    fun `size is clamped into 56 to 112 dp`() {
        assertEquals(56, FloatingCompanionSettings(sizeDp = 20).normalized().sizeDp)
        assertEquals(112, FloatingCompanionSettings(sizeDp = 400).normalized().sizeDp)
        assertEquals(80, FloatingCompanionSettings(sizeDp = 80).normalized().sizeDp)
        assertEquals(56, FloatingCompanionLimits.SIZE_MIN_DP)
        assertEquals(112, FloatingCompanionLimits.SIZE_MAX_DP)
        // 最小气泡也要高过 Material 的 48dp 可点区域
        assertTrue(FloatingCompanionLimits.SIZE_MIN_DP >= FloatingCompanionLimits.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `normalized trims name and cleans tap lines`() {
        val settings = FloatingCompanionSettings(
            name = "  喵丸  ",
            tapLines = listOf("  在呢  ", "", "   ", "在呢", "一句话"),
        ).normalized()
        assertEquals("喵丸", settings.name)
        assertEquals(listOf("在呢", "一句话"), settings.tapLines)
    }

    @Test
    fun `tap lines are capped in count and length`() {
        val many = (1..20).map { "line-$it" }
        val capped = FloatingCompanionSettings(tapLines = many).normalized()
        assertEquals(FloatingCompanionLimits.MAX_TAP_LINES, capped.tapLines.size)
        assertEquals("line-1", capped.tapLines.first())

        val long = "x".repeat(200)
        assertEquals(
            FloatingCompanionLimits.MAX_TAP_LINE_LENGTH,
            FloatingCompanionSettings(tapLines = listOf(long)).normalized().tapLines.single().length,
        )
    }

    @Test
    fun `name is capped`() {
        val name = "长".repeat(60)
        assertEquals(
            FloatingCompanionLimits.MAX_NAME_LENGTH,
            FloatingCompanionSettings(name = name).normalized().name.length,
        )
    }

    @Test
    fun `custom preset without image falls back to logo instead of blank`() {
        val noImage = FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM)
        assertEquals(FloatingCompanionPreset.WHALE_MAID, noImage.effectivePreset)

        val withImage = noImage.copy(customImagePath = "file:///data/user/0/app/files/x.png")
        assertEquals(FloatingCompanionPreset.CUSTOM, withImage.effectivePreset)
    }

    @Test
    fun `remote and content paths are dropped`() {
        val normalized = FloatingCompanionSettings(
            customImagePath = "https://example.com/cat.png",
            customTapImagePath = "content://media/external/images/1",
        ).normalized()
        assertNull(normalized.customImagePath)
        assertNull(normalized.customTapImagePath)
    }

    @Test
    fun `tap line falls back to the preset defaults`() {
        val fallback = listOf("default-1", "default-2")
        val empty = FloatingCompanionSettings()
        assertEquals(fallback, empty.tapLinesOr(fallback))

        val custom = FloatingCompanionSettings(tapLines = listOf("mine"))
        assertEquals(listOf("mine"), custom.tapLinesOr(fallback))
    }

    @Test
    fun `display name falls back when blank`() {
        assertEquals("Mimi", FloatingCompanionSettings().displayName("Mimi"))
        assertEquals("小猫", FloatingCompanionSettings(name = " 小猫 ").displayName("Mimi"))
    }

    @Test
    fun `owned path allowlist only accepts files in the private directory`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val inside = File(dir, "companion-1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = tempFolder.newFile("outside.png").apply { writeBytes(byteArrayOf(1)) }
        val nestedDir = File(dir, "nested").apply { mkdirs() }
        val nested = File(nestedDir, "deep.png").apply { writeBytes(byteArrayOf(1)) }

        assertNotNull(FloatingCompanionPaths.resolve(inside.toURI().toString(), dir))
        assertNull(FloatingCompanionPaths.resolve(outside.toURI().toString(), dir))
        assertNull(FloatingCompanionPaths.resolve(nested.toURI().toString(), dir))
        assertNull(FloatingCompanionPaths.resolve("https://example.com/a.png", dir))
        assertNull(FloatingCompanionPaths.resolve(null, dir))
    }

    @Test
    fun `missing files are not loadable but the path is still recognized`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val missing = File(dir, "gone.png")
        val uri = missing.toURI().toString()

        assertNull(FloatingCompanionPaths.resolve(uri, dir))
        assertTrue(FloatingCompanionPaths.isOwnedPath(uri, dir))
    }

    @Test
    fun `owned files list is deduped and skips missing entries`() {
        val dir = tempFolder.newFolder(FloatingCompanionLimits.DIRECTORY)
        val idle = File(dir, "idle.png").apply { writeBytes(byteArrayOf(1)) }
        val tap = File(dir, "tap.png").apply { writeBytes(byteArrayOf(2)) }
        val settings = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.CUSTOM,
            customImagePath = idle.toURI().toString(),
            customTapImagePath = tap.toURI().toString(),
        )

        assertEquals(listOf("idle.png", "tap.png"), FloatingCompanionPaths.ownedFiles(settings, dir).map { it.name })

        val sameImage = settings.copy(customTapImagePath = idle.toURI().toString())
        assertEquals(1, FloatingCompanionPaths.ownedFiles(sameImage, dir).size)

        val missing = settings.copy(customTapImagePath = File(dir, "gone.png").toURI().toString())
        assertEquals(1, FloatingCompanionPaths.ownedFiles(missing, dir).size)
    }

    @Test
    fun `serialized settings round trip and tolerate unknown keys`() {
        val original = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.CAT,
            name = "喵丸",
            tapLines = listOf("在的"),
            sizeDp = 88,
            animationEnabled = false,
        )
        val decoded = json.decodeFromString<FloatingCompanionSettings>(json.encodeToString(original))
        assertEquals(original, decoded)

        val legacy = json.decodeFromString<FloatingCompanionSettings>(
            """{"preset":"dragon","unknownFutureField":1}"""
        )
        assertEquals(FloatingCompanionPreset.DRAGON, legacy.preset)
        assertEquals(FloatingCompanionLimits.SIZE_DEFAULT_DP, legacy.sizeDp)

        // 老配置里完全没有这个字段时，读出来还是原来的 logo 气泡
        val absent = json.decodeFromString<FloatingCompanionSettings>("{}")
        assertEquals(FloatingCompanionPreset.WHALE_MAID, absent.preset)
        assertTrue(absent.animationEnabled)
    }

    @Test
    fun `tap feedback happens before the panel opens`() {
        // 先给反馈再跳转：延迟必须是正数且比一次反馈短
        assertTrue(FloatingCompanionLimits.TAP_DELAY_MILLIS > 0)
        assertTrue(FloatingCompanionLimits.TAP_FEEDBACK_MILLIS >= FloatingCompanionLimits.TAP_DELAY_MILLIS)
    }

    @Test
    fun `image cap is 16 mb`() {
        assertEquals(16L * 1024 * 1024, FloatingCompanionLimits.MAX_IMAGE_BYTES)
        assertFalse(FloatingCompanionLimits.DIRECTORY.isBlank())
    }
}
