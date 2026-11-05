package me.rerere.rikkahub.data.media.export

/*
 * GIF 调色板：
 * - [GifPaletteQuantizer] 把一帧像素学成 256 色（NeuQuant 思路），并把任意 RGB 映射到最近的颜色；
 * - [GifPalette.medianCut] 是纯 Kotlin 的中位切分量化，作为不需要逐像素训练的替代方案（用于背景不透明
 *   的静态导出，速度快、结果稳定）。
 *
 * 出处与许可见 GifNeuQuant.kt 头部：NeuQuant 算法由 Anthony Dekker 发表（1994），Java 实现来自
 * Kevin Weiner 的 GIFEncoder 血统，长期以可自由使用/修改/再分发的形式发布。本文件为 Kotlin 改写，
 * 不包含任何第三方二进制，也没有新增依赖。
 */

/** 一帧到 GIF 颜色下标的映射器。 */
internal interface GifColorMapper {
    /** `0xRRGGBB` 在调色板里的下标（0..255）。 */
    fun indexOf(rgb: Int): Int

    /** 调色板，每项 `0xRRGGBB`，长度固定 256。 */
    val colors: IntArray
}

/**
 * NeuQuant 神经网络量化。
 *
 * 训练是对整帧随机抽样进行的，完成后只保留调色板（256×3 字节）；[indexOf] 从最接近的绿色档位向两侧搜索，
 * 照片通常能在个位数的比较里命中。
 */
