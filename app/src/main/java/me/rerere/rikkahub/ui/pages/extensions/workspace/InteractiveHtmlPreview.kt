package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.mimeTypeOf
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * 网页类产物的应用内预览。
 *
 * - origin 是每次随机生成的 `https://preview-<uuid>.invalid`：外网不可解析，页面的请求只能落到
 *   [WebViewClient.shouldInterceptRequest] 的本地 reader（父目录受限）或直接 403；
 * - JS 打开（关闭 JS 会得到一堆空壳页面），但 `file://`/`content://` 访问关闭，不注册任何
 *   `addJavascriptInterface` bridge、不开窗口、拒绝权限请求；
 * - 外网默认关闭，用户显式打开开关后只放行 https 主站资源，并用 CSP 挡掉 frame/worker/form。
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
    val reader by rememberUpdatedState(resourceReader)
    // 开关切换时换一个 origin 重建 WebView，避免已加载的外网资源留在同一个文档里
    val host = remember(html, allowNetwork) { "preview-${UUID.randomUUID()}.invalid" }
    val mime = if (isSvg) "image/svg+xml" else "text/html"
    val base = "https://$host/index.${if (isSvg) "svg" else "html"}"
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.r11_preview_network), Modifier.weight(1f))
            Switch(checked = allowNetwork, onCheckedChange = { allowNetwork = it })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(host) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val resourceCache = mutableMapOf<String, ByteArray>()
                        var cachedBytes = 0
                        var fellBack = false
                        WebView(ctx).apply {
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
                            }
                            webViewClient = object : WebViewClient() {
                                private fun response(data: ByteArray, mimeType: String) = WebResourceResponse(
                                    mimeType, "utf-8", 200, "OK", mapOf(
                                        "Content-Security-Policy" to htmlPreviewCsp(allowNetwork),
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
                                        if (request.url.path == java.net.URI(base).path) {
                                            return response(html.toByteArray(), mime)
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

                                /**
                                 * 兜底：万一这台设备上的 WebView 没有为 `.invalid` 主文档走拦截器、直接报网络错误，
                                 * 就换成内存文档继续渲染（相对资源仍解析到这个 origin），不要给用户留一张白屏。
                                 */
                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame || fellBack) return
                                    fellBack = true
                                    runCatching {
                                        view.loadDataWithBaseURL(
                                            base,
                                            injectPreviewCsp(html, htmlPreviewCsp(allowNetwork)),
                                            mime,
                                            "utf-8",
                                            null,
                                        )
                                    }
                                }
                            }
                            loadUrl(base + (fragment?.let { "#" + android.net.Uri.encode(it) } ?: ""))
                        }
                    },
                    onRelease = { view -> view.stopLoading(); view.webChromeClient = null; view.destroy() },
                )
            }
        }
    }
}
