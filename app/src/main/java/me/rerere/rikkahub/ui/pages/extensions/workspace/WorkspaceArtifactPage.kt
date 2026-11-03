package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.webkit.WebSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkahub.utils.WorkspaceArtifactKind
import me.rerere.rikkahub.utils.WorkspaceArtifactLink
import me.rerere.rikkahub.utils.mimeTypeOf
import me.rerere.rikkahub.utils.openWorkspaceArtifactExternally
import me.rerere.rikkahub.utils.shareWorkspaceArtifact
import me.rerere.ui.toast.ToastType
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState
import org.koin.compose.koinInject
import java.io.File

/** 应用内预览的文本/图片大小上限，和 WorkspaceRepository 的预览限制保持同一量级 */
private const val MAX_ARTIFACT_PREVIEW_BYTES = 2L * 1024 * 1024
private const val MAX_ARTIFACT_EXPORT_BYTES = 256L * 1024 * 1024

/**
 * 打开 Markdown 里指向当前对话工作区文件（`sandbox:/workspace/...`）的链接。
 *
 * 只读预览：文本只读显示、图片可缩放、HTML 走受限 WebView（禁脚本/禁本地与网络访问），
 * PDF 和其它二进制不在应用内渲染，交给用户在页面上主动点“用其他应用打开”。
 *
 * 所有文件读写都走 [WorkspaceRepository] 已有的本地/SSH/DSH 接口
 * （absoluteFileSize / resolveAbsoluteFile / exportAbsoluteFile），
 * 因此远程工作区也是导出到 cacheDir 再预览，不会去碰宿主机任意路径。
 */
