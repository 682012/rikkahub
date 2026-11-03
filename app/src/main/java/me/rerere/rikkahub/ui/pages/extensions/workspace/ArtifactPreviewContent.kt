package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 工作区产物的应用内预览：PDF / Office 文本 / 压缩包清单 / 音视频 / 未知二进制。
 *
 * 调用方（`WorkspaceArtifactPage` 等）只需要把**已经落到本地 cache 的文件**和 MIME 交进来：
 * 本文件不做网络、不做下载、不解压到工作区、不调外部 App、不新增运行时。
 * 本地/远程工作区文件都由调用方先导出成 cache 副本（远程走 SSH/DSH 导出），这里只读这个副本。
 *
 * 预览失败只在预览区域显示原因，不弹窗、不阻塞页面顶部的「打开 / 分享」。
 *
 * 分工：
 * - 纯逻辑（格式判定、ZIP/TAR 清单、Office 文本、hex）在 `ArtifactPreviewContentFormats.kt`，JVM 单测覆盖；
 * - 图片用已有的 `ZoomableAsyncImage`、文本与受限 HTML 用现有 WebView，都不在这里重复实现。
 */
@Composable
fun ArtifactDocumentPreview(
    file: File,
    mimeType: String,
    modifier: Modifier = Modifier,
) {
    when (artifactDocumentKindOf(file.name, mimeType)) {
        ArtifactDocumentKind.PDF -> PdfArtifactPreview(file = file, modifier = modifier)
        ArtifactDocumentKind.OFFICE_TEXT -> OfficeTextArtifactPreview(file = file, modifier = modifier)
        ArtifactDocumentKind.ARCHIVE -> ArchiveArtifactPreview(file = file, modifier = modifier)
        ArtifactDocumentKind.AUDIO -> AudioArtifactPreview(file = file, modifier = modifier)
        ArtifactDocumentKind.VIDEO -> VideoArtifactPreview(file = file, modifier = modifier)
        ArtifactDocumentKind.BINARY -> BinaryArtifactPreview(file = file, mimeType = mimeType, modifier = modifier)
    }
}

// ---------------------------------------------------------------------------------------------
// PDF
// ---------------------------------------------------------------------------------------------

/**
 * 用系统自带的 [PdfRenderer] 分页渲染 PDF。
 *
 * - 一次只持有一页的渲染结果，位图分辨率按可见区域收窄（[ArtifactPreviewLimits.MAX_RENDER_SIDE] /
 *   [ArtifactPreviewLimits.MAX_RENDER_PIXELS]），不会把整本 PDF 解码进内存；
 * - 渲染在 IO 线程，位图离开组合时 recycle；
 * - 加密 PDF（PdfRenderer 抛 password 相关 IOException）、损坏文件都翻成明确提示，不崩、不回退浏览器；
 * - [PdfArtifactSession] 持有 `ParcelFileDescriptor` + `PdfRenderer`，离开页面 close。
 */
