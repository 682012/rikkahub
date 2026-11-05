package me.rerere.rikkahub.data.organization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ImageClassifier"

/**
 * 一次图片识别请求。
 *
 * 只做三件事：把原图缩到模型能接受的小尺寸 JPEG、发一次 chat completion、解析结果。
 * 不内置任何本地模型权重，不自动触发，一次调用只发一个请求。
 *
 * 内存：解码按 inSampleSize 采样，长边最多 [MAX_EDGE]；alpha 先铺白底；
 * 压缩完立刻释放 Bitmap，临时文件用完就删。
 */
class ImageClassifier(
    private val context: Context,
    private val providerManager: ProviderManager,
) {
    companion object {
        /** 发给模型的最长边。1024 对内容分类够用，又能把体积压到几百 KB。 */
        const val MAX_EDGE = 1024
        private const val JPEG_QUALITY = 82
        /** 单张请求体上限，超过就降质量重压一次。 */
        private const val MAX_JPEG_BYTES = 1_200 * 1024
        private const val MAX_TOKENS = 256
        // Keep bitmap allocation bounded independently of network concurrency.
        private val decodeSlots = Semaphore(2)
    }

    /**
     * @throws kotlinx.coroutines.CancellationException 取消时原样抛出
     * @throws Exception 解码失败或接口失败，由调用方按单张失败处理
     */
    suspend fun classify(
        file: File,
        model: Model,
        providerSetting: ProviderSetting,
    ): ParsedClassification {
        require(file.isFile) { "文件不存在：${file.name}" }
        val temp = decodeSlots.withPermit { prepareImage(file) }
        try {
            val provider = providerManager.getProviderByType(providerSetting)
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(ImageClassificationPrompt.SYSTEM),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            UIMessagePart.Text(ImageClassificationPrompt.USER),
                            UIMessagePart.Image(temp.toUri().toString()),
                        ),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    maxTokens = MAX_TOKENS,
                    customHeaders = model.customHeaders,
                    // TextGenerationParams 的字段是 customBody（单数），Model 上是 customBodies
                    customBody = model.customBodies,
                    disableToolCalls = true,
                    sessionId = Uuid.random().toString(),
                ),
            )
            val content = result.message.toText()
            Log.d(TAG, "classify: ${file.name} -> ${content.take(200)}")
            return ImageClassificationParser.parse(content)
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 解码 → 摆正 → 铺白底 → 压成小 JPEG 临时文件。所有 Bitmap 在返回前释放，失败也不留临时文件。 */
    private suspend fun prepareImage(source: File): File = withContext(Dispatchers.IO) {
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

            val w = upright.width
            val h = upright.height
            val ratio = MAX_EDGE.toFloat() / maxOf(w, h)
            val resized = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    upright,
                    (w * ratio).toInt().coerceAtLeast(1),
                    (h * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                null
            }
            scaled = resized
            val target = resized ?: upright

            // 有透明通道的图在 JPEG 里会变黑底，先铺白
            val opaque = if (target.hasAlpha()) flattenOnWhite(target).also { flattened = it } else target

            val dir = File(context.cacheDir, "image_organizer").apply { mkdirs() }
            val file = File(dir, "classify_${Uuid.random()}.jpg")
            temp = file
            file.outputStream().use { output ->
                opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            if (file.length() > MAX_JPEG_BYTES) {
                // 还太大就降质量重压一次，别把原图整个发出去
                file.outputStream().use { output ->
                    opaque.compress(Bitmap.CompressFormat.JPEG, 60, output)
                }
            }
            file
        } catch (e: Throwable) {
            temp?.delete()
            throw e
        } finally {
            listOfNotNull(flattened, scaled, oriented)
                .filter { it !== decoded }
                .forEach { runCatching { it.recycle() } }
            runCatching { decoded.recycle() }
        }
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
        val matrix = android.graphics.Matrix()
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
