package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.mimeTypeOf
import java.io.ByteArrayInputStream
import java.util.UUID

/** 预览失败的种类；只用来挑一句最短的说明，不承载任何页面内容。 */
private enum class PreviewFailureKind { LOAD, EMPTY, RENDERER }

private data class PreviewFailure(val kind: PreviewFailureKind, val detail: String? = null)

/**
 * 网页类产物的应用内预览。
 *
 * - 主文档直接内存加载（`loadDataWithBaseURL` + 随机 `https://preview-<uuid>.invalid` origin）：
 *   不要求 `.invalid` 真的走一次网络导航，也就不依赖“主框架请求会不会被拦截器接住”；
 * - 相对资源仍然解析到同一个 origin，只能落到 [WebViewClient.shouldInterceptRequest] 的本地
 *   reader（父目录受限）或 403；
 * - JS 打开（关闭 JS 会得到一堆空壳页面），但 `file://`/`content://` 关闭，不注册任何
 *   `addJavascriptInterface` bridge、不开窗口、拒绝权限请求、证书错不放过；
 * - 外网默认关闭，用户显式打开开关后只放行 https 主站资源，并用 CSP 挡掉 frame/worker/form；
 * - 主文档失败 / 渲染进程退出 / “加载完了但页面是空的”都给一条可见诊断和一个重试入口，
 *   不再把用户留在没有提示的白屏上。
 */