@Composable
private fun PdfArtifactPreview(file: File, modifier: Modifier = Modifier) {
    var session by remember(file) { mutableStateOf<PdfArtifactSession?>(null) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    var failure by remember(file) { mutableStateOf<PdfPreviewFailure?>(null) }
    var failureDetail by remember(file) { mutableStateOf<String?>(null) }

    DisposableEffect(file) {
        onDispose { session?.close() }
    }

    // 位图离开组合（换页 / 退出页面）时回收；recycle 放在 onDispose 里，避免回收掉还在绘制的那一张
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.recycle() }
    }

    LaunchedEffect(file) {
        loading = true
        val opened = withContext(Dispatchers.IO) { runCatching { PdfArtifactSession(file) } }
        opened.onSuccess { openedSession ->
            session = openedSession
            pageCount = openedSession.pageCount
            if (openedSession.pageCount <= 0) {
                failure = PdfPreviewFailure.CORRUPT
                failureDetail = "PDF 里没有可显示的页面"
                loading = false
            }
        }.onFailure { error ->
            failure = pdfPreviewFailureOf(error)
            failureDetail = error.message
            loading = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

        LaunchedEffect(session, pageIndex, widthPx, heightPx) {
            val opened = session ?: return@LaunchedEffect
            if (opened.pageCount <= 0) return@LaunchedEffect
            loading = true
            val rendered = withContext(Dispatchers.IO) {
                runCatching { opened.render(pageIndex, widthPx, heightPx) }
            }
            rendered.onSuccess { next ->
                bitmap = next
                failure = null
                failureDetail = null
            }.onFailure { error ->
                failure = pdfPreviewFailureOf(error)
                failureDetail = error.message
            }
            loading = false
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val currentBitmap = bitmap
                when {
                    failure != null -> PdfFailureMessage(failure = failure, detail = failureDetail)
                    currentBitmap != null -> Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = file.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (loading && currentBitmap == null && failure == null) {
                    CircularProgressIndicator()
                }
            }
            if (failure == null && pageCount > 0) {
                PdfPageBar(
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    onPrevious = { if (pageIndex > 0) pageIndex-- },
                    onNext = { if (pageIndex < pageCount - 1) pageIndex++ },
                )
            }
        }
    }
}

@Composable
private fun PdfPageBar(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = pageIndex > 0) {
            Text(text = stringResource(R.string.artifact_preview_previous_page))
        }
        Text(
            text = stringResource(R.string.artifact_preview_page_indicator, pageIndex + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Text(text = stringResource(R.string.artifact_preview_next_page))
        }
    }
}