internal class GifPaletteQuantizer private constructor(
    override val colors: IntArray,
) : GifColorMapper {
    /** 调色板按绿色分量排序后的绿色值，[indexOf] 二分定位起点。 */
    private val greens = IntArray(colors.size) { (colors[it] shr 8) and 0xFF }

    override fun indexOf(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        // 从绿色最接近的位置向两侧扩展
        var upper = 0
        while (upper < 256 && greens[upper] < g) upper++
        var lower = upper - 1
        var best = -1
        var bestDist = Int.MAX_VALUE
        while (upper < 256 || lower >= 0) {
            if (upper < 256) {
                val d = squaredDistance(colors[upper], r, g, b)
                if (d < bestDist) {
                    bestDist = d
                    best = upper
                }
                if (d == 0) return upper
                upper++
            }
            if (lower >= 0) {
                val d = squaredDistance(colors[lower], r, g, b)
                if (d < bestDist) {
                    bestDist = d
                    best = lower
                }
                if (d == 0) return lower
                lower--
            }
        }
        return if (best >= 0) best else 0
    }

    private fun squaredDistance(color: Int, r: Int, g: Int, b: Int): Int {
        val dr = r - ((color shr 16) and 0xFF)
        val dg = g - ((color shr 8) and 0xFF)
        val db = b - (color and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    companion object {
        private const val NET_SIZE = 256

        /** 从一帧像素学习调色板；[sampleFactor] 越大越快、越粗（原版常用 10）。 */
        fun learn(width: Int, height: Int, pixels: IntArray, sampleFactor: Int = 10): GifPaletteQuantizer {
            require(width > 0 && height > 0) { "size must be positive" }
            require(pixels.size >= width * height) { "pixels shorter than width * height" }
            val total = width * height
            val sampling = (total / sampleFactor).coerceAtLeast(NET_SIZE * 2)

            // 网络初值：沿灰度渐变铺满 256 个神经元
            val netR = IntArray(NET_SIZE) { (it shl 2) and 0xFF }
            val netG = IntArray(NET_SIZE) { (it shl 2) and 0xFF }
            val netB = IntArray(NET_SIZE) { (it shl 2) and 0xFF }
            val freq = IntArray(NET_SIZE)

            // 训练半径按样本数线性收缩，学习率随轮次衰减
            val step = (total / sampling).coerceAtLeast(1)
            val alphadec = 30.0 + (sampleFactor - 1) / 3.0
            var alpha = 1024.0
            var sampleIndex = 0
            var sampleCounter = 0
            while (sampleCounter < sampling) {
                val argb = pixels[sampleIndex]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // 找最近神经元（欧氏距离）
                var bestPos = 0
                var bestDist = Int.MAX_VALUE
                for (i in 0 until NET_SIZE) {
                    val dr = netR[i] - r
                    val dg = netG[i] - g
                    val db = netB[i] - b
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestDist) {
                        bestDist = d
                        bestPos = i
                    }
                }

                // 向样本颜色靠拢，邻居按半径衰减共享一部分调整
                val radius = ((NET_SIZE / 8.0) * (sampling - sampleCounter) / sampling).toInt()
                val low = (bestPos - radius).coerceAtLeast(0)
                val high = (bestPos + radius).coerceAtMost(NET_SIZE - 1)
                val rate = alpha / (1.0 + bestDist / 256.0)
                for (i in low..high) {
                    val ring = i - bestPos
                    val falloff = rate * 64.0 / (ring * ring + 1.0)
                    netR[i] = (netR[i] + (r - netR[i]) * falloff / 1024.0).toInt().coerceIn(0, 255)
                    netG[i] = (netG[i] + (g - netG[i]) * falloff / 1024.0).toInt().coerceIn(0, 255)
                    netB[i] = (netB[i] + (b - netB[i]) * falloff / 1024.0).toInt().coerceIn(0, 255)
                }
                freq[bestPos]++
                sampleCounter++
                sampleIndex += step
                if (sampleIndex >= total) sampleIndex -= total
                alpha -= alpha / alphadec
                if (alpha < 32.0) alpha = 32.0
            }

            // 从未被选中的神经元改成最近被选中邻居的颜色，避免出现孤立色
            for (i in 0 until NET_SIZE) {
                if (freq[i] != 0) continue
                var nearest = -1
                var nearestDist = Int.MAX_VALUE
                for (j in 0 until NET_SIZE) {
                    if (freq[j] == 0) continue
                    val dr = netR[j] - netR[i]
                    val dg = netG[j] - netG[i]
                    val db = netB[j] - netB[i]
                    val d = dr * dr + dg * dg + db * db
                    if (d < nearestDist) {
                        nearestDist = d
                        nearest = j
                    }
                }
                if (nearest >= 0) {
                    netR[i] = netR[nearest]
                    netG[i] = netG[nearest]
                    netB[i] = netB[nearest]
                }
            }

            val colors = IntArray(NET_SIZE) { i -> (netR[i] shl 16) or (netG[i] shl 8) or netB[i] }
            return from(colors)
        }

        /** 用给定颜色构造（按绿色排序，[indexOf] 依赖这个顺序）。 */
        fun from(colors: IntArray): GifPaletteQuantizer {
            require(colors.size == NET_SIZE) { "palette must have exactly 256 colors" }
            return GifPaletteQuantizer(colors.sortedBy { (it shr 8) and 0xFF }.toIntArray())
        }

        /** 3:3:2 位分配的通话色板，作为没有任何样本时的兜底色板。 */
        fun default(): GifPaletteQuantizer = from(
            IntArray(NET_SIZE) { i ->
                val r = (i shr 5) and 0x07
                val g = (i shr 2) and 0x07
                val b = i and 0x03
                val r8 = (r shl 5) or (r shl 2) or (r shr 1)
                val g8 = (g shl 5) or (g shl 2) or (g shr 1)
                val b8 = (b shl 6) or (b shl 4) or (b shl 2) or b
                (r8 shl 16) or (g8 shl 8) or b8
            }
        )
    }
}

/**
 * 中位切分量化：把颜色按通道跨度最宽的那一维反复切开，直到得到 [size] 个箱子，每箱取平均色。
 * 只用于静态图片导出（不需要逐帧训练），结果稳定、与帧顺序无关。
 */
internal object GifPalette {
    fun medianCut(pixels: IntArray, size: Int = 256): GifPaletteQuantizer {
        require(pixels.isNotEmpty()) { "no pixels" }
        val opaque = pixels.filter { (it ushr 24) > 0 }
        val source = if (opaque.isEmpty()) pixels else opaque.toIntArray()
        var boxes = listOf(ColorBox(source))
        while (boxes.size < size) {
            val target = boxes.filter { it.count > 1 }.maxByOrNull { it.longestSide }
                ?: break
            boxes = boxes - target + target.split()
        }
        val colors = IntArray(size)
        boxes.forEachIndexed { index, box ->
            if (index < size) colors[index] = box.average()
        }
        // 不足 256 箱时用最后一箱的颜色补齐，避免出现纯黑
        val fill = colors[(boxes.size - 1).coerceAtLeast(0)]
        for (i in boxes.size until size) colors[i] = fill
        return GifPaletteQuantizer.from(colors)
    }

    private class ColorBox(private val pixels: IntArray) {
        val count: Int get() = pixels.size

        private fun channel(color: Int, index: Int): Int = when (index) {
            0 -> (color shr 16) and 0xFF
            1 -> (color shr 8) and 0xFF
            else -> color and 0xFF
        }

        val longestSide: Int
            get() {
                var best = 0
                var bestSpan = -1
                for (c in 0 until 3) {
                    var min = 255
                    var max = 0
                    for (p in pixels) {
                        val v = channel(p, c)
                        if (v < min) min = v
                        if (v > max) max = v
                    }
                    val span = max - min
                    if (span > bestSpan) {
                        bestSpan = span
                        best = c
                    }
                }
                return bestSpan
            }

        fun split(): List<ColorBox> {
            var width = 0
            var bestSpan = -1
            for (c in 0 until 3) {
                var min = 255
                var max = 0
                for (p in pixels) {
                    val v = channel(p, c)
                    if (v < min) min = v
                    if (v > max) max = v
                }
                if (max - min > bestSpan) {
                    bestSpan = max - min
                    width = c
                }
            }
            val sorted = pixels.sortedBy { channel(it, width) }
            val mid = sorted.size / 2
            return listOf(ColorBox(sorted.subList(0, mid).toIntArray()), ColorBox(sorted.subList(mid, sorted.size).toIntArray()))
        }

        fun average(): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            for (p in pixels) {
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            val n = pixels.size.coerceAtLeast(1)
            return (((r / n).toInt() and 0xFF) shl 16) or (((g / n).toInt() and 0xFF) shl 8) or ((b / n).toInt() and 0xFF)
        }
    }
}
