package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.pages.extensions.workspace.ArtifactDocumentPreview
import me.rerere.rikkahub.ui.pages.extensions.workspace.HTML_PREVIEW_MAX_RESOURCE_BYTES
import me.rerere.rikkahub.ui.pages.extensions.workspace.TextArtifactPreview
import me.rerere.rikkahub.ui.pages.extensions.workspace.isPreviewAssetPath
import java.io.File
import java.util.Locale

/**
 * 聊天里 `UIMessagePart.Document` 附件的应用内预览入口。
 *
 * 以前所有文档附件都只走 `openLocalFile`（外部应用），聊天里发的 html/pdf 反而看不到内容。
 * 这里给出一个不引入导航改动的最小入口：同一个 Dialog 里
 * - html/svg → InteractiveHtmlPreview（随机 origin、JS 开、本地文件访问关、外网默认关，
 *   同目录资源用 [readSiblingWebAsset] 读取）；
 * - pdf → 现成的 [ArtifactDocumentPreview]（`PdfRenderer`，不换外部应用）；
 * - md/csv/json/txt → Compose 原生渲染，不碰 WebView。
 * 其它格式（docx/xlsx/zip/…）仍然让用户点“打开”交给外部应用。
 */
internal enum class LocalDocumentPreviewKind { HTML, PDF, TEXT }

/** 单文件内联阅读上限：和 workspace 产物预览同一量级，避免把上百 MB 文本读进内存 */
private const val MAX_INLINE_DOCUMENT_BYTES = 4L * 1024 * 1024

/** `content://` 转存到 cache 的上限（PDF 可能比较大） */
private const val MAX_COPIED_DOCUMENT_BYTES = 64L * 1024 * 1024

private val HTML_DOCUMENT_EXTENSIONS = setOf("html", "htm", "xhtml", "svg")
private val HTML_DOCUMENT_MIME = setOf("text/html", "application/xhtml+xml", "image/svg+xml")
private val TEXT_DOCUMENT_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "csv", "tsv", "json", "txt")

/**
 * 什么格式值得在应用内打开；纯函数，让单测能直接断言（不碰 Android API）。
 */
internal fun localDocumentPreviewKind(fileName: String, mime: String): LocalDocumentPreviewKind? {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val normalizedMime = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        extension in HTML_DOCUMENT_EXTENSIONS || normalizedMime in HTML_DOCUMENT_MIME -> LocalDocumentPreviewKind.HTML
        extension == "pdf" || normalizedMime == "application/pdf" -> LocalDocumentPreviewKind.PDF
        extension in TEXT_DOCUMENT_EXTENSIONS || normalizedMime.startsWith("text/") -> LocalDocumentPreviewKind.TEXT
        else -> null
    }
}

/**
 * 读取 HTML 文档旁边的网页资源：只允许 [directory] 目录内的、白名单扩展名的普通文件，
 * 用 canonical 路径挡住 `../` 和符号链接逃逸；单文件 8MB 上限（与 workspace reader 一致）。
 */
internal fun readSiblingWebAsset(directory: File, relative: String): ByteArray {
    require(isPreviewAssetPath(relative)) { "不是可预览的网页资源：$relative" }
    val root = directory.toPath().toRealPath().toFile()
    val file = root.toPath().resolve(relative).toRealPath().toFile()
    require(file.toPath().startsWith(root.toPath()) && file != root) { "资源逃出了附件所在目录" }
    require(file.isFile) { "资源不存在" }
    require(file.length() <= HTML_PREVIEW_MAX_RESOURCE_BYTES) { "单个网页资源超过 8MB" }
    return file.readBytes()
}

@Composable
internal fun LocalDocumentPreviewDialog(
    url: String,
    fileName: String,
    mime: String,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    val context = LocalContext.current
    val kind = remember(fileName, mime) { localDocumentPreviewKind(fileName, mime) }
    var loading by remember(url) { mutableStateOf(true) }
    var failure by remember(url) { mutableStateOf<String?>(null) }
    var htmlText by remember(url) { mutableStateOf<String?>(null) }
    var htmlParent by remember(url) { mutableStateOf<File?>(null) }
    var documentFile by remember(url) { mutableStateOf<File?>(null) }

    LaunchedEffect(url, kind) {
        loading = true
        failure = null
        runCatching {
            val file = withContext(Dispatchers.IO) { resolveDocumentFile(context, url, fileName) }
            if (kind == LocalDocumentPreviewKind.PDF) {
                documentFile = file
            } else {
                require(file.length() <= MAX_INLINE_DOCUMENT_BYTES) { "附件超过 4MB，请用其他应用打开" }
                htmlText = withContext(Dispatchers.IO) { file.readText() }
                htmlParent = file.parentFile
            }
        }.onFailure { failure = it.message ?: "无法读取附件" }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = fileName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onOpenExternally) { Text("打开") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    val text = htmlText
                    val file = documentFile
                    val error = failure
                    when {
                        kind == null -> PreviewMessage("这个格式还不能在应用内预览")

                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        error != null -> Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("预览失败：$error", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onOpenExternally) { Text("用其他应用打开") }
                        }

                        kind == LocalDocumentPreviewKind.PDF && file != null ->
                            ArtifactDocumentPreview(file = file, mimeType = "application/pdf")

                        text != null && kind == LocalDocumentPreviewKind.HTML -> TextArtifactPreview(
                            content = text,
                            fileName = fileName,
                            modifier = Modifier.fillMaxSize(),
                            resourceReader = htmlParent?.let { parent ->
                                { relative -> withContext(Dispatchers.IO) { readSiblingWebAsset(parent, relative) } }
                            },
                        )

                        text != null -> TextArtifactPreview(
                            content = text,
                            fileName = fileName,
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> PreviewMessage("这个格式还不能在应用内预览")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 把附件 URL 落到本地文件：`file://`/裸路径直接用，`content://` 转存到 cache。
 * 只读附件自己，不去碰工作区或别的目录。
 */
private fun resolveDocumentFile(context: Context, url: String, fileName: String): File {
    val uri = url.toUri()
    val file = when (uri.scheme) {
        "content" -> copyToCache(context, uri, fileName)
        "file" -> uri.toFile()
        null -> File(url)
        else -> error("这个附件不在本地，请先下载后预览")
    }
    require(file.isFile) { "附件已不存在" }
    return file
}

private fun copyToCache(context: Context, uri: Uri, fileName: String): File {
    val dir = File(context.cacheDir, "document_preview").apply { mkdirs() }
    val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "document" }
    val target = File(dir, safeName)
    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取这个附件")
    input.use { source ->
        target.outputStream().use { sink ->
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                written += read
                require(written <= MAX_COPIED_DOCUMENT_BYTES) { "附件超过 64MB，请用其他应用打开" }
                sink.write(buffer, 0, read)
            }
        }
    }
    return target
}
