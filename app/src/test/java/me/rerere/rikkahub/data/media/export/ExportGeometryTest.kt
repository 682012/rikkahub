package me.rerere.rikkahub.data.media.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 导出几何与时间轴的针对性测试。重点是不变量：任何 fit/crop 组合都不拉伸（源矩形和目标矩形宽高比一致）。
 */
class ExportGeometryTest {
    private fun assertNoStretch(rect: FitRect) {
        val src = rect.srcWidth / rect.srcHeight
        val dst = rect.dstWidth / rect.dstHeight
        assertTrue("aspect changed: src=$src dst=$dst", abs(src - dst) < 1e-3f)
    }

    @Test
    fun `fit letterboxes wide source inside square`() {
        val rect = ExportGeometry.compute(2000, 1000, 1080, 1080, ExportFit.FIT)
        assertEquals(1080f, rect.dstWidth, 0.01f)
        assertEquals(540f, rect.dstHeight, 0.01f)
        assertEquals(0f, rect.dstLeft, 0.01f)
        assertEquals(270f, rect.dstTop, 0.5f)
        assertNoStretch(rect)
    }

    @Test
    fun `fit pillarboxes tall source inside landscape`() {
        val rect = ExportGeometry.compute(720, 1280, 1920, 1080, ExportFit.FIT)
        assertEquals(607.5f, rect.dstWidth, 0.5f)
        assertEquals(1080f, rect.dstHeight, 0.01f)
        assertEquals((1920f - rect.dstWidth) / 2f, rect.dstLeft, 0.5f)
        assertNoStretch(rect)
    }

    @Test
    fun `crop fills the target and trims the long side`() {
        val rect = ExportGeometry.compute(2000, 1000, 1080, 1080, ExportFit.CROP)
        assertEquals(1080f, rect.dstWidth, 0.01f)
        assertEquals(1080f, rect.dstHeight, 0.01f)
        assertEquals(1000f, rect.srcWidth, 0.01f)
        assertEquals(1000f, rect.srcHeight, 0.01f)
        assertEquals(500f, rect.srcLeft, 0.01f)
        assertEquals(0f, rect.srcTop, 0.01f)
        assertNoStretch(rect)
    }

    @Test
    fun `crop focus stays inside the source`() {
        val left = ExportGeometry.compute(2000, 1000, 1080, 1080, ExportFit.CROP, focusX = 0f)
        val right = ExportGeometry.compute(2000, 1000, 1080, 1080, ExportFit.CROP, focusX = 1f)
        assertEquals(0f, left.srcLeft, 0.01f)
        assertEquals(1000f, right.srcLeft, 0.01f)
        assertTrue(left.srcLeft + left.srcWidth <= 2000f)
        assertTrue(right.srcLeft + right.srcWidth <= 2000f)
    }

    @Test
    fun `matching aspect fits exactly with no letterbox`() {
        val rect = ExportGeometry.compute(1024, 1024, 512, 512, ExportFit.FIT)
        assertEquals(0f, rect.dstLeft, 0f)
        assertEquals(0f, rect.dstTop, 0f)
        assertEquals(512f, rect.dstWidth, 0f)
        assertEquals(512f, rect.dstHeight, 0f)
        assertEquals(0f to 0f, ExportGeometry.letterboxInsets(1024, 1024, 512, 512))
    }

    @Test
    fun `every aspect combination is never stretched`() {
        val sources = listOf(1254 to 1254, 1536 to 1024, 1024 to 1536, 3000 to 400, 51 to 977)
        val targets = listOf(512 to 512, 1080 to 1920, 1920 to 1080, 1000 to 750, 16 to 4096)
        for ((sw, sh) in sources) {
            for ((tw, th) in targets) {
                for (fit in ExportFit.entries) {
                    val rect = ExportGeometry.compute(sw, sh, tw, th, fit)
                    assertNoStretch(rect)
                    if (fit == ExportFit.CROP) {
                        assertEquals(tw.toFloat(), rect.dstWidth, 0.01f)
                        assertEquals(th.toFloat(), rect.dstHeight, 0.01f)
                    }
                    assertTrue(rect.srcLeft >= -0.01f && rect.srcTop >= -0.01f)
                    assertTrue(rect.srcLeft + rect.srcWidth <= sw + 0.01f)
                    assertTrue(rect.srcTop + rect.srcHeight <= sh + 0.01f)
                }
            }
        }
    }

