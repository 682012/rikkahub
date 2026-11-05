package me.rerere.rikkahub.data.media.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.GifStructure
import me.rerere.rikkahub.data.files.MediaOutputVerifier
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Local rendering only: no provider request, no claimed new AI scene content. */
data class ImageExportTarget(val format: ExportImageFormat, val size: ExportSize, val fit: ExportFit = ExportFit.FIT, val quality: Int = 100)
data class MediaExportResult(val file: File, val mimeType: String, val width: Int, val height: Int, val frameCount: Int? = null, val durationMillis: Long? = null)

object MediaExportEngine {
    private val workers = Semaphore(1)
    private val paint get() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    suspend fun exportImage(context: Context, source: File, target: ImageExportTarget, onProgress: ExportProgressListener? = null): MediaExportResult =
        withContext(Dispatchers.IO) { workers.withPermit {
            require(target.quality in 1..100)
            onProgress?.onProgress(0f, ExportStage.PREPARING)
            val bitmap = decode(source, target.size)
            val frame = Bitmap.createBitmap(target.size.width, target.size.height, Bitmap.Config.ARGB_8888)
            val output = destination(context, target.format.extension)
            val partial = File(output.path + ".part")
            try {
                currentCoroutineContext().ensureActive()
                draw(bitmap, frame, target.fit, null, target.format != ExportImageFormat.JPEG)
                onProgress?.onProgress(0.4f, ExportStage.ENCODING)
                val format = when (target.format) {
                    ExportImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    ExportImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ExportImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                }
                partial.outputStream().use { require(frame.compress(format, target.quality, it)) { "图片编码失败" } }
                currentCoroutineContext().ensureActive()
                val actual = MediaOutputVerifier.validateLocal(partial, false)
                check(partial.renameTo(output)) { "导出文件保存失败" }
                onProgress?.onProgress(1f, ExportStage.FINISHED)
                MediaExportResult(output, actual.mimeType, actual.width, actual.height)
            } finally { bitmap.recycle(); frame.recycle(); partial.delete() }
        } }

    suspend fun exportMotion(context: Context, source: File, spec: MotionSpec, onProgress: ExportProgressListener? = null): MediaExportResult =
        withContext(Dispatchers.IO) { workers.withPermit {
            require(spec.size.pixels <= 1920L * 1080) { "动效导出最多支持 1920×1080 像素，请调小尺寸" }
            require(spec.output != MotionOutput.MP4 || (spec.size.width % 2 == 0 && spec.size.height % 2 == 0)) { "MP4 的宽高需要是偶数" }
            onProgress?.onProgress(0f, ExportStage.PREPARING)
            val bitmap = decode(source, spec.size)
            val frame = Bitmap.createBitmap(spec.size.width, spec.size.height, Bitmap.Config.ARGB_8888)
            val output = destination(context, spec.output.extension)
            val partial = File(output.path + ".part")
            try {
                if (spec.output == MotionOutput.GIF) encodeGif(bitmap, frame, partial, spec, onProgress)
                else encodeMp4(bitmap, frame, partial, spec, onProgress)
                currentCoroutineContext().ensureActive()
                val actual = MediaOutputVerifier.validateLocal(partial, spec.output == MotionOutput.MP4, spec.output == MotionOutput.GIF)
                check(partial.renameTo(output)) { "动效文件保存失败" }
                val gif = if (spec.output == MotionOutput.GIF) GifStructure.read(output) else null
                onProgress?.onProgress(1f, ExportStage.FINISHED)
                MediaExportResult(output, actual.mimeType, actual.width, actual.height, gif?.frames ?: spec.frameCount,
                    gif?.durationMs ?: ((actual.durationSeconds ?: 0.0) * 1000).toLong())
            } finally { bitmap.recycle(); frame.recycle(); partial.delete() }
        } }

    private fun destination(context: Context, extension: String): File =
        File(context.cacheDir, "export").apply { mkdirs() }.resolve("creation-${UUID.randomUUID()}.$extension")

    private fun decode(source: File, size: ExportSize): Bitmap {
        require(source.isFile && source.length() in 1..MediaOutputVerifier.MAX_IMAGE_BYTES) { "源图片不存在、为空或过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64L * 1024 * 1024) { "源图片尺寸无效或过大" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size.width && bounds.outHeight / (sample * 2) >= size.height) sample *= 2
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 20L * 1024 * 1024) sample *= 2
        return BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("源图片无法解码")
    }

    private fun draw(source: Bitmap, target: Bitmap, fit: ExportFit, motion: MotionFrame?, transparent: Boolean = false) {
        val canvas = Canvas(target)
        canvas.drawColor(if (transparent) Color.TRANSPARENT else Color.WHITE, android.graphics.PorterDuff.Mode.SRC)
        val rect = ExportGeometry.compute(source.width, source.height, target.width, target.height, fit)
        val transform = android.graphics.Matrix().apply {
            setRectToRect(RectF(rect.srcLeft, rect.srcTop, rect.srcLeft + rect.srcWidth, rect.srcTop + rect.srcHeight),
                RectF(rect.dstLeft, rect.dstTop, rect.dstLeft + rect.dstWidth, rect.dstTop + rect.dstHeight), android.graphics.Matrix.ScaleToFit.FILL)
        }
        canvas.save()
        if (motion != null) {
            canvas.translate(motion.offsetX * target.width, motion.offsetY * target.height)
            canvas.scale(motion.scale, motion.scale, target.width / 2f, target.height / 2f)
        }
        canvas.drawBitmap(source, transform, paint.apply { alpha = ((motion?.alpha ?: 1f) * 255).roundToInt() })
        canvas.restore()
    }

