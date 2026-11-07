package me.rerere.rikkahub.startup

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r22 本地启动素材的纯逻辑边界：类型准入、大小上限、时长/缩放收敛、场景回落、拷贝上限。
 *
 * 一条 Android 类都不碰（只看 [StartupIntroRules] / [StartupIntroSceneResolver] / [StartupIntroPolicy]），
 * 所以能直接跑 JVM 单测；真正的写盘、Coil 解码、ExoPlayer 在设备上，不在这里假装测过。
 */
class StartupIntroCustomTest {

    // ---------------------------------------------------------------- 类型准入 ----

    @Test
    fun `image and animated formats are accepted, unknown extensions are not`() {
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOfFileName("cat.png"))
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOfFileName("CAT.JPG"))
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOfFileName("loop.webp"))
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOfFileName("loop.gif"))
        assertEquals(StartupMediaKind.VIDEO, StartupIntroRules.kindOfFileName("intro.mp4"))
        assertEquals(StartupMediaKind.VIDEO, StartupIntroRules.kindOfFileName("clip.WEBM"))

        assertNull(StartupIntroRules.kindOfFileName("notes.txt"))
        assertNull(StartupIntroRules.kindOfFileName("archive.zip"))
        assertNull(StartupIntroRules.kindOfFileName(null))
        assertNull(StartupIntroRules.kindOfFileName(""))
        assertNull(StartupIntroRules.kindOfFileName("no_extension"))
    }

    @Test
    fun `mime type wins and file name is the fallback`() {
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOf("image/gif", null))
        assertEquals(StartupMediaKind.VIDEO, StartupIntroRules.kindOf("video/mp4", "whatever.bin"))
        // 系统选择器常给 octet-stream：这时候只能看名字
        assertEquals(StartupMediaKind.IMAGE, StartupIntroRules.kindOf("application/octet-stream", "a.webp"))
        assertEquals(StartupMediaKind.VIDEO, StartupIntroRules.kindOf(null, "a.mp4"))
        assertNull(StartupIntroRules.kindOf("application/pdf", "a.pdf"))
        assertNull(StartupIntroRules.kindOf(null, null))
    }

    @Test
    fun `landed file name never carries the user supplied name`() {
        val weird = StartupIntroRules.extensionFor(StartupMediaKind.IMAGE, "../../etc/passwd.PNG", null)
        assertEquals("png", weird)
        val name = StartupIntroRules.fileNameFor(StartupMediaKind.IMAGE, weird)
        assertEquals("intro_image.png", name)
        assertFalse(name.contains("passwd"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))

        // 名字里没有可用扩展名时退到 MIME
        assertEquals("jpeg", StartupIntroRules.extensionFor(StartupMediaKind.IMAGE, "noext", "image/jpeg"))
        // 连 MIME 都没有时给一个安全默认值
        assertEquals("mp4", StartupIntroRules.extensionFor(StartupMediaKind.VIDEO, null, null))
    }

    // ---------------------------------------------------------------- 大小上限 ----

    @Test
    fun `image and video have their own size ceilings`() {
        assertEquals(12L * 1024 * 1024, StartupIntroRules.maxBytes(StartupMediaKind.IMAGE))
        assertEquals(8L * 1024 * 1024, StartupIntroRules.maxBytes(StartupMediaKind.VIDEO))

        assertTrue(StartupIntroRules.isWithinSizeLimit(StartupMediaKind.IMAGE, 12L * 1024 * 1024))
        assertFalse(StartupIntroRules.isWithinSizeLimit(StartupMediaKind.IMAGE, 12L * 1024 * 1024 + 1))
        // 0 字节是空文件，不是"合格的小图"
        assertFalse(StartupIntroRules.isWithinSizeLimit(StartupMediaKind.IMAGE, 0L))
        // 9MB 对图是合格，对视频不合格
        assertTrue(StartupIntroRules.isWithinSizeLimit(StartupMediaKind.IMAGE, 9L * 1024 * 1024))
        assertFalse(StartupIntroRules.isWithinSizeLimit(StartupMediaKind.VIDEO, 9L * 1024 * 1024))
    }

    @Test
    fun `declared size unknown falls through to the copy time guard`() {
        // -1 = 元素查询没给出大小：不提前拒绝，交给拷贝的硬上限
        assertFalse(StartupIntroRules.declaredSizeTooLarge(StartupMediaKind.IMAGE, -1L))
        assertTrue(StartupIntroRules.declaredSizeTooLarge(StartupMediaKind.VIDEO, 8L * 1024 * 1024 + 1))
    }

    // -------------------------------------------------------------- 拷贝硬上限 ----

    @Test
    fun `copy stops at the limit instead of writing the whole file`() {
        val dir = Files.createTempDirectory("r22-startup-copy").toFile()
        try {
            val limit = 8L
            val over = File(dir, "over.bin")
            val written = StartupIntroRules.copyWithLimit(
                ByteArrayInputStream(ByteArray(9) { 7 }),
                over,
                limit,
            )
            assertEquals(-1L, written)
            // 半个文件由调用方删：这里只保证"没有把 9 个字节全写进去"
            assertTrue(over.length() < 9L)

            val exact = File(dir, "exact.bin")
            assertEquals(8L, StartupIntroRules.copyWithLimit(ByteArrayInputStream(ByteArray(8)), exact, limit))
            assertEquals(8L, exact.length())

            val empty = File(dir, "empty.bin")
            assertEquals(0L, StartupIntroRules.copyWithLimit(ByteArrayInputStream(ByteArray(0)), empty, limit))
            assertEquals(0L, empty.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `copy guard holds for a huge stream without buffering it all`() {
        val dir = Files.createTempDirectory("r22-startup-copy-big").toFile()
        try {
            val target = File(dir, "big.bin")
            val chunk = 64 * 1024
            val stream = object : java.io.InputStream() {
                var produced = 0L
                override fun read(): Int = throw AssertionError("must read in blocks")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (produced >= 4L * 1024 * 1024) return -1
                    val size = minOf(len, chunk)
                    produced += size
                    return size
                }
            }
            // 4MB 的源对 8 字节的上限：读几块就该停，不会把 4MB 全落盘
            assertEquals(-1L, StartupIntroRules.copyWithLimit(stream, target, 8L))
            assertTrue(target.length() <= 1024L)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ 时长 / 缩放 ----

    @Test
    fun `duration is clamped to a range that never parks on the screen`() {
        assertEquals(StartupIntroRules.MIN_DURATION_MS, StartupIntroRules.clampDuration(1, StartupMediaKind.IMAGE))
        assertEquals(StartupIntroRules.MAX_DURATION_MS, StartupIntroRules.clampDuration(60_000, StartupMediaKind.VIDEO))
        assertEquals(600, StartupIntroRules.clampDuration(600, StartupMediaKind.IMAGE))
        // 0/负数是"没设置过"，用类型默认值而不是最小值
        assertEquals(StartupIntroRules.customDurationDefault(StartupMediaKind.IMAGE), StartupIntroRules.clampDuration(0, StartupMediaKind.IMAGE))
        assertEquals(StartupIntroRules.customDurationDefault(StartupMediaKind.VIDEO), StartupIntroRules.clampDuration(-5, StartupMediaKind.VIDEO))
        // 内建风格默认给一个"看得见"的时长（m3：以前写死 280ms，太短像没画）
        assertEquals(1500, StartupIntroRules.BUILTIN_DURATION_MS)
        // 上限必须小于"用户会觉得卡住"的量级
        assertTrue(StartupIntroRules.MAX_DURATION_MS <= 2_500)
        // 内建时长的坏值一律回落到默认，而不是最小值
        assertEquals(StartupIntroRules.BUILTIN_DURATION_MS, StartupIntroRules.clampBuiltinDuration(0))
        assertEquals(StartupIntroRules.BUILTIN_DURATION_MS, StartupIntroRules.clampBuiltinDuration(-1))
        assertEquals(StartupIntroRules.MIN_DURATION_MS, StartupIntroRules.clampBuiltinDuration(1))
        assertEquals(StartupIntroRules.MAX_DURATION_MS, StartupIntroRules.clampBuiltinDuration(60_000))
        // 用户能选的中间值原样保留
        assertEquals(1200, StartupIntroRules.clampBuiltinDuration(1200))
    }

    @Test
    fun `scale is clamped and a bad value means default`() {
        assertEquals(StartupIntroRules.MIN_SCALE, StartupIntroRules.clampScale(1))
        assertEquals(StartupIntroRules.MAX_SCALE, StartupIntroRules.clampScale(1000))
        assertEquals(StartupIntroRules.DEFAULT_SCALE, StartupIntroRules.clampScale(0))
        assertEquals(StartupIntroRules.DEFAULT_SCALE, StartupIntroRules.clampScale(-10))
        assertEquals(120, StartupIntroRules.clampScale(120))
    }

    @Test
    fun `decode bound stays bounded for every input`() {
        // 屏幕尺寸读不到（0）时退回解码上限，再按缩放收：永远有限、永远为正
        assertTrue(StartupIntroRules.decodeBoundPx(0, 1) in 64..StartupIntroRules.MAX_DECODE_PX)
        assertTrue(StartupIntroRules.decodeBoundPx(10_000, 140) <= StartupIntroRules.MAX_DECODE_PX)
        assertTrue(StartupIntroRules.decodeBoundPx(1080, 100) <= 1080)
        assertTrue(StartupIntroRules.decodeBoundPx(1080, 100) > 0)
        assertTrue(StartupIntroRules.decodeBoundPx(-1, 100) > 0)
        // 大屏 + 放大也不会超过解码上限
        assertEquals(
            StartupIntroRules.MAX_DECODE_PX,
            StartupIntroRules.decodeBoundPx(4000, StartupIntroRules.MAX_SCALE),
        )
    }

    // ---------------------------------------------------------------- 场景回落 ----

    private fun material(kind: StartupMediaKind = StartupMediaKind.IMAGE, path: String = "/data/app/startup_intro/intro_image.png") =
        StartupCustomIntro(path = path, kind = kind)

    @Test
    fun `off stays off even with a material`() {
        val scene = StartupIntroSceneResolver.resolve(StartupMotion.OFF, material())

        assertEquals(StartupMotion.OFF, scene.style)
        assertNull(scene.custom)
        assertTrue(scene.isEmpty)
    }

    @Test
    fun `custom without a usable material falls back to the built-in default`() {
        val blankPath = StartupIntroSceneResolver.resolve(
            StartupMotion.CUSTOM,
            material(path = "   "),
        )
        assertEquals(StartupMotion.DEFAULT, blankPath.style)
        assertNull(blankPath.custom)
        assertFalse(blankPath.isEmpty)
        assertEquals(StartupIntroRules.BUILTIN_DURATION_MS, blankPath.durationMs)

        val none = StartupIntroSceneResolver.resolve(StartupMotion.CUSTOM, null)
        assertEquals(StartupMotion.DEFAULT, none.style)
        assertNull(none.custom)
    }

    @Test
    fun `custom with a material keeps its kind and clamps tuning`() {
        val scene = StartupIntroSceneResolver.resolve(
            motion = StartupMotion.CUSTOM,
            custom = material(kind = StartupMediaKind.VIDEO),
            customDurationMs = 99_999,
            customScalePercent = 5_000,
            customFit = StartupFit.COVER,
        )

        val custom = scene.custom
        assertNotNull(custom)
        assertEquals(StartupMediaKind.VIDEO, custom!!.kind)
        assertEquals(StartupIntroRules.MAX_DURATION_MS, custom.durationMs)
        assertEquals(StartupIntroRules.MAX_SCALE, custom.scalePercent)
        assertEquals(StartupFit.COVER, custom.fit)
        // 时间轴用素材自己的时长，不是内建的 280ms
        assertEquals(custom.durationMs, scene.durationMs)
        // 兜底风格永远是能直接画的
        assertTrue(scene.style.isBuiltIn)
        assertEquals(StartupMotion.DEFAULT, scene.style)
    }

    @Test
    fun `custom tuning of zero means the per kind default`() {
        val scene = StartupIntroSceneResolver.resolve(
            motion = StartupMotion.CUSTOM,
            custom = material(kind = StartupMediaKind.VIDEO, path = "/x/intro_video.mp4"),
        )
        assertEquals(StartupIntroRules.customDurationDefault(StartupMediaKind.VIDEO), scene.durationMs)
        assertEquals(StartupIntroRules.DEFAULT_SCALE, scene.custom?.scalePercent)
        assertEquals(StartupFit.DEFAULT, scene.custom?.fit)
    }

    @Test
    fun `built-in styles use the configurable duration and default to 1500ms`() {
        listOf(StartupMotion.RIKKA, StartupMotion.RING, StartupMotion.DOTS, StartupMotion.AURORA).forEach { style ->
            val scene = StartupIntroSceneResolver.resolve(style, material())
            assertEquals(style, scene.style)
            assertNull(scene.custom)
            assertEquals(StartupIntroRules.BUILTIN_DURATION_MS, scene.durationMs)
        }
        // 用户调过就按存的来；坏值仍回到默认（不会变成 200ms 一闪而过）
        val tuned = StartupIntroSceneResolver.resolve(
            motion = StartupMotion.RING,
            custom = null,
            builtinDurationMs = 2000,
        )
        assertEquals(2000, tuned.durationMs)
        val bad = StartupIntroSceneResolver.resolve(
            motion = StartupMotion.RING,
            custom = null,
            builtinDurationMs = -7,
        )
        assertEquals(StartupIntroRules.BUILTIN_DURATION_MS, bad.durationMs)
    }

    @Test
    fun `custom material still owns its own duration`() {
        val custom = material()
        val scene = StartupIntroSceneResolver.resolve(
            motion = StartupMotion.CUSTOM,
            custom = custom,
            builtinDurationMs = 2000,
        )
        // 自定义素材用素材自己的时长，不被内建时长覆盖
        assertEquals(custom.durationMs, scene.durationMs)
    }

    // ------------------------------------------------------------- 偏好取值表 ----

    @Test
    fun `motion wire values round trip and unknown values fall back to default`() {
        StartupMotion.entries.forEach { motion ->
            assertEquals(motion, StartupMotion.fromWire(motion.wireName))
        }
        assertEquals(StartupMotion.DEFAULT, StartupMotion.fromWire("nonsense"))
        assertEquals(StartupMotion.DEFAULT, StartupMotion.fromWire(null))
        // 关闭是明确的用户选择，不能被当成脏数据吃掉
        assertEquals(StartupMotion.OFF, StartupMotion.fromWire(" off "))
        // 新增的内建风格和自定义都必须认得
        assertEquals(StartupMotion.AURORA, StartupMotion.fromWire("aurora"))
        assertEquals(StartupMotion.CUSTOM, StartupMotion.fromWire("custom"))
        assertTrue(StartupMotion.CUSTOM.isBuiltIn.not())
        assertTrue(StartupMotion.AURORA.isBuiltIn)
    }

    @Test
    fun `fit mode wire values round trip`() {
        StartupFit.entries.forEach { fit -> assertEquals(fit, StartupFit.fromWire(fit.wireName)) }
        assertEquals(StartupFit.DEFAULT, StartupFit.fromWire("stretch"))
        assertEquals(StartupFit.DEFAULT, StartupFit.fromWire(null))
        assertEquals(StartupFit.CONTAIN, StartupFit.DEFAULT)
    }

    // ---------------------------------------------------- 什么时候不画（r21 规则） ----

    @Test
    fun `custom style still never covers a share notification or deep link`() {
        val shared = StartupIntroPolicy.planStartupIntro(
            motion = StartupMotion.CUSTOM,
            reduceMotion = false,
            coldStart = true,
            plainLaunch = false,
            alreadyShownInProcess = false,
            splashExitSupported = true,
        )
        assertTrue(shared.isEmpty)
        assertNull(shared.overlay)

        val reduced = StartupIntroPolicy.planStartupIntro(
            motion = StartupMotion.CUSTOM,
            reduceMotion = true,
            coldStart = true,
            plainLaunch = true,
            alreadyShownInProcess = false,
            splashExitSupported = true,
        )
        assertTrue(reduced.isEmpty)

        val warm = StartupIntroPolicy.planStartupIntro(
            motion = StartupMotion.CUSTOM,
            reduceMotion = false,
            coldStart = false,
            plainLaunch = true,
            alreadyShownInProcess = false,
            splashExitSupported = true,
        )
        assertTrue(warm.isEmpty)
    }

    @Test
    fun `plain launcher cold start still plays once with custom`() {
        val plan = StartupIntroPolicy.planStartupIntro(
            motion = StartupMotion.CUSTOM,
            reduceMotion = false,
            coldStart = true,
            plainLaunch = true,
            alreadyShownInProcess = false,
            splashExitSupported = true,
        )
        assertEquals(StartupMotion.CUSTOM, plan.overlay)
        assertTrue(plan.splashExitAnimation)
    }
}
