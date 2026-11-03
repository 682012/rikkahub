package me.rerere.rikkahub.utils

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 工作区产物的对外动作：用其他应用打开、分享。
 *
 * 约定：
 * - 只用 FileProvider 的 `content://` + `FLAG_GRANT_READ_URI_PERMISSION` 做**短时**读授权，
 *   不用 `file://`（7.0+ 会 FileUriExposedException，还会把整个目录暴露给接收方）。
 * - 不 `addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION)`：授权随这次调用结束。
 * - 打开/分享都是用户点击触发的动作，代码里不自动外发、不自动安装。
 * - 没有 handler 时返回 failed Result，调用方给明确提示，不要静默失败。
 */
fun mimeTypeOf(path: String): String {
    val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

/** 用其他应用打开缓存里的工作区文件副本 */
fun Context.openWorkspaceArtifactExternally(file: File, mimeType: String = mimeTypeOf(file.name)): Result<Unit> =
    launchExternally(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileProviderUri(file), mimeType)
        // 同时塞进 ClipData：部分 ROM 只从 ClipData 里读授权
        clipData = ClipData.newRawUri(file.name, fileProviderUri(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })

/** 分享缓存里的工作区文件副本；用 chooser 让用户自己选目标应用 */
fun Context.shareWorkspaceArtifact(file: File, mimeType: String = mimeTypeOf(file.name)): Result<Unit> {
    val uri = fileProviderUri(file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(send, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return launchExternally(chooser)
}

/** 自定义 scheme：ACTION_VIEW，没有 handler 时给明确错误 */
fun Context.openExternalScheme(url: String): Result<Unit> =
    launchExternally(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })

private fun Context.fileProviderUri(file: File) =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

private fun Context.launchExternally(intent: Intent): Result<Unit> = try {
    startActivity(intent)
    Result.success(Unit)
} catch (_: ActivityNotFoundException) {
    Result.failure(IllegalStateException("没有可以处理这个内容的 App"))
} catch (error: Exception) {
    Result.failure(error)
}