    private suspend fun encodeGif(source: Bitmap, frame: Bitmap, file: File, spec: MotionSpec, progress: ExportProgressListener?) {
        draw(source, frame, ExportFit.CROP, MotionTimeline.frameAt(spec.style, 0f, spec.zoom))
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val palette = GifPaletteQuantizer.learn(frame.width, frame.height, pixels, sampleFactor = 4)
        file.outputStream().buffered().use { stream ->
            val writer = GifWriter(stream, frame.width, frame.height)
            writer.begin(palette)
            repeat(spec.frameCount) { index ->
                currentCoroutineContext().ensureActive()
                val state = currentCoroutineContext()
                draw(source, frame, ExportFit.CROP, MotionTimeline.frameAt(spec.style, index.toFloat() / (spec.frameCount - 1), spec.zoom))
                // Centisecond rounding is distributed so the total duration matches the chosen seconds.
                val delay = (((index + 1) * 100.0 / spec.fps).roundToInt() - (index * 100.0 / spec.fps).roundToInt()) * 10
                writer.writeFrame(null, delay) { row, out ->
                    state.ensureActive()
                    frame.getPixels(out, 0, frame.width, 0, row, frame.width, 1)
                }
                progress?.onProgress((index + 1f) / spec.frameCount * .95f, ExportStage.ENCODING)
            }
            writer.finish()
        }
    }

    private suspend fun encodeMp4(source: Bitmap, frame: Bitmap, file: File, spec: MotionSpec, progress: ExportProgressListener?) {
        val format = MediaFormat.createVideoFormat("video/avc", frame.width, frame.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (frame.width * frame.height * 4).coerceIn(1_000_000, 16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("设备没有支持此尺寸的 H.264 编码器，请调小尺寸或导出 GIF")
        val codec = MediaCodec.createByCodecName(name)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var codecStarted = false
        var track = -1
        var eos = false
        var samples = 0
        val info = MediaCodec.BufferInfo()
        val pixels = IntArray(frame.width * frame.height)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val writer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            fun drain(waitUs: Long) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, waitUs)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "视频格式重复变化" }
                        track = writer.addTrack(codec.outputFormat)
                        writer.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index) ?: error("视频编码输出为空")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted) { "视频封装尚未开始" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writer.writeSampleData(track, buffer, info)
                            samples++
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
            for (index in 0..spec.frameCount) {
                val deadline = System.nanoTime() + 10_000_000_000L
                var input: Int
                while (true) {
                    currentCoroutineContext().ensureActive()
                    drain(0)
                    input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) break
                    check(System.nanoTime() < deadline) { "视频编码器等待超时" }
                }
                val timestamp = index * 1_000_000L / spec.fps
                if (index == spec.frameCount) {
                    codec.queueInputBuffer(input, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    draw(source, frame, ExportFit.CROP, MotionTimeline.frameAt(spec.style, index.toFloat() / (spec.frameCount - 1), spec.zoom))
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    val image = codec.getInputImage(input) ?: error("设备编码器不支持可写 YUV 帧，请改用 GIF")
                    val state = currentCoroutineContext()
                    for (planeIndex in 0..2) {
                        val plane = image.planes[planeIndex]
                        val buffer = plane.buffer
                        val base = buffer.position()
                        val step = if (planeIndex == 0) 1 else 2
                        for (y in 0 until frame.height step step) {
                            state.ensureActive()
                            for (x in 0 until frame.width step step) {
                                var r = 0; var g = 0; var b = 0
                                for (dy in 0 until step) for (dx in 0 until step) {
                                    val color = pixels[(y + dy) * frame.width + x + dx]
                                    r += color shr 16 and 255; g += color shr 8 and 255; b += color and 255
                                }
                                val n = step * step; r /= n; g /= n; b /= n
                                val value = when (planeIndex) {
                                    0 -> ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                                    1 -> ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                    else -> ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                }
                                buffer.put(base + y / step * plane.rowStride + x / step * plane.pixelStride, value.coerceIn(0, 255).toByte())
                            }
                        }
                    }
                    codec.queueInputBuffer(input, 0, frame.width * frame.height * 3 / 2, timestamp, 0)
                    progress?.onProgress((index + 1f) / spec.frameCount * .95f, ExportStage.ENCODING)
                }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!eos) {
                currentCoroutineContext().ensureActive()
                drain(10_000)
                check(System.nanoTime() < deadline) { "视频编码结束超时" }
            }
            check(samples > 0 && muxerStarted) { "视频没有有效帧" }
            writer.stop()
            muxerStarted = false
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
