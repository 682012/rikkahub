package me.rerere.rikkahub.data.media.export

import java.io.OutputStream

/*
 * GIF89a 文件写入：文件头、逻辑屏幕、每帧的图形控制扩展（延时 + 处置方式）与图像描述符、LZW 图像数据、
 * NETSCAPE2.0 循环扩展、文件结束符。结构按 CompuServe GIF89a Specification（1990）实现，LZW 的字典推进方式
 * 参照 Kevin Weiner GIFEncoder 血统的做法（出处见 GifPalette.kt 注释）。
 *
 * 设计约束：
 * - 只按行读取像素（[GifFrameSource]），编码器内存与该帧宽度的几倍同阶，不随帧数增长；
 * - 每帧可带自己的局部色板，也可以共用文件头里的全局色板；
 * - 每帧延时以 1/100 秒写进图形控制扩展，因此每帧的真实停留时间都能被解码器读到；
 * - 处置方式为 1（整帧保留并覆盖），不做帧间差分，不会出现透明区未清导致的拖影；
 * - 只有在 [finish] 成功返回后文件才完整；中途抛异常时调用方负责删掉半成品。
 */

/** 逐行提供一帧 ARGB 像素；[readRow] 写满 `out[0 until width]`。 */
internal fun interface GifFrameSource {
    fun readRow(row: Int, out: IntArray)
}