@Composable
fun WorkspaceArtifactPage(
    id: String,
    path: String,
    fragment: String? = null,
    area: String? = null,
) {
    val repository = koinInject<WorkspaceRepository>()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val kind = remember(path) { WorkspaceArtifactLink.kindOf(path) }

    var loading by remember(id, path) { mutableStateOf(true) }
    var errorMessage by remember(id, path) { mutableStateOf<String?>(null) }
    var text by remember(id, path) { mutableStateOf<String?>(null) }
    var artifactFile by remember(id, path) { mutableStateOf<File?>(null) }
    var sizeBytes by remember(id, path) { mutableStateOf(0L) }

    LaunchedEffect(id, path) {
        loading = true
        errorMessage = null
        text = null
        artifactFile = null
        runCatching {
            withContext(Dispatchers.IO) {
                // 不存在 / 不是文件时由工作区 API 抛错，这里统一翻成明确提示，绝不回退到浏览器
                val layout = repository.layout(id) ?: error("工作区不存在")
                val actualPath = if (area == null) WorkspaceArtifactLink.scaleToWorkspace(path, layout.workspaceDir)
                    else me.rerere.workspace.WorkspaceStorageArea.valueOf(area).let { storage -> layout.absolutePath(storage, path) }
                requireNotNull(actualPath) { "路径无效" }
                val size = if(area == null) repository.artifactFileSize(id, actualPath) else repository.absoluteFileSize(id, actualPath)
                require(size <= MAX_ARTIFACT_EXPORT_BYTES) { "文件超过 256MB，请在工作区文件页导出" }
                val file = exportArtifactToCache(repository, id, actualPath, context.cacheDir, artifactLink = area == null)
                val previewText = if ((kind == WorkspaceArtifactKind.TEXT || kind == WorkspaceArtifactKind.HTML) && size <= MAX_ARTIFACT_PREVIEW_BYTES)
                    file.readText() else null
                Triple(size, file, previewText)
            }
        }.onSuccess { (size, file, previewText) ->
            sizeBytes = size
            artifactFile = file
            text = previewText
            loading = false
        }.onFailure { error ->
            errorMessage = describeArtifactError(error, path)
            loading = false
        }
    }

    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = artifactFile
        if (uri != null && file != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入所选位置")
            } }.onFailure { toaster.show(it.message ?: "导出失败", type = ToastType.Error) }
        }
    }

    fun openExternally() {
        val file = artifactFile ?: return
        context.openWorkspaceArtifactExternally(file, mimeTypeOf(fileName))
            .onFailure { toaster.show(it.message ?: "没有可以打开这个文件的 App", type = ToastType.Error) }
    }

    fun share() {
        val file = artifactFile ?: return
        context.shareWorkspaceArtifact(file, mimeTypeOf(fileName))
            .onFailure { toaster.show(it.message ?: "分享失败", type = ToastType.Error) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (!loading && errorMessage == null) {
                        TextButton(onClick = { openExternally() }) { Text("打开") }
                        TextButton(onClick = { exporter.launch(fileName) }) { Text("导出") }
                        TextButton(onClick = { share() }) { Text("分享") }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.pageContainerColor,
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            loading -> Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            errorMessage != null -> Column(
                modifier = contentModifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "链接指向当前对话绑定的工作区文件: $path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            kind == WorkspaceArtifactKind.IMAGE -> ZoomableAsyncImage(
                model = artifactFile?.absolutePath,
                contentDescription = fileName,
                modifier = contentModifier,
            )

            kind == WorkspaceArtifactKind.TEXT && text != null -> SelectionContainer {
                Text(
                    text = text ?: "",
                    modifier = contentModifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            kind == WorkspaceArtifactKind.HTML && text != null -> HtmlArtifactPreview(
                html = text ?: "",
                modifier = contentModifier,
            )

            else -> artifactFile?.let { file ->
                ArtifactDocumentPreview(file=file,mimeType=mimeTypeOf(fileName),modifier=contentModifier)
            }
        }
    }
}

/**
 * 工作区文件内容的受限预览。
 *
 * 安全约束（对应“危险 HTML 不许访问私有文件或 JS 接口、不自动跑脚本”）：
 * - `javaScriptEnabled = false`：不执行脚本，也不注册任何 addJavascriptInterface 通道；
 * - `allowFileAccess / allowContentAccess = false`：页面拿不到 file:// 和 content://；
 * - `blockNetworkLoads = true`：页面里的外链资源不会自动请求；
 * - `baseUrl` 用不可解析的假域名，相对链接不会落回本地目录。
 *
 * 锚点（`#foo`）不在这里自动滚动：禁用脚本后无法安全触发定位，片段只作为链接信息保留。
 */
@Composable
private fun HtmlArtifactPreview(html: String, modifier: Modifier = Modifier) {
    val state = rememberWebViewState(
        data = html,
        baseUrl = "https://workspace-artifact.invalid/",
        mimeType = "text/html",
        settings = { applyWorkspaceArtifactPreviewSettings() },
    )
    state.javaScriptEnabled = false
    WebView(state = state, modifier = modifier)
}

/**
 * 预览 HTML 产物时收紧的 WebSettings。
 *
 * 单独拆出来是为了能在 instrumented 测试里对真实 WebSettings 断言，
 * 避免以后有人为了“让预览好看点”顺手把脚本或本地文件访问打开。
 */
fun WebSettings.applyWorkspaceArtifactPreviewSettings() {
    javaScriptEnabled = false
    allowFileAccess = false
    allowContentAccess = false
    blockNetworkLoads = true
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    setSupportMultipleWindows(false)
    builtInZoomControls = true
    displayZoomControls = false
    useWideViewPort = true
    loadWithOverviewMode = true
}

/** 导出到 cacheDir：FileProvider 只暴露 cache/files 目录，远程工作区也只有这一步落地 */
private suspend fun exportArtifactToCache(
    repository: WorkspaceRepository,
    id: String,
    path: String,
    cacheDir: File,
    artifactLink: Boolean = true,
): File = withContext(Dispatchers.IO) {
    val dir = File(cacheDir, "workspace_artifact").apply { mkdirs() }
    val safeName = path.substringAfterLast('/').ifBlank { "artifact" }
        .replace('/', '_').replace('\\', '_').replace('\u0000', '_')
    val file = File(dir, "${id.take(8)}-${path.hashCode().toUInt().toString(16)}-$safeName")
    try {
        file.outputStream().use { destination ->
            val bounded = object : java.io.OutputStream() {
                var written = 0L
                override fun write(value: Int) {
                    check(++written <= MAX_ARTIFACT_EXPORT_BYTES) { "文件超过 256MB" }
                    destination.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    written += count
                    check(written <= MAX_ARTIFACT_EXPORT_BYTES) { "文件超过 256MB" }
                    destination.write(bytes, offset, count)
                }
            }
            if(artifactLink) repository.exportArtifactFile(id, path, bounded)
            else repository.exportAbsoluteFile(id, path, bounded)
        }
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    file
}

private fun describeArtifactError(error: Throwable, path: String): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("does not exist", ignoreCase = true) -> WorkspaceArtifactLink.Reason.NOT_FOUND.message
        message.contains("not a file", ignoreCase = true) -> WorkspaceArtifactLink.Reason.NOT_A_FILE.message
        message.isBlank() -> "无法打开工作区文件: $path"
        else -> message
    }
}
