package me.rerere.rikkahub.data.files

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.MediaFileSignature
import java.io.File

/** Validate actual downloaded bytes before declaring a creation successful. Originals are never re-encoded. */
object MediaOutputVerifier {
    const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 256L * 1024 * 1024
    private const val MAX_PIXELS = 64L * 1024 * 1024
    private val decoders = Semaphore(2)

    data class Verified(
        val mimeType: String,
        val extension: String,
        val width: Int,
        val height: Int,
        val durationSeconds: Double? = null,
    )

    suspend fun validateLocal(file: File, expectedVideo: Boolean, requireAnimatedGif: Boolean = false): Verified = withContext(Dispatchers.IO) {
        decoders.withPermit {
            val limit = if (expectedVideo) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
            require(file.isFile && file.length() in 1..limit) { "产出文件为空或超过大小限制" }
            val header = file.inputStream().use { input ->
                val buffer = ByteArray(MediaFileSignature.MAX_HEADER_BYTES)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                buffer.copyOf(size)
            }
            val signature = MediaFileSignature.detect(header)
            val mime = signature.mime ?: error("接口没有返回可识别的媒体文件")
            val extension = signature.extension ?: error("无法识别产出格式")
            require(expectedVideo == mime.startsWith("video/")) { "接口返回的媒体类型与所选任务不符" }
            if (expectedVideo) validateVideo(file, mime, extension)
            else validateImage(file, mime, extension, requireAnimatedGif)
        }
    }

    private fun validateImage(file: File, mime: String, extension: String, requireAnimatedGif: Boolean): Verified {
        require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            "暂不能验证这个图像格式，请保留原文件后用支持的查看器打开"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "图片尺寸无效或过大" }
        // Decode a bounded bitmap, not only the header. API28+ rejects incomplete images explicitly.
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                if (longest > 1024) decoder.setTargetSize(
                    maxOf(1, info.size.width * 1024 / longest), maxOf(1, info.size.height * 1024 / longest),
                )
            }
            bitmap.recycle()
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("图片文件无法解码")
            bitmap.recycle()
        }
        val gif = if (mime == "image/gif") GifStructure.read(file) else null
        if (requireAnimatedGif) require(gif != null && gif.frames > 1 && gif.durationMs > 0) { "接口未返回有效的多帧 GIF" }
        return Verified(mime, extension, bounds.outWidth, bounds.outHeight, gif?.durationMs?.div(1000.0))
    }

    private fun validateVideo(file: File, mime: String, extension: String): Verified {
        val info = MediaCreationFiles.videoInfo(file) ?: error("视频文件无法读取")
        require(info.width > 0 && info.height > 0 && (info.durationSeconds ?: 0.0) > 0.0) { "视频无有效画面或时长" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: error("视频没有可解码画面")
            frame.recycle()
        } finally { retriever.release() }
        return Verified(mime, extension, info.width, info.height, info.durationSeconds)
    }
}
