package me.rerere.mediagen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * [MediaFileSignature] 的 fixture 测试。
 *
 * fixture 都是手写的最小头部字节（只覆盖识别所需的字段），刻意不含完整图像数据：这个模块只做嗅探，不解码。
 * "能否真的解码出来"必须由 App 侧的 decoder 验证覆盖，这里不试图证明。
 */
class MediaFileSignatureTest {

    // ------------------------------------------------------------ 拒绝输入

    @Test
    fun `empty input is unknown`() {
        val result = MediaFileSignature.detect(ByteArray(0))
        assertTrue(result is MediaFileSignature.Unknown)
        assertEquals(0, result.detectedBytes)
        assertNull(result.mime)
        assertNull(result.extension)
        assertNull(result.isAnimatedHint)
        assertNull(result.width)
        assertNull(result.height)
    }

    @Test
    fun `null input is unknown`() {
        val result = MediaFileSignature.detect(null)
        assertTrue(result is MediaFileSignature.Unknown)
        assertEquals(0, result.detectedBytes)
        assertNull(result.mime)
    }

    @Test
    fun `too short input is unknown`() {
        val result = MediaFileSignature.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E))
        assertTrue(result is MediaFileSignature.Unknown)
        assertEquals(3, result.detectedBytes)
    }

    @Test
    fun `html is rejected even when it inlines an svg`() {
        val html = """
            |<!DOCTYPE html>
            |<html><body><svg xmlns="http://www.w3.org/2000/svg"></svg></body></html>
        """.trimMargin()
        assertTrue(MediaFileSignature.detect(html.toBytes()) is MediaFileSignature.Unknown)

        val withoutDoctype = "<html><head><title>t</title></head><body><svg/></body></html>"
        assertTrue(MediaFileSignature.detect(withoutDoctype.toBytes()) is MediaFileSignature.Unknown)

        // 根是 div 的片段不是独立 SVG 资源。
        val fragment = """<div><svg xmlns="http://www.w3.org/2000/svg"/></div>"""
        assertTrue(MediaFileSignature.detect(fragment.toBytes()) is MediaFileSignature.Unknown)
    }

    @Test
    fun `json and plain text are rejected`() {
        val json = """{"error":{"code":"invalid_request","message":"bad model"}}"""
        val result = MediaFileSignature.detect(json.toBytes())
        assertTrue(result is MediaFileSignature.Unknown)
        assertNull(result.mime)

        assertTrue(MediaFileSignature.detect("Internal Server Error".toBytes()) is MediaFileSignature.Unknown)
    }

    @Test
    fun `declared mime is not evidence`() {
        // 自称 image/png 的 JSON 必须判为 Unknown，不能顺着声明认成 PNG。
        val json = """{"url":"https://example.com/a.png","mime":"image/png"}"""
        assertTrue(MediaFileSignature.detect(json.toBytes()) is MediaFileSignature.Unknown)

        // 反向：真实 PNG 字节即使没有任何声明也认得出来。
        assertTrue(MediaFileSignature.detect(pngHeader(32, 16)) is MediaFileSignature.PNG)
    }

    // ------------------------------------------------------------ PNG

    @Test
    fun `png static reads size`() {
        val result = MediaFileSignature.detect(pngHeader(32, 16))
        assertTrue(result is MediaFileSignature.PNG)
        assertEquals("image/png", result.mime)
        assertEquals("png", result.extension)
        assertEquals(32, result.width)
        assertEquals(16, result.height)
        assertEquals(false, result.isAnimatedHint)
    }

    @Test
    fun `apng is flagged animated`() {
        val result = MediaFileSignature.detect(pngWithActl(8, 8))
        assertTrue(result is MediaFileSignature.PNG)
        assertEquals(true, result.isAnimatedHint)
        assertEquals(8, result.width)
        assertEquals(8, result.height)
    }

    @Test
    fun `png signature without IHDR is not accepted`() {
        val bogus = pngSignature() + byteArrayOf(0, 0, 0, 0) + "JUNK".toBytes() + ByteArray(16)
        assertTrue(MediaFileSignature.detect(bogus) is MediaFileSignature.Unknown)
    }

    @Test
    fun `huge declared png size does not overflow`() {
        // 宽高填满 u32：超出 Int.MAX_VALUE，应退化为 null 而不是负数或溢出值。
        val ihdr = ByteArray(13)
        for (i in 0 until 8) ihdr[i] = 0xFF.toByte()
        val result = MediaFileSignature.detect(pngSignature() + pngChunk("IHDR", ihdr))
        assertTrue(result is MediaFileSignature.PNG)
        assertNull(result.width)
        assertNull(result.height)
    }

    // ------------------------------------------------------------ JPEG

    @Test
    fun `jpeg reads size from sof0`() {
        val result = MediaFileSignature.detect(jpegHeader(640, 480))
        assertTrue(result is MediaFileSignature.JPEG)
        assertEquals("image/jpeg", result.mime)
        assertEquals("jpg", result.extension)
        assertEquals(640, result.width)
        assertEquals(480, result.height)
        assertNull(result.isAnimatedHint)
    }

    @Test
    fun `jpeg with truncated header still identifies but omits size`() {
        val truncated = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) +
            ByteArray(8)
        val result = MediaFileSignature.detect(truncated)
        assertTrue(result is MediaFileSignature.JPEG)
        assertNull(result.width)
        assertNull(result.height)
    }

    // ------------------------------------------------------------ GIF

    @Test
    fun `gif static reads size`() {
        val result = MediaFileSignature.detect(gifHeader(120, 90, frames = 1))
        assertTrue(result is MediaFileSignature.GIF)
        assertEquals("image/gif", result.mime)
        assertEquals("gif", result.extension)
        assertEquals("GIF89a", (result as MediaFileSignature.GIF).version)
        assertEquals(120, result.width)
        assertEquals(90, result.height)
        assertEquals(false, result.isAnimatedHint)
    }

    @Test
    fun `multi frame gif is flagged animated`() {
        val result = MediaFileSignature.detect(gifHeader(16, 16, frames = 3))
        assertTrue(result is MediaFileSignature.GIF)
        assertEquals(true, result.isAnimatedHint)
        assertEquals(16, result.width)
    }

    @Test
    fun `gif87a is accepted`() {
        val bytes = "GIF87a".toBytes() + byteArrayOf(10, 0, 4, 0) + ByteArray(8)
        val result = MediaFileSignature.detect(bytes)
        assertTrue(result is MediaFileSignature.GIF)
        assertEquals("GIF87a", (result as MediaFileSignature.GIF).version)
        assertEquals(10, result.width)
        assertEquals(4, result.height)
    }

    // ------------------------------------------------------------ WebP

    @Test
    fun `webp lossy reads size`() {
        val result = MediaFileSignature.detect(webpVp8(300, 200))
        assertTrue(result is MediaFileSignature.WebP)
        assertEquals("image/webp", result.mime)
        assertEquals("webp", result.extension)
        assertEquals(300, result.width)
        assertEquals(200, result.height)
        assertEquals(false, result.isAnimatedHint)
    }

    @Test
    fun `webp lossless reads size`() {
        val result = MediaFileSignature.detect(webpVp8l(9, 5))
        assertTrue(result is MediaFileSignature.WebP)
        assertEquals(9, result.width)
        assertEquals(5, result.height)
        assertEquals(false, result.isAnimatedHint)
    }

    @Test
    fun `animated webp is flagged`() {
        val result = MediaFileSignature.detect(webpVp8x(64, 48, animated = true))
        assertTrue(result is MediaFileSignature.WebP)
        assertEquals(true, result.isAnimatedHint)
        assertEquals(64, result.width)
        assertEquals(48, result.height)
    }

    @Test
    fun `riff that is not webp is rejected`() {
        val wav = "RIFF".toBytes() + byteArrayOf(0x24, 0, 0, 0) + "WAVE".toBytes() + ByteArray(8)
        assertTrue(MediaFileSignature.detect(wav) is MediaFileSignature.Unknown)
    }

    // ------------------------------------------------------------ MP4 / WebM

    @Test
    fun `mp4 isobox is recognized as video`() {
        val result = MediaFileSignature.detect(mp4Ftyp("isom"))
        assertTrue(result is MediaFileSignature.MP4)
        assertEquals("video/mp4", result.mime)
        assertEquals("mp4", result.extension)
        assertEquals("isom", (result as MediaFileSignature.MP4).brand)
        assertEquals(MediaKindHint.VIDEO, (result as MediaFileSignature.MP4).kindHint)
    }

    @Test
    fun `m4a brand is not reported as video`() {
        val result = MediaFileSignature.detect(mp4Ftyp("M4A "))
        assertTrue(result is MediaFileSignature.MP4)
        assertEquals(MediaKindHint.AUDIO, (result as MediaFileSignature.MP4).kindHint)
        assertEquals("audio/mp4", result.mime)
        assertNull(result.extension)
    }

    @Test
    fun `heif brand gives no mime`() {
        val result = MediaFileSignature.detect(mp4Ftyp("heic"))
        assertTrue(result is MediaFileSignature.MP4)
        assertEquals(MediaKindHint.IMAGE, (result as MediaFileSignature.MP4).kindHint)
        assertNull(result.mime)
    }

    @Test
    fun `webm doctype is recognized`() {
        val result = MediaFileSignature.detect(ebmlHeader("webm"))
        assertTrue(result is MediaFileSignature.WebM)
        assertEquals("video/webm", result.mime)
        assertEquals("webm", result.extension)
        assertEquals("webm", (result as MediaFileSignature.WebM).docType)
    }

    @Test
    fun `matroska doctype is recognized without mime`() {
        val result = MediaFileSignature.detect(ebmlHeader("matroska"))
        assertTrue(result is MediaFileSignature.Matroska)
        assertNull(result.mime)
        assertEquals("mkv", result.extension)
    }

    @Test
    fun `ebml without a known doctype is not guessed`() {
        val bytes = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(20)
        assertTrue(MediaFileSignature.detect(bytes) is MediaFileSignature.Unknown)
    }

    // ------------------------------------------------------------ SVG

    @Test
    fun `svg with xml declaration is recognized`() {
        val svg = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>
        """.trimMargin()
        val result = MediaFileSignature.detect(svg.toBytes())
        assertTrue(result is MediaFileSignature.SVG)
        assertEquals("image/svg+xml", result.mime)
        assertEquals("svg", result.extension)
        assertTrue((result as MediaFileSignature.SVG).xmlDeclaration)
        assertEquals("svg", (result as MediaFileSignature.SVG).rootElement)
    }

    @Test
    fun `svg without declaration and with bom is recognized`() {
        val svg = "\uFEFF  <svg xmlns=\"http://www.w3.org/2000/svg\"/>"
        val result = MediaFileSignature.detect(svg.toBytes())
        assertTrue(result is MediaFileSignature.SVG)
        assertFalse((result as MediaFileSignature.SVG).xmlDeclaration)
    }

    @Test
    fun `namespaced svg root is recognized`() {
        val svg = """<?xml version="1.0"?><s:svg xmlns:s="http://www.w3.org/2000/svg"></s:svg>"""
        val result = MediaFileSignature.detect(svg.toBytes())
        assertTrue(result is MediaFileSignature.SVG)
        assertEquals("s:svg", (result as MediaFileSignature.SVG).rootElement)
    }

    @Test
    fun `plain xml that is not svg is rejected`() {
        val xml = """<?xml version="1.0"?><catalog><item/></catalog>"""
        assertTrue(MediaFileSignature.detect(xml.toBytes()) is MediaFileSignature.Unknown)
    }

    @Test
    fun `unterminated xml declaration is rejected`() {
        val svg = """<?xml version="1.0" <svg xmlns="http://www.w3.org/2000/svg"/>"""
        assertTrue(MediaFileSignature.detect(svg.toBytes()) is MediaFileSignature.Unknown)
    }

    // ------------------------------------------------------------ 窗口与边界

    @Test
    fun `input larger than header window only uses the first 64 KiB`() {
        val padding = ByteArray(MediaFileSignature.MAX_HEADER_BYTES) { 0x20 }
        val bytes = padding + "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toBytes()
        val result = MediaFileSignature.detect(bytes)
        assertTrue(result is MediaFileSignature.Unknown)
        assertEquals(MediaFileSignature.MAX_HEADER_BYTES, result.detectedBytes)
    }

    @Test
    fun `detectedBytes reports the actual inspected length`() {
        val bytes = pngHeader(4, 4)
        assertEquals(bytes.size, MediaFileSignature.detect(bytes).detectedBytes)
    }

    @Test
    fun `random binary is unknown`() {
        val bytes = ByteArray(64) { (it * 37 % 251).toByte() }
        assertTrue(MediaFileSignature.detect(bytes) is MediaFileSignature.Unknown)
    }

    @Test
    fun `truncated signatures do not crash or guess`() {
        val prefixes = listOf(
            pngSignature(),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            "GIF89a".toBytes(),
            "RIFF".toBytes() + byteArrayOf(0, 0, 0, 0) + "WEBP".toBytes(),
            "RIFF".toBytes() + byteArrayOf(0, 0, 0, 0) + "WEB".toBytes(),
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
            "ftyp".toBytes(),
        )
        for (prefix in prefixes) {
            for (length in 0..prefix.size) {
                val result = MediaFileSignature.detect(prefix.copyOf(length))
                assertNull("$result must not claim a mime for a ${length}B prefix", result.mime)
            }
        }
    }

    // ------------------------------------------------------------ fixtures

    private fun String.toBytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun pngSignature(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val ihdr = ByteArray(13)
        writeUInt32BE(ihdr, 0, width)
        writeUInt32BE(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // color type RGBA
        return pngSignature() + pngChunk("IHDR", ihdr) + pngChunk("IEND", ByteArray(0))
    }

    private fun pngWithActl(width: Int, height: Int): ByteArray {
        val ihdr = ByteArray(13)
        writeUInt32BE(ihdr, 0, width)
        writeUInt32BE(ihdr, 4, height)
        ihdr[8] = 8
        ihdr[9] = 6
        val actl = ByteArray(8)
        writeUInt32BE(actl, 0, 2) // num_frames
        return pngSignature() + pngChunk("IHDR", ihdr) + pngChunk("acTL", actl) +
            pngChunk("IDAT", ByteArray(4)) + pngChunk("IEND", ByteArray(0))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUInt32BE(out, data.size)
        out.write(type.toBytes())
        out.write(data)
        writeUInt32BE(out, 0) // CRC 不参与识别
        return out.toByteArray()
    }

    private fun jpegHeader(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        // APP0 / JFIF
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
                0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            ),
        )
        // SOF0: 长度(2) 精度(1) 高(2) 宽(2) 分量数(1) ...
        out.write(
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
                (height shr 8).toByte(), height.toByte(),
                (width shr 8).toByte(), width.toByte(),
                0x03,
                0x01, 0x22, 0x00,
                0x02, 0x11, 0x01,
                0x03, 0x11, 0x01,
            ),
        )
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        return out.toByteArray()
    }

    private fun gifHeader(width: Int, height: Int, frames: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toBytes())
        out.write(byteArrayOf(width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()))
        out.write(byteArrayOf(0x00, 0x00, 0x00)) // packed / background / aspect
        if (frames > 1) {
            out.write(0x21)
            out.write(0xFF)
            out.write(0x0B)
            out.write("NETSCAPE2.0".toBytes())
            out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        }
        repeat(frames) {
            out.write(0x2C)
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            out.write(byteArrayOf(width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()))
            out.write(0x00) // packed
            out.write(0x02) // LZW min code size
            out.write(0x02) // 子块长度
            out.write(byteArrayOf(0x4C, 0x01))
            out.write(0x00) // 块终止
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    private fun webpVp8(width: Int, height: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0x30, 0x01, 0x00)) // frame tag：key frame
        payload.write(byteArrayOf(0x9D.toByte(), 0x01, 0x2A)) // sync code
        payload.write(byteArrayOf(width.toByte(), ((width shr 8) and 0x3F).toByte()))
        payload.write(byteArrayOf(height.toByte(), ((height shr 8) and 0x3F).toByte()))
        payload.write(ByteArray(8))
        return riffWebp("VP8 ", payload.toByteArray())
    }

    private fun webpVp8l(width: Int, height: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0x2F)
        val bits = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        payload.write(
            byteArrayOf(
                bits.toByte(),
                (bits shr 8).toByte(),
                (bits shr 16).toByte(),
                (bits shr 24).toByte(),
            ),
        )
        payload.write(ByteArray(4))
        return riffWebp("VP8L", payload.toByteArray())
    }

    private fun webpVp8x(width: Int, height: Int, animated: Boolean): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(if (animated) 0x02 else 0x00)
        payload.write(ByteArray(3)) // reserved
        val w = width - 1
        val h = height - 1
        payload.write(byteArrayOf(w.toByte(), (w shr 8).toByte(), (w shr 16).toByte()))
        payload.write(byteArrayOf(h.toByte(), (h shr 8).toByte(), (h shr 16).toByte()))
        return riffWebp("VP8X", payload.toByteArray())
    }

    private fun riffWebp(chunkType: String, payload: ByteArray): ByteArray {
        val chunk = ByteArrayOutputStream()
        chunk.write(chunkType.toBytes())
        writeUInt32LE(chunk, payload.size)
        chunk.write(payload)
        if (payload.size % 2 == 1) chunk.write(0) // padding

        val out = ByteArrayOutputStream()
        out.write("RIFF".toBytes())
        writeUInt32LE(out, 4 + chunk.size())
        out.write("WEBP".toBytes())
        out.write(chunk.toByteArray())
        return out.toByteArray()
    }

    private fun mp4Ftyp(majorBrand: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeUInt32BE(out, 24) // box size
        out.write("ftyp".toBytes())
        out.write(majorBrand.toBytes())
        writeUInt32BE(out, 512) // minor version
        out.write("isom".toBytes())
        return out.toByteArray()
    }

    private fun ebmlHeader(docType: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        out.write(0x9F) // EBML header size (vint)
        out.write(ByteArray(5)) // 占位：EBMLVersion 等元素的空壳
        out.write(0x42) // DocType element id 0x4282
        out.write(0x82)
        out.write(0x80 or docType.length)
        out.write(docType.toBytes())
        return out.toByteArray()
    }

    private fun writeUInt32BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUInt32BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeUInt32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