@Composable
private fun PdfFailureMessage(failure: PdfPreviewFailure?, detail: String?) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (failure) {
                PdfPreviewFailure.PASSWORD -> stringResource(R.string.artifact_preview_pdf_password)
                PdfPreviewFailure.CORRUPT -> stringResource(R.string.artifact_preview_pdf_corrupt)
                else -> stringResource(R.string.artifact_preview_pdf_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.artifact_preview_fallback_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一份已打开的 PDF：持有 fd + renderer，并按需渲染单页位图。
 *
 * 单独成类是为了能在 instrumented 测试里直接对真实 PDF 断言页数和渲染尺寸
 * （Compose 部分不适合做像素断言）。
 */
class PdfArtifactSession(file: File) : Closeable {
    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer
    private var closed = false

    init {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    val pageCount: Int
        get() = if (closed) 0 else renderer.pageCount

    /**
     * 渲染第 [pageIndex] 页，输出位图不超过 [maxWidthPx] × [maxHeightPx]，且总像素受
     * [ArtifactPreviewLimits.MAX_RENDER_PIXELS] 约束。调用方负责 recycle 返回的位图。
     */
    fun render(pageIndex: Int, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        check(!closed) { "PDF 预览已关闭" }
        val count = renderer.pageCount
        if (pageIndex !in 0 until count) throw IOException("页码超出范围：${pageIndex + 1}/$count")

        val page = renderer.openPage(pageIndex)
        try {
            val sourceWidth = page.width.coerceAtLeast(1)
            val sourceHeight = page.height.coerceAtLeast(1)
            val widthLimit = maxWidthPx.coerceAtLeast(1)
            val heightLimit = maxHeightPx.coerceAtLeast(1)

            var scale = minOf(widthLimit.toFloat() / sourceWidth, heightLimit.toFloat() / sourceHeight)
            if (!scale.isFinite() || scale <= 0f) scale = 1f
            scale = scale.coerceAtMost(ArtifactPreviewLimits.MAX_RENDER_SCALE)

            var width = (sourceWidth * scale).roundToInt().coerceIn(1, ArtifactPreviewLimits.MAX_RENDER_SIDE)
            var height = (sourceHeight * scale).roundToInt().coerceIn(1, ArtifactPreviewLimits.MAX_RENDER_SIDE)
            val pixels = width.toLong() * height
            if (pixels > ArtifactPreviewLimits.MAX_RENDER_PIXELS) {
                val factor = sqrt(ArtifactPreviewLimits.MAX_RENDER_PIXELS.toDouble() / pixels.toDouble())
                width = (width * factor).roundToInt().coerceAtLeast(1)
                height = (height * factor).roundToInt().coerceAtLeast(1)
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PDF 页面背景默认透明，先铺白底，深色主题下也能看清
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

// ---------------------------------------------------------------------------------------------
// Office（docx / xlsx / pptx / odt）：只提取文本
// ---------------------------------------------------------------------------------------------

@Composable
private fun OfficeTextArtifactPreview(file: File, modifier: Modifier = Modifier) {
    var document by remember(file) { mutableStateOf<OfficeTextDocument?>(null) }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        loading = true
        errorMessage = null
        withContext(Dispatchers.IO) { runCatching { readOfficeText(file) } }
            .onSuccess { document = it }
            .onFailure { errorMessage = it.message ?: "无法提取文档文本" }
        loading = false
    }

    PreviewContentFrame(
        loading = loading,
        errorMessage = errorMessage,
        isEmpty = document?.isEmpty == true,
        modifier = modifier,
    ) {
        val loaded = document ?: return@PreviewContentFrame
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.artifact_preview_office_note, loaded.format.displayName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (loaded.truncated) {
                    item {
                        Text(
                            text = stringResource(R.string.artifact_preview_office_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                loaded.sections.forEach { section ->
                    if (section.label.isNotEmpty()) {
                        item {
                            Text(
                                text = section.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(section.lines.size) { index ->
                        Text(
                            text = section.lines[index],
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 压缩包：只列目录
// ---------------------------------------------------------------------------------------------

@Composable
private fun ArchiveArtifactPreview(file: File, modifier: Modifier = Modifier) {
    var listing by remember(file) { mutableStateOf<ArchiveListing?>(null) }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        loading = true
        errorMessage = null
        withContext(Dispatchers.IO) { runCatching { readArchiveListing(file) } }
            .onSuccess { listing = it }
            .onFailure { errorMessage = it.message ?: "无法读取压缩包目录" }
        loading = false
    }

    PreviewContentFrame(
        loading = loading,
        errorMessage = errorMessage,
        isEmpty = listing?.entries?.isEmpty() == true,
        modifier = modifier,
    ) {
        val loaded = listing ?: return@PreviewContentFrame
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(
                        R.string.artifact_preview_archive_summary,
                        loaded.format.displayName,
                        loaded.fileCount,
                        formatByteSize(loaded.totalUncompressedBytes),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.artifact_preview_archive_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loaded.truncated) {
                    Text(
                        text = stringResource(R.string.artifact_preview_archive_truncated, loaded.entries.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(loaded.entries.size) { index ->
                    val entry = loaded.entries[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (entry.isDirectory) "${entry.path}/" else entry.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.isDirectory) {
                            Text(
                                text = formatByteSize(entry.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 未知二进制：元信息 + 前 4KB 十六进制
// ---------------------------------------------------------------------------------------------

@Composable
private fun BinaryArtifactPreview(
    file: File,
    mimeType: String,
    modifier: Modifier = Modifier,
) {
    var preview by remember(file) { mutableStateOf<BinaryPreview?>(null) }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        loading = true
        errorMessage = null
        withContext(Dispatchers.IO) { runCatching { readBinaryPreview(file) } }
            .onSuccess { preview = it }
            .onFailure { errorMessage = it.message ?: "无法读取文件内容" }
        loading = false
    }

    PreviewContentFrame(
        loading = loading,
        errorMessage = errorMessage,
        isEmpty = preview?.shownBytes == 0,
        modifier = modifier,
    ) {
        val loaded = preview ?: return@PreviewContentFrame
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(
                        R.string.artifact_preview_binary_summary,
                        formatByteSize(loaded.sizeBytes),
                        mimeType,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.artifact_preview_binary_note, loaded.shownBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = loaded.lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 音视频：只用系统 MediaPlayer / VideoView，离开页面就停，不常驻
// ---------------------------------------------------------------------------------------------

@Composable
private fun AudioArtifactPreview(file: File, modifier: Modifier = Modifier) {
    val playerHolder = remember(file) { PreviewHolder<MediaPlayer>() }
    val released = remember(file) { AtomicBoolean(false) }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var preparing by remember(file) { mutableStateOf(true) }
    var playing by remember(file) { mutableStateOf(false) }
    var durationMs by remember(file) { mutableIntStateOf(0) }
    var positionMs by remember(file) { mutableIntStateOf(0) }

    DisposableEffect(file) {
        onDispose {
            released.set(true)
            playerHolder.value?.let { player ->
                runCatching { if (player.isPlaying) player.pause() }
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            playerHolder.value = null
        }
    }

    // 退到后台就暂停，不在后台偷偷出声
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val player = playerHolder.value
        if (player != null) {
            runCatching { player.pause() }
            playing = false
        }
    }

    LaunchedEffect(file) {
        preparing = true
        errorMessage = null
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    player.setDataSource(file.absolutePath)
                    player.prepare()
                    player
                } catch (error: Throwable) {
                    // prepare 失败时自己释放，别把半初始化的播放器漏在外面
                    runCatching { player.release() }
                    throw error
                }
            }
        }
        prepared.onSuccess { player ->
            if (released.get()) {
                runCatching { player.release() }
            } else {
                player.setOnErrorListener { _, _, _ ->
                    errorMessage = "音频播放出错"
                    playing = false
                    true
                }
                player.setOnCompletionListener {
                    playing = false
                    positionMs = durationMs
                }
                playerHolder.value = player
                durationMs = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
            }
        }.onFailure {
            errorMessage = it.message ?: "无法播放这个音频"
        }
        preparing = false
    }

    LaunchedEffect(playing) {
        while (playing) {
            val player = playerHolder.value ?: break
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            durationMs = runCatching { player.duration }.getOrDefault(durationMs).coerceAtLeast(0)
            delay(500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = file.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            errorMessage != null -> Text(
                text = errorMessage ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            preparing -> CircularProgressIndicator()

            else -> {
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = { positionMs = it.roundToInt() },
                    onValueChangeFinished = {
                        runCatching { playerHolder.value?.seekTo(positionMs) }
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                )
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = {
                            val player = playerHolder.value ?: return@TextButton
                            if (playing) {
                                runCatching { player.pause() }
                                playing = false
                            } else {
                                runCatching { player.start() }
                                    .onSuccess { playing = true }
                                    .onFailure { errorMessage = "无法播放这个音频" }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (playing) R.string.artifact_preview_media_pause
                                else R.string.artifact_preview_media_play,
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            val player = playerHolder.value ?: return@TextButton
                            runCatching {
                                player.pause()
                                player.seekTo(0)
                            }
                            playing = false
                            positionMs = 0
                        },
                    ) {
                        Text(text = stringResource(R.string.artifact_preview_media_restart))
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoArtifactPreview(file: File, modifier: Modifier = Modifier) {
    val viewHolder = remember(file) { PreviewHolder<VideoView>() }
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var prepared by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        onDispose {
            runCatching { viewHolder.value?.stopPlayback() }
            viewHolder.value = null
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        runCatching { viewHolder.value?.pause() }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { prepared = true }
                    setOnErrorListener { _, what, extra ->
                        errorMessage = "视频播放出错（$what/$extra）"
                        true
                    }
                    viewHolder.value = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            errorMessage != null -> Text(
                text = errorMessage ?: "",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )

            !prepared -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------------------------

/** 保存非 Compose 状态的实例（MediaPlayer / VideoView），避免把播放器塞进 snapshot state */
private class PreviewHolder<T>(var value: T? = null)

@Composable
private fun PreviewContentFrame(
    loading: Boolean,
    errorMessage: String?,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            errorMessage != null -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.artifact_preview_fallback_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            isEmpty -> Text(
                text = stringResource(R.string.artifact_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> content()
        }
    }
}

private fun formatDuration(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