    @Test
    fun `scaledTo keeps aspect and hits the long side`() {
        val square = ExportSize.scaledTo(1254, 1254, 1024)
        assertEquals(1024, square.width)
        assertEquals(1024, square.height)
        val wide = ExportSize.scaledTo(1536, 1024, 1200)
        assertEquals(1200, wide.width)
        assertEquals(800, wide.height)
    }

    @Test
    fun `sameAspect tolerates rounding`() {
        assertTrue(ExportGeometry.sameAspect(1254, 1254, 1080, 1080))
        assertTrue(ExportGeometry.sameAspect(1536, 1024, 1080, 720))
        assertFalse(ExportGeometry.sameAspect(1536, 1024, 1080, 1080))
    }

    @Test
    fun `motion spec frame count and timestamps follow duration and fps`() {
        val mp4 = MotionSpec(MotionStyle.ZOOM_IN, ExportSize(512, 512), durationSeconds = 3, output = MotionOutput.MP4)
        assertEquals(30, mp4.fps)
        assertEquals(90, mp4.frameCount)
        assertEquals(33, mp4.frameMillis)
        assertEquals(0f, mp4.frameTime(0), 0f)
        assertEquals(1f / 90f, mp4.frameTime(1), 1e-6f)
        assertTrue(mp4.frameTime(mp4.frameCount) <= 1f)

        val gif = mp4.copy(output = MotionOutput.GIF)
        assertEquals(15, gif.fps)
        assertEquals(45, gif.frameCount)
        assertEquals(66, gif.frameMillis)
    }

    @Test
    fun `zoom starts and ends at the requested scale`() {
        val start = MotionTimeline.frameAt(MotionStyle.ZOOM_IN, 0f, 1.10f)
        val end = MotionTimeline.frameAt(MotionStyle.ZOOM_IN, 1f, 1.10f)
        assertEquals(1f, start.scale, 1e-5f)
        assertEquals(1.10f, end.scale, 1e-5f)
        val outStart = MotionTimeline.frameAt(MotionStyle.ZOOM_OUT, 0f, 1.10f)
        val outEnd = MotionTimeline.frameAt(MotionStyle.ZOOM_OUT, 1f, 1.10f)
        assertEquals(1.10f, outStart.scale, 1e-5f)
        assertEquals(1f, outEnd.scale, 1e-5f)
    }

    @Test
    fun `scale never drops below 1 so the canvas is always covered`() {
        for (style in MotionStyle.entries) {
            for (i in 0..40) {
                val frame = MotionTimeline.frameAt(style, i / 40f, 1.10f)
                assertTrue("$style scale ${frame.scale}", frame.scale >= 1f)
                assertTrue("$style alpha ${frame.alpha}", frame.alpha in 0f..1f)
                assertTrue("$style offsetX", frame.offsetX in -0.05f..0.05f)
                assertTrue("$style offsetY", frame.offsetY in -0.05f..0.05f)
            }
        }
    }

    @Test
    fun `fade starts transparent and ends opaque`() {
        assertEquals(0f, MotionTimeline.frameAt(MotionStyle.FADE_IN, 0f, 1.1f).alpha, 1e-5f)
        assertEquals(1f, MotionTimeline.frameAt(MotionStyle.FADE_IN, 1f, 1.1f).alpha, 1e-5f)
    }

    @Test
    fun `pan moves monotonically`() {
        val a = MotionTimeline.frameAt(MotionStyle.PAN_RIGHT, 0.25f, 1.1f)
        val b = MotionTimeline.frameAt(MotionStyle.PAN_RIGHT, 0.75f, 1.1f)
        assertTrue("pan should move right: ${a.offsetX} -> ${b.offsetX}", b.offsetX > a.offsetX)
        val up0 = MotionTimeline.frameAt(MotionStyle.PAN_UP, 0.25f, 1.1f)
        val up1 = MotionTimeline.frameAt(MotionStyle.PAN_UP, 0.75f, 1.1f)
        assertTrue("pan up should move up: ${up0.offsetY} -> ${up1.offsetY}", up1.offsetY < up0.offsetY)
    }
}