@Composable
internal fun InteractiveHtmlPreview(
    html: String,
    modifier: Modifier = Modifier,
    isSvg: Boolean = false,
    fragment: String? = null,
    resourceReader: (suspend (String) -> ByteArray)? = null,
) {
    val context = LocalContext.current
    var allowNetwork by remember(html) { mutableStateOf(false) }
    var softwareRenderer by remember(html) { mutableStateOf(false) }
    // 用户重试、渲染进程重建都靠这个计数换一个新的 WebView
    var attempt by remember(html) { mutableStateOf(0) }
    var rendererRestarts by remember(html) { mutableStateOf(0) }
    var failure by remember(html) { mutableStateOf<PreviewFailure?>(null) }
    val reader by rememberUpdatedState(resourceReader)
    // 开关切换时换一个 origin 重建 WebView，避免已加载的外网资源留在同一个文档里
    val host = remember(html, allowNetwork) { "preview-${UUID.randomUUID()}.invalid" }
    val mime = htmlPreviewDocumentMime(isSvg)
    val documentPath = if (isSvg) "/index.svg" else "/index.html"
    val base = "https://$host$documentPath"
    val csp = htmlPreviewCsp(allowNetwork)
    // 主文档：HTML 注入 CSP meta，SVG 保持原样（XML 塞不进 HTML meta）
    val documentSource = remember(html, isSvg, csp) { htmlPreviewDocumentSource(html, isSvg, csp) }
    val fragmentScroll = remember(fragment) { htmlPreviewFragmentScrollScript(fragment) }
    // base 带上 fragment 让页面自己的 location.hash 是对的
    val documentUrl = remember(base, fragment) {
        fragment?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { base + "#" + Uri.encode(it) } ?: base
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.r11_preview_network), Modifier.weight(1f))
            Switch(checked = allowNetwork, onCheckedChange = { allowNetwork = it })
            TextButton(onClick = { failure = null; softwareRenderer = !softwareRenderer }) {
                Text(stringResource(if (softwareRenderer) R.string.r21_preview_standard_renderer else R.string.r21_preview_compat_renderer))
            }
        }
        failure?.let { current ->
            PreviewFailureBanner(
                failure = current,
                onRetry = {
                    failure = null
                    attempt += 1
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(host, attempt, softwareRenderer) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val resourceCache = mutableMapOf<String, ByteArray>()
                        var cachedBytes = 0
                        // 只数不存：脚本错误的具体文本可能包含页面内容，既不进日志也不进界面
                        var scriptErrors = 0
                        WebView(ctx).apply {
                            // Explicit opt-in fallback for device GPU/WebView compositing failures.
                            // Keep normal rendering fast; never impose software rasterization globally.
                            if (softwareRenderer) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            settings.apply {
                                javaScriptEnabled = true
                                // 随机 origin + 无 bridge 的临时存储，很多前端 demo 依赖 localStorage 才不会白屏
                                domStorageEnabled = true
                                databaseEnabled = false
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                mediaPlaybackRequiresUserGesture = true
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) {
                                    request.deny()
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                    if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        scriptErrors += 1
                                    }
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                private fun response(data: ByteArray, mimeType: String) = WebResourceResponse(
                                    mimeType, "utf-8", 200, "OK", mapOf(
                                        "Content-Security-Policy" to csp,
                                        "X-Content-Type-Options" to "nosniff",
                                        "Cache-Control" to "no-store",
                                    ), ByteArrayInputStream(data),
                                )

                                private fun denied() = WebResourceResponse(
                                    "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()),
                                )

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    val url = request.url.toString()
                                    if (request.url.host == host) {
                                        // 页面自己 reload / 重新导航回主文档时仍然由内存文档顶住
                                        if (request.url.path == documentPath) {
                                            return response(documentSource.toByteArray(), mime)
                                        }
                                        val relative = htmlPreviewResourcePath(url, host) ?: return denied()
                                        if (request.method != "GET") return denied()
                                        val bytes = synchronized(resourceCache) {
                                            resourceCache[relative] ?: runCatching {
                                                val read = reader ?: error("No local asset reader")
                                                runBlocking { read(relative) }.also {
                                                    require(it.size <= HTML_PREVIEW_MAX_RESOURCE_BYTES)
                                                    require(cachedBytes + it.size <= HTML_PREVIEW_MAX_TOTAL_BYTES)
                                                    resourceCache[relative] = it
                                                    cachedBytes += it.size
                                                }
                                            }.getOrNull()
                                        } ?: return denied()
                                        return response(bytes, when (relative.substringAfterLast('.').lowercase()) {
                                            "js", "mjs" -> "text/javascript"
                                            "css" -> "text/css"
                                            "json" -> "application/json"
                                            "svg" -> "image/svg+xml"
                                            "html", "htm", "xhtml" -> "text/html"
                                            else -> mimeTypeOf(relative)
                                        })
                                    }
                                    return if (allowNetwork && isExternalPreviewResourceAllowed(url)) null else denied()
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (request.url.host == host) return false
                                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https") {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (failure?.kind != PreviewFailureKind.RENDERER) failure = null
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    // 新页面已经渲染出来，渲染进程那条提示可以撤了
                                    if (failure?.kind == PreviewFailureKind.RENDERER) failure = null
                                    // 兼容性重绘提示，见 nudgePreviewRepaint 的注释
                                    nudgePreviewRepaint(view)
                                    view.postDelayed({ nudgePreviewRepaint(view) }, 320L)
                                    fragmentScroll?.let { script ->
                                        view.postDelayed({ runCatching { view.evaluateJavascript(script, null) } }, 60L)
                                    }
                                    // 一次性的“到底画没画出内容”探测，只读 0/1；第一次说空再等一会儿复核一次，
                                    // 避免把异步初始化慢半拍的页面误判成空白
                                    view.postDelayed({ probeContent(view, 0) }, 600L)
                                }

                                private fun probeContent(view: WebView, round: Int) {
                                    runCatching {
                                        view.evaluateJavascript(HTML_PREVIEW_CONTENT_PROBE) { result ->
                                            val empty = result?.trim()?.toIntOrNull() == 0
                                            if (empty && round < 1) {
                                                view.postDelayed({ probeContent(view, round + 1) }, 900L)
                                            } else if (empty && failure == null) {
                                                failure = PreviewFailure(
                                                    PreviewFailureKind.EMPTY,
                                                    scriptErrors.takeIf { it > 0 }?.let { "console errors: $it" },
                                                )
                                            }
                                        }
                                    }
                                }

                                private fun reportMainFrameFailure(detail: String?) {
                                    if (failure?.kind == PreviewFailureKind.RENDERER) return
                                    failure = PreviewFailure(PreviewFailureKind.LOAD, detail?.take(120))
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame) return
                                    reportMainFrameFailure(error.description?.toString())
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse,
                                ) {
                                    if (!request.isForMainFrame) return
                                    reportMainFrameFailure("HTTP ${errorResponse.statusCode}")
                                }

                                /** 证书错误一律拒绝：绝不 handler.proceed() */
                                override fun onReceivedSslError(
                                    view: WebView,
                                    handler: SslErrorHandler,
                                    error: SslError?,
                                ) {
                                    handler.cancel()
                                    reportMainFrameFailure("SSL")
                                }

                                /**
                                 * 渲染进程崩溃/被系统回收：返回 true 表示已处理，否则系统会杀掉整个应用进程。
                                 * 这个 WebView 已经不能再用，换一个新的；自动换两次还不行就把重试交给用户。
                                 * 提示先亮出来，等新页面真的 onPageFinished 再撤。
                                 */
                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    failure = PreviewFailure(PreviewFailureKind.RENDERER)
                                    if (rendererRestarts < 2) {
                                        rendererRestarts += 1
                                        attempt += 1
                                    }
                                    return true
                                }
                            }
                            loadDataWithBaseURL(documentUrl, documentSource, mime, "utf-8", null)
                        }
                    },
                    onRelease = { view ->
                        runCatching {
                            view.stopLoading()
                            view.webChromeClient = null
                            view.destroy()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 兼容性重绘提示：个别设备/WebView 组合在文档提交后只把首帧（常常只剩一层背景色）合成出来，
 * 之后不再刷新合成帧，于是用户看到的是一张“有底色、没内容”的静态画面。
 * 这里只做无害的重排 + 1px 往返滚动，催一次新的合成帧；具体原因还没有被证实，
 * 所以这是保底的重绘提示，不是“已定位根因”的修复。
 */
private fun nudgePreviewRepaint(view: WebView) {
    runCatching {
        val y = view.scrollY
        view.requestLayout()
        view.invalidate()
        view.scrollBy(0, 1)
        view.scrollTo(0, y)
    }
}

@Composable
private fun PreviewFailureBanner(failure: PreviewFailure, onRetry: () -> Unit) {
    val title = when (failure.kind) {
        PreviewFailureKind.LOAD -> stringResource(R.string.r21_preview_failure_load)
        PreviewFailureKind.EMPTY -> stringResource(R.string.r21_preview_failure_empty)
        PreviewFailureKind.RENDERER -> stringResource(R.string.r21_preview_failure_renderer)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            failure.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onRetry) { Text(stringResource(R.string.r21_preview_failure_retry)) }
    }
}
