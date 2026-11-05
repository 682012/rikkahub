package me.rerere.rikkahub.data.files

import java.io.File
import java.io.InputStream

/** Full container walk, not an LZW decoder. Rejects truncated records and missing trailer. */
object GifStructure {
    data class Info(val width: Int, val height: Int, val frames: Int, val durationMs: Long)

    fun read(file: File): Info = file.inputStream().buffered().use { input ->
        fun byte(): Int = input.read().also { require(it >= 0) { "GIF 文件不完整" } }
        fun word(): Int = byte() or (byte() shl 8)
        fun skip(count: Int) {
            repeat(count) { byte() }
        }
        fun subBlocks() {
            while (true) {
                val count = byte()
                if (count == 0) return
                skip(count)
            }
        }
        val header = ByteArray(6) { byte().toByte() }.toString(Charsets.US_ASCII)
        require(header == "GIF87a" || header == "GIF89a") { "不是 GIF 文件" }
        val width = word()
        val height = word()
        require(width > 0 && height > 0) { "GIF 尺寸无效" }
        val packed = byte()
        skip(2)
        if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var frames = 0
        var duration = 0L
        var delay = 0
        var ended = false
        while (!ended) {
            when (byte()) {
                0x3b -> ended = true
                0x21 -> {
                    if (byte() == 0xf9) {
                        require(byte() == 4) { "GIF 控制块损坏" }
                        byte()
                        delay = word() * 10
                        byte()
                        require(byte() == 0) { "GIF 控制块缺少结尾" }
                    } else subBlocks()
                }
                0x2c -> {
                    val left = word()
                    val top = word()
                    val frameWidth = word()
                    val frameHeight = word()
                    require(frameWidth > 0 && frameHeight > 0 &&
                        left + frameWidth <= width && top + frameHeight <= height) { "GIF 帧尺寸无效" }
                    val local = byte()
                    if (local and 0x80 != 0) skip(3 * (1 shl ((local and 7) + 1)))
                    require(byte() in 2..8) { "GIF 编码无效" }
                    subBlocks()
                    frames++
                    require(frames <= 5000) { "GIF 帧数过多" }
                    duration += delay
                    delay = 0
                }
                else -> error("GIF 数据块损坏")
            }
        }
        require(frames > 0) { "GIF 没有画面" }
        Info(width, height, frames, duration)
    }
}
