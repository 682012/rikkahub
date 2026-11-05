package me.rerere.rikkahub.data.media.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** GIF 文件结构检查：头、尺寸、帧数与延时都按规范写出，多帧确实是多帧（不是一个静态画面）。 */
class GifEncoderTest {
    private fun solid(color: Int) = GifFrameSource { _, out -> out.fill(color) }

    private fun encode(width: Int = 32, height: Int = 24, frames: List<Int>, delayMillis: Int = 66): ByteArray {
        val sink = ByteArrayOutputStream()
        val palette = GifPaletteQuantizer.default()
        val writer = GifWriter(sink, width, height)
        writer.begin(palette)
        frames.forEach { color -> writer.writeFrame(palette, delayMillis, solid(color)) }
        writer.finish()
        return sink.toByteArray()
    }

    @Test
    fun `header declares GIF89a with the requested size`() {
        val data = encode(width = 32, height = 24, frames = listOf(0xFF0000))
        assertEquals("GIF89a", String(data, 0, 6, Charsets.US_ASCII))
        assertEquals(32, (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8))
        assertEquals(24, (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8))
        assertEquals(0xF7, data[10].toInt() and 0xFF)
        assertEquals(0x3B, data.last().toInt() and 0xFF)
    }

    @Test
    fun `each frame gets its own graphic control extension with the delay`() {
        val data = encode(frames = listOf(0xFF0000, 0x00FF00, 0x0000FF), delayMillis = 100)
        val delays = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 7) {
            if ((data[i].toInt() and 0xFF) == 0x21 && (data[i + 1].toInt() and 0xFF) == 0xF9) {
                assertEquals(4, data[i + 2].toInt())
                val delay = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)
                delays.add(delay)
                assertEquals(0x04, data[i + 3].toInt() and 0xFF)
            }
            i++
        }
        assertEquals(3, delays.size)
        assertTrue(delays.all { it == 10 })
    }

    @Test
    fun `loop extension is written`() {
        val data = encode(frames = listOf(0xFF0000, 0x00FF00))
        assertTrue(String(data, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }

    @Test
    fun `frame count is reported`() {
        val sink = ByteArrayOutputStream()
        val palette = GifPaletteQuantizer.default()
        val writer = GifWriter(sink, 8, 8)
        writer.begin(palette)
        repeat(5) { writer.writeFrame(palette, 50, solid(it * 0x202020)) }
        writer.finish()
        assertEquals(5, writer.frameCount)
    }

    @Test
    fun `palette maps its own colors back to themselves`() {
        val palette = GifPaletteQuantizer.default()
        for (i in 0 until 256) {
            assertEquals(i, palette.indexOf(palette.colors[i]))
        }
    }

    @Test
    fun `learnt palette keeps black and white close`() {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) {
            if ((it % width) < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val palette = GifPaletteQuantizer.learn(width, height, pixels, sampleFactor = 4)
        val black = palette.colors[palette.indexOf(0x000000)]
        val white = palette.colors[palette.indexOf(0xFFFFFF)]
        val blackLuma = ((black shr 16) and 0xFF) + ((black shr 8) and 0xFF) + (black and 0xFF)
        val whiteLuma = ((white shr 16) and 0xFF) + ((white shr 8) and 0xFF) + (white and 0xFF)
        assertTrue("black too bright: $blackLuma", blackLuma < 120)
        assertTrue("white too dark: $whiteLuma", whiteLuma > 645)
    }
}
