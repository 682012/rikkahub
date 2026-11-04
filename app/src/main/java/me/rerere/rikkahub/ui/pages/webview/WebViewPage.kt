package me.rerere.rikkahub.ui.pages.webview

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToasterOrNull
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.openExternalScheme
import me.rerere.rikkahub.utils.openUrl
import me.rerere.ui.toast.ToastType
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebContent
import me.rerere.ui.webview.WebPreviewNavigation
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.WebViewContentCache
import me.rerere.ui.webview.WebViewLoadError
import me.rerere.ui.webview.WebViewState
import me.rerere.ui.webview.rememberWebViewState

/**
 * 应用内网页预览。
 *
 * 两个入口共用这一页：
 * - [url] 非空：模型/对话里点开的 http(s) 链接，直接在当前 WebView 里加载；
 * - 否则：本地生成的 HTML（Markdown 预览、代码块、Mermaid）先落盘成 64 位 id，这里按 id 取回，
 *   用 [WEB_VIEW_BASE_URL] 作为 baseUrl 加载，正文不塞进导航状态。
 *
 * 页面只负责显示进度、地址、失败与重试，以及把非 http(s) 链接交给用户确认后 external 打开；
 * 加载策略、SSL 拒绝、file/content 拦截都在 `me.rerere.ui.webview` 里，不在这一层重复实现。
 */
