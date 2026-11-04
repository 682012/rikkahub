package me.rerere.rikkahub.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

/**
 * 逐张识别前的单图准备：把一张图变成"可以安全放进一次视觉请求"的样子。
 *
 * 这里只处理**一张**图，不做任何缓存或网络请求：
 * - 本地图：按 inSampleSize 采样解码 → EXIF 摆正 → 长边 ≤ [MAX_EDGE] → alpha 铺白 → 临时 JPEG，
 *   原图不修改、不覆盖；临时文件由调用方在 finally 删除。
 * - 小体积 data: 内联图：原样传递（单图，体积有上限，本地不解码）。
 * - 大体积 data: 内联图：先落临时文件（有字节上限），再走缩小流程，避免整张大图解码爆内存。
 * - http(s)：原样传递，由供应商自己下载；本地不下载不解码，没有 OOM 风险。
 */
object SequentialImageBitmaps {
    /** 发给模型的最长边。1024 对文字/图表识别够用，又能把体积压到几百 KB。 */
    const val MAX_EDGE = 1024

    private const val JPEG_QUALITY = 82
    private const val MAX_JPEG_BYTES = 1_200 * 1024

    /** 直接原样传递的内联 base64 上限（字符数）；再大就落盘缩小。 */
    private const val MAX_INLINE_CHARS = 2_000_000

    /** 解码内联图时的字节上限：超限直接报错，不把大图整块读进内存。 */
    private const val MAX_INLINE_BYTES = 8 * 1024 * 1024

    private val QUALITY_LADDER = intArrayOf(JPEG_QUALITY, 65, 50)

    /**
     * 准备单张图。返回的 [PreparedImage] 可能带临时文件，调用方必须 finally 里 [PreparedImage.cleanup]。
     *
     * @throws IllegalStateException 图片无法解码或体积无法压到上限内
     */
    suspend fun prepareForSingleRequest(context: Context, url: String): PreparedImage {
        val raw = url.trim()
        require(raw.isNotEmpty()) { "图片地址为空" }
        return when {
            raw.startsWith("data:", ignoreCase = true) && raw.length <= MAX_INLINE_CHARS ->
                PreparedImage(url = raw, width = 0, height = 0)

            raw.startsWith("data:", ignoreCase = true) -> {
                val source = inlineDataToTempFile(context, raw)
                try {
                    downscaleToTempJpeg(context, source)
                } finally {
                    runCatching { source.delete() }
                }
            }

            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) ->
                PreparedImage(url = raw, width = 0, height = 0)

            else -> {
                val path = sequentialImageLocalPath(raw) ?: error("无法识别的图片地址")
                downscaleToTempJpeg(context, File(path))
            }
        }
    }

    /** 采样解码 → 摆正 → 铺白 → JPEG。所有 Bitmap 在返回前回收，失败不留临时文件。 */
    private suspend fun downscaleToTempJpeg(context: Context, source: File): PreparedImage =
        withContext(Dispatchers.IO) {
            require(source.isFile) { "图片文件不存在：${source.name}" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                error("无法解码图片：${source.name}")
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2

            val decoded = BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: error("无法解码图片：${source.name}")

            var oriented: Bitmap? = null
            var scaled: Bitmap? = null
            var flattened: Bitmap? = null
            var temp: File? = null
            try {
                val upright = applyExifRotation(source, decoded)
                oriented = upright
                val width = upright.width
                val height = upright.height
                require(width > 0 && height > 0) { "图片尺寸无效：${source.name}" }

                val ratio = MAX_EDGE.toFloat() / maxOf(width, height)
                val resized = if (ratio < 1f) {
                    Bitmap.createScaledBitmap(
                        upright,
                        (width * ratio).toInt().coerceAtLeast(1),
                        (height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    null
                }
                scaled = resized
                val target = resized ?: upright

                // 有透明通道的图在 JPEG 里会变黑底，先铺白
                val opaque = if (target.hasAlpha()) flattenOnWhite(target).also { flattened = it } else target

                val dir = File(context.cacheDir, "sequential_image").apply { mkdirs() }
                val file = File(dir, "seq_${Uuid.random()}.jpg")
                temp = file
                for (quality in QUALITY_LADDER) {
                    file.outputStream().use { output ->
                        opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    }
                    if (file.length() in 1..MAX_JPEG_BYTES) break
                }
                check(file.length() > 0) { "图片压缩失败：${source.name}" }
                PreparedImage(
                    url = file.toUri().toString(),
                    width = opaque.width,
                    height = opaque.height,
                    tempFile = file,
                )
            } catch (e: Throwable) {
                temp?.let { runCatching { it.delete() } }
                throw e
            } finally {
                listOfNotNull(flattened, scaled, oriented)
                    .filter { it !== decoded }
                    .forEach { runCatching { it.recycle() } }
                runCatching { decoded.recycle() }
            }
        }

    /** 大体积内联图先落盘：写入有字节上限，超限直接报错。 */
    private suspend fun inlineDataToTempFile(context: Context, dataUrl: String): File = withContext(Dispatchers.IO) {
        val comma = dataUrl.indexOf(',')
        require(comma > 0) { "无法解析内联图片" }
        val header = dataUrl.substring(5, comma)
        require(header.contains("base64", ignoreCase = true)) { "内联图片不是 base64" }
        val payload = dataUrl.substring(comma + 1)
        // base64 每 4 个字符对应 3 字节，解码前先挡住明显过大的输入
        if (payload.length / 4L * 3L > MAX_INLINE_BYTES) error("内联图片过大（超过 ${MAX_INLINE_BYTES / 1024 / 1024}MB）")
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        if (bytes.size > MAX_INLINE_BYTES) error("内联图片过大（超过 ${MAX_INLINE_BYTES / 1024 / 1024}MB）")
        val dir = File(context.cacheDir, "sequential_image").apply { mkdirs() }
        File(dir, "inline_${Uuid.random()}.img").apply { writeBytes(bytes) }
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return out
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }
}

/** 一张准备好的单图请求图片：url 可直接塞进 UIMessagePart.Image。 */
data class PreparedImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val tempFile: File? = null,
) {
    /** 用完删临时文件；原图永远不动。 */
    fun cleanup() {
        tempFile?.let { runCatching { it.delete() } }
    }
}