/** 把多帧写成动画 GIF。[width]/[height] 必须与每帧一致。 */
internal class GifWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
) {
    init {
        require(width in 1..65535 && height in 1..65535) { "invalid GIF size ${width}x${height}" }
    }

    private var begun = false
    private var finished = false
    private var frames = 0
    private val rowBuffer = IntArray(width)
    private val indexedRow = ByteArray(width)
    private val lzw = LzwEncoder(out)
    private var globalPalette: GifPaletteQuantizer? = null

    /** 已写入的帧数。 */
    val frameCount: Int get() = frames

    /** 写文件头与逻辑屏幕描述；[globalPalette] 非空时写全局色板（之后不带局部色板的帧都用它）。 */
    fun begin(globalPalette: GifPaletteQuantizer? = null) {
        check(!begun) { "GIF already begun" }
        begun = true
        this.globalPalette = globalPalette
        out.write(HEADER)
        writeShort(width)
        writeShort(height)
        if (globalPalette != null) {
            // 有全局色板、颜色分辨率 8 位、色板大小 256
            out.write(0xF7)
            out.write(0) // 背景色下标
            out.write(0) // 像素宽高比
            writePalette(globalPalette)
        } else {
            // 无全局色板
            out.write(0x70)
            out.write(0)
            out.write(0)
        }
    }

    /**
     * 追加一帧完整画面。
     *
     * @param palette 这一帧的调色板；null 表示用 [begin] 的全局色板。
     * @param delayMillis 这一帧的停留时间，写入时按 10ms 取整。
     */
    fun writeFrame(palette: GifPaletteQuantizer?, delayMillis: Int, source: GifFrameSource) {
        check(begun && !finished) { "call begin() first and finish() last" }
        val mapper = palette ?: globalPalette ?: error("frame needs a palette")

        // 图形控制扩展：处置方式 1（bit2）+ 延时 + 无透明色
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write(1 shl 2)
        writeShort(((delayMillis + 5) / 10).coerceIn(1, 0xFFFF))
        out.write(0x00) // 透明色下标（未启用）
        out.write(0x00)

        // 图像描述符
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        if (palette !== null && palette !== globalPalette) {
            out.write(0x87) // 局部色板，256 色
            writePalette(palette)
        } else {
            out.write(0x00)
        }

        // 图像数据
        lzw.beginStream(minCodeSize = 8)
        for (row in 0 until height) {
            source.readRow(row, rowBuffer)
            for (x in 0 until width) {
                indexedRow[x] = mapper.indexOf(rowBuffer[x] and 0xFFFFFF).toByte()
            }
            lzw.encode(indexedRow, 0, width)
        }
        lzw.endStream()
        frames++
    }

    /** 写 NETSCAPE2.0 循环扩展与文件结束符；[loopCount] 为 0 表示无限循环。 */
    fun finish(loopCount: Int = 0) {
        if (finished) return
        check(begun) { "GIF has no header" }
        check(frames > 0) { "GIF has no frames" }
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write(NETSCAPE.toByteArray(Charsets.US_ASCII))
        out.write(0x03)
        out.write(0x01)
        writeShort(loopCount.coerceIn(0, 0xFFFF))
        out.write(0x00)
        out.write(0x3B)
        out.flush()
        finished = true
    }

    private fun writePalette(palette: GifPaletteQuantizer) {
        val colors = palette.colors
        for (i in 0 until 256) {
            val c = colors[i]
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private companion object {
        val HEADER = "GIF89a".toByteArray(Charsets.US_ASCII)
        const val NETSCAPE = "NETSCAPE2.0"
    }

    /**
     * GIF 变长码 LZW。码长 9→12，字典满 4096 项时发 clear code 重来；输出按最长 255 字节的数据子块写出。
     *
     * 码长切换的时机与解码器严格对齐：每写完一个码后才检查下一个可用码是否超过当前码长的上限，因此编码器
     * 比解码器早一格分配字典项也不会错位（这是 GIF LZW 的经典陷阱，写错就会得到 broken data stream）。
     * 字典用开放寻址表保存"键 → 码"，键与码分开存，避免把 (前缀 shl 8 | 字节) 打包进一个 Int 时溢出。
     */
    private class LzwEncoder(private val out: OutputStream) {
        private var nBits = 9
        private var maxCode = 0x1FF
        private var freeEnt = 0
        private var clearCode = 0
        private var eofCode = 0
        private var accumulator = 0
        private var accumulatorBits = 0
        private var block = ByteArray(256)
        private var blockLen = 0
        private var prefix = -1
        private var clearPending = false
        private val keys = IntArray(TABLE_SIZE)
        private val codes = IntArray(TABLE_SIZE)

        fun beginStream(minCodeSize: Int) {
            clearCode = 1 shl minCodeSize
            eofCode = clearCode + 1
            freeEnt = clearCode + 2
            nBits = minCodeSize + 1
            maxCode = (1 shl nBits) - 1
            accumulator = 0
            accumulatorBits = 0
            blockLen = 0
            prefix = -1
            clearPending = false
            java.util.Arrays.fill(keys, -1)
            out.write(minCodeSize)
            emit(clearCode)
        }

        fun encode(pixels: ByteArray, offset: Int, length: Int) {
            var i = offset
            val end = offset + length
            if (prefix < 0 && i < end) {
                prefix = pixels[i].toInt() and 0xFF
                i++
            }
            while (i < end) {
                val c = pixels[i].toInt() and 0xFF
                i++
                val found = lookup(prefix, c)
                if (found >= 0) {
                    prefix = found
                    continue
                }
                emit(prefix)
                if (freeEnt < MAX_CODES) {
                    insert(prefix, c, freeEnt)
                    freeEnt++
                } else {
                    // 字典满：先按当前码长写 clear code，再重置码长与字典
                    clearPending = true
                    emit(clearCode)
                    java.util.Arrays.fill(keys, -1)
                    freeEnt = clearCode + 2
                }
                prefix = c
            }
        }

        fun endStream() {
            if (prefix >= 0) emit(prefix)
            emit(eofCode)
            if (accumulatorBits > 0) {
                blockAdd(accumulator and 0xFF)
                accumulator = 0
                accumulatorBits = 0
            }
            flushBlock()
            out.write(0) // 数据子块结束
            prefix = -1
        }

        private fun slot(prefixCode: Int, c: Int): Int = ((prefixCode shl 8) or c) % TABLE_SIZE

        private fun lookup(prefixCode: Int, c: Int): Int {
            val key = (prefixCode shl 8) or c
            var index = slot(prefixCode, c)
            while (true) {
                val stored = keys[index]
                if (stored == -1) return -1
                if (stored == key) return codes[index]
                index++
                if (index == TABLE_SIZE) index = 0
            }
        }

        private fun insert(prefixCode: Int, c: Int, code: Int) {
            val key = (prefixCode shl 8) or c
            var index = slot(prefixCode, c)
            while (keys[index] != -1) {
                if (keys[index] == key) return
                index++
                if (index == TABLE_SIZE) index = 0
            }
            keys[index] = key
            codes[index] = code
        }

        private fun emit(code: Int) {
            accumulator = accumulator or (code shl accumulatorBits)
            accumulatorBits += nBits
            while (accumulatorBits >= 8) {
                blockAdd(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                accumulatorBits -= 8
            }
            // 写完当前码后决定下一个码的宽度：与解码器推进字典的时机一致
            if (clearPending) {
                clearPending = false
                nBits = 9
                maxCode = (1 shl nBits) - 1
            } else if (freeEnt > maxCode && nBits < 12) {
                nBits++
                maxCode = (1 shl nBits) - 1
            }
        }

        private fun blockAdd(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockLen == 0) return
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }

        private companion object {
            const val TABLE_SIZE = 8191
            const val MAX_CODES = 4096
        }
    }
}
