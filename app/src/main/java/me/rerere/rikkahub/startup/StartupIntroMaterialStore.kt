package me.rerere.rikkahub.startup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导入失败的原因；界面按它给一句能看懂的话，不把异常文本丢给用户。 */
enum class StartupImportFailure {
    UNSUPPORTED_TYPE,
    TOO_LARGE,
    EMPTY_FILE,
    IO_ERROR,
}

sealed class StartupImportResult {
    data class Imported(val custom: StartupCustomIntro) : StartupImportResult()
    data class Rejected(val reason: StartupImportFailure) : StartupImportResult()
}

/**
 * 自定义启动素材的落地：从系统选择器拿到的 URI 复制进应用私有目录。
 *
 * 三条硬约束：
 * 1. 全程在 [Dispatchers.IO]：绝不在主线程读用户选的几个 MB 的文件；
 * 2. 拷贝时就带字节上限（不是复制完再判断），超了立刻中断并删掉半个文件；
 * 3. 只写固定文件名（[StartupIntroRules.fileNameFor]），用户给的名字不进文件系统。
 *
 * 目录里只留当前这一份素材：换素材/清除时旧文件直接删掉，不会越攒越多。
 */
object StartupIntroMaterialStore {
    private const val TAG = "StartupMaterial"

    fun directory(context: Context): File = File(context.filesDir, StartupIntroRules.DIRECTORY)

    /** 只要私有目录里真实存在的文件；不在目录里或不存在都返回 null（失败就回退内建风格）。 */
    fun resolveExisting(context: Context, custom: StartupCustomIntro?): File? {
        val path = custom?.path?.trim().orEmpty()
        if (path.isEmpty()) return null
        val root = directory(context)
        val file = File(path)
        val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!canonicalFile.startsWith(canonicalRoot + File.separator)) return null
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /** 设置页预览要用：文件在不在，异步判断，不阻塞组合。 */
    suspend fun resolveExistingAsync(context: Context, custom: StartupCustomIntro?): File? =
        withContext(Dispatchers.IO) { resolveExisting(context, custom) }

    suspend fun import(
        context: Context,
        uri: Uri,
        durationMs: Int = 0,
        scalePercent: Int = StartupIntroRules.DEFAULT_SCALE,
        fit: StartupFit = StartupFit.DEFAULT,
    ): StartupImportResult = withContext(Dispatchers.IO) {
        val meta = readMetadata(context, uri)
        val kind = StartupIntroRules.kindOf(meta.mimeType, meta.displayName)
            ?: return@withContext StartupImportResult.Rejected(StartupImportFailure.UNSUPPORTED_TYPE)
        // 系统已经报了大小且超限：连打开都省了
        if (StartupIntroRules.declaredSizeTooLarge(kind, meta.sizeBytes)) {
            return@withContext StartupImportResult.Rejected(StartupImportFailure.TOO_LARGE)
        }
        val extension = StartupIntroRules.extensionFor(kind, meta.displayName, meta.mimeType)
        val target = File(directory(context).apply { mkdirs() }, StartupIntroRules.fileNameFor(kind, extension))
        val temp = File(target.parentFile, target.name + ".part")
        val limit = StartupIntroRules.maxBytes(kind)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                StartupIntroRules.copyWithLimit(input, temp, limit)
            } ?: -1L
        }.getOrElse { error ->
            Log.w(TAG, "open intro material failed", error)
            temp.delete()
            return@withContext StartupImportResult.Rejected(StartupImportFailure.IO_ERROR)
        }
        if (copied < 0L) {
            temp.delete()
            return@withContext StartupImportResult.Rejected(StartupImportFailure.TOO_LARGE)
        }
        if (copied == 0L) {
            temp.delete()
            return@withContext StartupImportResult.Rejected(StartupImportFailure.EMPTY_FILE)
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withContext StartupImportResult.Rejected(StartupImportFailure.IO_ERROR)
        }
        // 同一时刻只保留一份素材：换格式（图 ↔ 视频）时把另一种删掉
        directory(context).listFiles()?.forEach { file ->
            if (file.name != target.name && (file.name.startsWith("intro_") || file.name.endsWith(".part"))) {
                runCatching { file.delete() }
            }
        }
        val custom = StartupCustomIntro(
            path = target.absolutePath,
            kind = kind,
            durationMs = StartupIntroRules.clampDuration(durationMs, kind),
            scalePercent = StartupIntroRules.clampScale(scalePercent),
            fit = fit,
        )
        StartupPreferences.writeCustom(context, custom)
        StartupImportResult.Imported(custom)
    }

    /** 清除：删目录里的素材文件并撤掉偏好；失败也把偏好清干净，界面不会再显示"有素材"。 */
    suspend fun clear(context: Context): Unit = withContext(Dispatchers.IO) {
        runCatching { directory(context).listFiles()?.forEach { it.delete() } }
            .onFailure { Log.w(TAG, "delete intro material failed", it) }
        StartupPreferences.clearCustom(context)
    }

    /** 只改时长/缩放/适配，不重新拷文件。 */
    fun updateTuning(context: Context, durationMs: Int, scalePercent: Int, fit: StartupFit) {
        StartupPreferences.updateCustomTuning(context, durationMs, scalePercent, fit)
    }

    private data class UriMetadata(
        val displayName: String?,
        val mimeType: String?,
        val sizeBytes: Long,
    )

    private fun readMetadata(context: Context, uri: Uri): UriMetadata {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "read intro metadata failed", it) }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return UriMetadata(displayName = name ?: uri.lastPathSegment, mimeType = mime, sizeBytes = size)
    }
}
