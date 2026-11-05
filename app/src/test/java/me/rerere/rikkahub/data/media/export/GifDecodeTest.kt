package me.rerere.rikkahub.data.media.export

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Decode bytes produced by the actual Kotlin writer with an independent GIF decoder. */
class GifDecodeTest {
    @Test fun realDecoderReadsDistinctFramesAndFullDictionary() {
        val sink = ByteArrayOutputStream()
        val palette = GifPaletteQuantizer.default()
        val size = 256
        GifWriter(sink, size, size).apply {
            begin(palette)
            repeat(3) { frame ->
                writeFrame(null, 100) { y, pixels ->
                    for (x in pixels.indices) pixels[x] = palette.colors[((x * 73 + y * 31 + frame * 83) xor (x * y)) and 255]
                }
            }
            finish()
        }
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(ByteArrayInputStream(sink.toByteArray())).use { input ->
            reader.input = input
            assertEquals(3, reader.getNumImages(true))
            repeat(3) { frame ->
                val image = reader.read(frame)
                assertEquals(size, image.width)
                for (y in 0 until size step 17) for (x in 0 until size step 19) {
                    val index = ((x * 73 + y * 31 + frame * 83) xor (x * y)) and 255
                    assertEquals(palette.colors[index] and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF)
                }
            }
        }
        reader.dispose()
    }
}