@Composable
fun WebViewPage(url: String, contentId: String) {
    val context = LocalContext.current

    if (url.isNotBlank()) {
        val state = rememberWebViewState(
            url = url,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        )
        WebPreviewScaffold(
            state = state,
            online = true,
            fallbackTitle = WebPreviewNavigation.displayHost(url),
        )
    } else {
        // 缓存只读一次：contentId 不变时不重复读盘，避免每次重组都做磁盘 IO
        val html = remember(contentId) {
            if (contentId.isBlank()) null else WebViewContentCache.load(context.cacheDir, contentId)
        }

        if (html.isNullOrBlank()) {
            MissingPreviewContent()
        } else {
            val state = rememberWebViewState(
                data = html,
                baseUrl = WEB_VIEW_BASE_URL,
                mimeType = "text/html",
                settings = {
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
            )
            WebPreviewScaffold(
                state = state,
                online = false,
                fallbackTitle = stringResource(R.string.r16_web_title),
            )
        }
    }
}

/** 预览正文的落盘缓存过期/被清理后的兜底页：明确说明原因，不显示空白 WebView */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingPreviewContent() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.r16_web_missing_title)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = HugeIcons.File02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.r16_web_missing_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.r16_web_missing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebPreviewScaffold(
    state: WebViewState,
    online: Boolean,
    fallbackTitle: String?,
) {
    val context = LocalContext.current
    val toaster = LocalToasterOrNull.current

    var showDropdown by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    val notify: (String) -> Unit = remember(context, toaster) {
        { message: String ->
            if (toaster != null) {
                toaster.show(message, type = ToastType.Error)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val externalFailed = stringResource(R.string.r16_web_external_failed)

    // 宿主离开前台时暂停 WebView，别让页面的 JS/媒体在后台继续跑
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        state.onHostPause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.onHostResume()
    }

    BackHandler(state.canGoBack) {
        state.goBack()
    }

    // 只对真正的在线页面提供“在浏览器打开”，本地 HTML 的 baseUrl 是这个虚拟域名，开出去没有意义
    val currentExternalUrl = state.currentUrl
        ?.takeIf { WebPreviewNavigation.isWebUrl(it) && WebPreviewNavigation.displayHost(it) != LOCAL_PREVIEW_HOST }
        ?: state.content.let { content ->
            (content as? WebContent.Url)?.url
                ?.takeIf { WebPreviewNavigation.isWebUrl(it) && WebPreviewNavigation.displayHost(it) != LOCAL_PREVIEW_HOST }
        }

    val title = state.pageTitle?.takeIf { it.isNotBlank() }
        ?: WebPreviewNavigation.displayHost(state.currentUrl ?: "")
        ?: fallbackTitle
        ?: stringResource(R.string.r16_web_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (online) {
                            Text(
                                text = WebPreviewNavigation.displayHost(state.currentUrl ?: "")
                                    ?: stringResource(R.string.r16_web_address_unknown),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (state.isLoading) {
                        IconButton(onClick = { state.stopLoading() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.r16_web_stop))
                        }
                    } else {
                        IconButton(onClick = { state.reload() }) {
                            Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.r16_web_reload))
                        }
                    }

                    IconButton(
                        onClick = { state.goForward() },
                        enabled = state.canGoForward,
                    ) {
                        Icon(HugeIcons.ArrowRight01, contentDescription = stringResource(R.string.r16_web_forward))
                    }

                    IconButton(onClick = { showDropdown = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.r16_web_more))

                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.r16_web_open_browser)) },
                                leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                enabled = currentExternalUrl != null,
                                onClick = {
                                    showDropdown = false
                                    currentExternalUrl?.let(context::openUrl)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.r16_web_console)) },
                                leadingIcon = { Icon(HugeIcons.Bug01, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    showConsoleSheet = true
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (online) {
                WebAddressBar(
                    url = state.currentUrl,
                    isLoading = state.isLoading,
                    progress = state.loadingProgress,
                    hasError = state.loadError != null,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                WebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )

                // 失败时盖住 WebView：默认错误页是一张白板，用户看不出发生了什么，也没法重试
                state.loadError?.let { error ->
                    WebLoadErrorPanel(
                        error = error,
                        onRetry = state::retry,
                        onOpenExternal = currentExternalUrl?.let { target ->
                            { context.openUrl(target) }
                        },
                        onClose = state::clearLoadError,
                    )
                }
            }
        }
    }

    // 页面里点了 mailto:/tel:/intent: 之类的链接：先问一次，用户同意了才交给系统
    state.pendingExternalUrl?.let { target ->
        val decision = WebPreviewNavigation.decide(target)
        AlertDialog(
            onDismissRequest = { state.consumePendingExternalNavigation() },
            title = { Text(stringResource(R.string.r16_web_external_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.r16_web_external_message,
                        (decision as? WebPreviewNavigation.Decision.External)?.scheme ?: "",
                        target.take(MAX_EXTERNAL_URL_PREVIEW),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.consumePendingExternalNavigation()
                        // 二次校验：只放行确认过是“外部协议”的地址，别在这里给 file/content 开新口子
                        if (decision is WebPreviewNavigation.Decision.External) {
                            context.openExternalScheme(target)
                                .onFailure { notify(it.message ?: externalFailed) }
                        } else {
                            notify(externalFailed)
                        }
                    }
                ) {
                    Text(stringResource(R.string.r16_web_external_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.consumePendingExternalNavigation() }) {
                    Text(stringResource(R.string.r16_web_external_cancel))
                }
            },
        )
    }

    if (showConsoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConsoleSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.r16_web_console),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SelectionContainer {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.consoleMessages) { message ->
                            Text(
                                text = "${message.messageLevel().name}: ${message.message()}\n" +
                                    "Source: ${message.sourceId()}:${message.lineNumber()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = when (message.messageLevel().name) {
                                    "ERROR" -> MaterialTheme.colorScheme.error
                                    "WARNING" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (state.consoleMessages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.r16_web_console_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/** 地址条：只显示域名（不带 path/query，避免把 token 显示在屏幕上），加载中显示进度百分比 */
@Composable
private fun WebAddressBar(
    url: String?,
    isLoading: Boolean,
    progress: Float,
    hasError: Boolean,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Link01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = WebPreviewNavigation.displayHost(url ?: "")
                    ?: stringResource(R.string.r16_web_address_unknown),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (isLoading) {
                Text(
                    text = stringResource(
                        R.string.r16_web_progress,
                        (progress.coerceIn(0f, 1f) * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasError) {
                Icon(
                    imageVector = HugeIcons.Alert02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 主文档加载失败的整块提示：说清是证书还是网络，给出重试与“在浏览器打开”两条出路 */
@Composable
private fun WebLoadErrorPanel(
    error: WebViewLoadError,
    onRetry: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = HugeIcons.Alert02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = when {
                    error.rendererGone -> stringResource(R.string.r16_web_error_renderer_title)
                    error.sslError -> stringResource(R.string.r16_web_error_ssl_title)
                    else -> stringResource(R.string.r16_web_error_title)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = when {
                    error.rendererGone -> stringResource(R.string.r16_web_error_renderer_hint)
                    error.sslError -> stringResource(R.string.r16_web_error_ssl_hint)
                    else -> stringResource(R.string.r16_web_error_network_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            error.url?.takeIf { it.isNotBlank() }?.let { failedUrl ->
                Text(
                    text = stringResource(R.string.r16_web_error_url, failedUrl.take(MAX_EXTERNAL_URL_PREVIEW)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            error.description?.takeIf { it.isNotBlank() && !error.sslError }?.let { description ->
                Text(
                    text = stringResource(R.string.r16_web_error_detail, description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(
                modifier = Modifier.padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 渲染进程已崩时重建同一个 WebView 没有意义，只留退出与外部打开
                if (!error.rendererGone) {
                    FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(R.string.r16_web_retry))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenExternal != null) {
                        TextButton(onClick = onOpenExternal) {
                            Text(stringResource(R.string.r16_web_open_browser))
                        }
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.r16_web_close))
                    }
                }
            }
        }
    }
}

/** 本地 HTML 预览用的虚拟域名；它出现在地址栏只说明这是应用内内容，不是真站点 */
private val LOCAL_PREVIEW_HOST = WebPreviewNavigation.displayHost(WEB_VIEW_BASE_URL)

/** 外部地址/失败地址在界面上最多显示这么多字符，避免长 URL 把对话框撑成一片 */
private const val MAX_EXTERNAL_URL_PREVIEW = 120
