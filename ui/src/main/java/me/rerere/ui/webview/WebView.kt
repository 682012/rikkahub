package me.rerere.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.ClientCertRequest
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val TAG = "WebView"

/** 控制台最多留 64 条，每条最多 500 字，避免模型生成的长页面把内存和日志刷爆 */
private const val MAX_CONSOLE_MESSAGES = 64
private const val MAX_CONSOLE_CHARS = 500

/** 每次加载最多往 logcat 回显 20 条 warning/error，其余只进 UI 列表 */
private const val MAX_LOG_ECHO_PER_PAGE = 20

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

private fun ConsoleMessage.shortText(): String =
    message().take(MAX_CONSOLE_CHARS)

internal class MyWebChromeClient(private val state: WebViewState) : WebChromeClient() {
    private var echoedMessages = 0

    fun resetEchoBudget() {
        echoedMessages = 0
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        state.loadingProgress = newProgress / 100f
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        state.pageTitle = title?.take(200)
    }

    /** 预览页不需要摄像头/麦克风/定位；一律拒绝，不给站点弹权限框 */
    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    /** 不创建新窗口（target=_blank 交给同页导航或用户手动打开） */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        state.pushConsoleMessage(consoleMessage)
        val level = consoleMessage.messageLevel()
        if ((level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) &&
            echoedMessages < MAX_LOG_ECHO_PER_PAGE
        ) {
            echoedMessages += 1
            Log.w(
                TAG,
                "onConsoleMessage: ${consoleMessage.shortText()} @${consoleMessage.lineNumber()} " +
                    consoleMessage.sourceId()?.take(120)
            )
        }
        // 自己消费掉，避免系统再打一份（页面里 console.log 刷屏时日志仍然有界）
        return true
    }
}

internal class MyWebViewClient(private val state: WebViewState) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // allowFileAccess/allowContentAccess 已经关掉，这里再兜一层：本地文件与 content 一律 403
        when (request.url.scheme?.lowercase()) {
            "file", "content" -> return blockedResponse()
        }
        return WebViewLocalAssets.intercept(view.context.applicationContext, request.url)
            ?: super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = handleNavigation(view, request.url?.toString(), request.isForMainFrame)

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
        handleNavigation(view, url, isMainFrame = true)

    /**
     * 只让 http/https 在当前 WebView 里加载；
     * file/content/javascript/data 直接拒绝；其它协议记到 [WebViewState.pendingExternalUrl]，
     * 由页面弹一次确认框，用户点了才交给系统——不自动 startActivity。
     */
    private fun handleNavigation(view: WebView, url: String?, isMainFrame: Boolean): Boolean {
        val target = url ?: return true
        return when (val decision = WebPreviewNavigation.decide(target)) {
            WebPreviewNavigation.Decision.Inline -> false
            WebPreviewNavigation.Decision.Blocked -> {
                Log.w(TAG, "blocked navigation: ${WebPreviewNavigation.schemeOf(target) ?: "unknown"}")
                true
            }
            is WebPreviewNavigation.Decision.External -> {
                if (isMainFrame) {
                    state.requestExternalNavigation(target)
                }
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.onPageStarted(url)
        (view?.webChromeClient as? MyWebChromeClient)?.resetEchoBudget()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        state.onPageFinished(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        state.canGoBack = view?.canGoBack() == true
        state.canGoForward = view?.canGoForward() == true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        state.reportLoadError(
            url = request.url?.toString() ?: view?.url,
            description = error.description?.toString()?.take(200),
            sslError = false,
        )
    }

    /** 证书错误一律拒绝：绝不 handler.proceed()，也不留“继续访问”的入口 */
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
        handler.cancel()
        state.reportLoadError(
            url = error?.url ?: view?.url,
            description = "SSL",
            sslError = true,
        )
        Log.w(TAG, "SSL error rejected: primary=${error?.primaryError}")
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest) {
        request.cancel()
    }

    /**
     * 渲染进程崩溃/被系统回收：返回 true 表示“已处理”，否则系统会连整个应用进程一起杀掉。
     * 这里只把状态标成不可用（页面显示错误与外部打开），不再去碰这个已经死掉的 WebView。
     */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        Log.w(TAG, "onRenderProcessGone: didCrash=${detail?.didCrash()}")
        state.reportLoadError(
            url = view?.url,
            description = detail?.let { "renderer didCrash=${it.didCrash()}" },
            sslError = false,
            rendererGone = true,
        )
        return true
    }
}

/**
 * 预览用 WebView 的安全默认值。
 *
 * JS 保留（一般站点和本项目的本地 HTML 预览都要用），但本地文件能力全部关闭：
 * 不给 `addJavascriptInterface` 文件读写，`file:`/`content:` 不加载，混合内容不允许，
 * 不允许 JS 自动开窗，媒体要用户手势。调用方传入的 [WebViewState.settings] 在此之后应用，
 * 只能在“更严”或纯 UI 参数（缩放、宽视口）上覆盖。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyPreviewDefaults() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
        // 在线页面沿用系统 HTTP 缓存（受系统配额约束），不在磁盘上再存一份
        cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
    }
    // 第三方 Cookie 关掉；不 clearCookies / clearCache，免得把用户已经登录的网页会话清掉
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
}

private fun WebView.resetState(
    interfaces: Map<String, Any>,
    clearClients: Boolean = false,
) {
    stopLoading()
    interfaces.forEach { (name, _) ->
        removeJavascriptInterface(name)
    }
    if (clearClients) {
        webChromeClient = null
        webViewClient = WebViewClient()
    }
}

/** 离页时的完整收尾：先停加载、暂停，再解绑、销毁，避免后台定时器和 Activity 泄漏 */
private fun WebView.release(interfaces: Map<String, Any>) {
    stopLoading()
    onPause()
    interfaces.forEach { (name, _) -> removeJavascriptInterface(name) }
    webChromeClient = null
    webViewClient = WebViewClient()
    loadUrl("about:blank")
    clearHistory()
    removeAllViews()
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    destroy()
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    onCreated: (WebView) -> Unit = {},
    onUpdated: (WebView) -> Unit = {},
) {
    // clients 必须跟着 state 走：旧的 remember 没带 key，换 url 时会一直往新 state 里写旧数据
    val webChromeClient = remember(state) { MyWebChromeClient(state) }
    val webViewClient = remember(state) { MyWebViewClient(state) }
    val addedInterfaces = remember(state) { mutableSetOf<String>() }

    DisposableEffect(state) {
        onDispose {
            // 组合离开（返回上一页）时先停掉加载与定时器，AndroidView 的 onRelease 随后 destroy
            state.webView?.stopLoading()
            state.webView?.onPause()
            Log.d(TAG, "DisposableEffect: paused/detached WebView")
        }
    }

    Box(
        modifier = modifier
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )

                    state.webView = this

                    applyPreviewDefaults()
                    settings.javaScriptEnabled = state.javaScriptEnabled
                    settings.apply(state.settings)

                    this.webChromeClient = webChromeClient
                    this.webViewClient = webViewClient

                    // JS bridge 只给应用自己生成的本地 HTML（Mermaid 之类的 resize/导出回调）；
                    // 在线页面不注册任何 bridge，页面的 JS 碰不到宿主对象
                    if (state.content is WebContent.Data) {
                        state.interfaces.forEach { (name, obj) ->
                            addJavascriptInterface(obj, name)
                            addedInterfaces += name
                        }
                    }
                    if (state.isHostPaused) onPause()

                    onCreated(this)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            onReset = {
                it.resetState(state.interfaces)
                addedInterfaces.clear()
                Log.d(TAG, "AndroidView: Resetting WebView")
            },
            onRelease = {
                if (state.webView === it) {
                    state.webView = null
                }
                it.release(state.interfaces)
                addedInterfaces.clear()
                Log.d(TAG, "AndroidView: Releasing WebView")
            },
            update = { webView ->
                state.webView = webView

                // state 换了就换 client，不重建 WebView（避免重复 load 与实例泄漏）
                if (webView.webChromeClient !== webChromeClient) {
                    webView.webChromeClient = webChromeClient
                }
                if (webView.webViewClient !== webViewClient) {
                    webView.webViewClient = webViewClient
                }

                // bridge 只在本地 HTML(Data) 下注册；切到在线页面时主动摘掉，避免遗留宿主对象
                if (state.content is WebContent.Data) {
                    state.interfaces.forEach { (name, obj) ->
                        if (!addedInterfaces.add(name)) return@forEach
                        webView.addJavascriptInterface(obj, name)
                    }
                } else if (addedInterfaces.isNotEmpty()) {
                    addedInterfaces.forEach { webView.removeJavascriptInterface(it) }
                    addedInterfaces.clear()
                }

                webView.settings.javaScriptEnabled = state.javaScriptEnabled
                if (state.isHostPaused) webView.onPause() else webView.onResume()

                // 订阅 reloadToken：Data 内容 reload() 时靠它触发重新 loadDataWithBaseURL
                val reloadToken = state.reloadToken

                when (val content = state.content) {
                    is WebContent.Url -> {
                        // 只有地址真的变了才 load：同一 URL 的重复重组、旋转、返回都不会再发一次请求
                        val currentWebViewUrl = webView.url
                        if (content.url.isNotEmpty() && currentWebViewUrl != content.url) {
                            webView.loadUrl(content.url, content.additionalHttpHeaders)
                        }
                    }

                    is WebContent.Data -> {
                        if (content != state.lastLoadedData || reloadToken != state.lastDataReloadToken) {
                            webView.loadDataWithBaseURL(
                                content.baseUrl,
                                content.data,
                                content.mimeType,
                                content.encoding,
                                content.historyUrl
                            )
                            state.lastLoadedData = content
                            state.lastDataReloadToken = reloadToken
                        }
                    }

                    WebContent.NavigatorOnly -> {
                        // NO-OP: 纯导航状态，加载由 WebViewState 的方法直接驱动
                    }
                }
                onUpdated(webView)
            }
        )

        if (state.isLoading && state.loadError == null) {
            LinearProgressIndicator(
                progress = { state.loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}

// --- State and Content Definition ---
sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
        val clearHistory: Boolean = false
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null
    ) : WebContent()

    data object NavigatorOnly : WebContent()
}

/** 主文档加载失败的原因，界面据此显示“重试”而不是一片空白 */
data class WebViewLoadError(
    val url: String?,
    val description: String?,
    val sslError: Boolean = false,
    /** 渲染进程崩溃/被回收：这个 WebView 实例已经不能再用，重试同一个实例没有意义 */
    val rendererGone: Boolean = false,
)

@Stable // Mark as Stable for better Compose performance
class WebViewState(
    initialContent: WebContent = WebContent.NavigatorOnly,
    val interfaces: Map<String, Any> = emptyMap(),
    val settings: WebSettings.() -> Unit = {}
) {
    // --- Content State ---
    var content: WebContent by mutableStateOf(initialContent)
    internal var lastLoadedData: WebContent.Data? = null
    internal var reloadToken: Int by mutableIntStateOf(0)
    internal var lastDataReloadToken: Int = 0

    // --- Loading State ---
    var isLoading: Boolean by mutableStateOf(false)
        internal set
    var loadingProgress: Float by mutableFloatStateOf(0f)
        internal set

    // --- Page Information ---
    var pageTitle: String? by mutableStateOf(null)
        internal set
    var currentUrl: String? by mutableStateOf(null)
        internal set

    // --- Navigation State ---
    var canGoBack: Boolean by mutableStateOf(false)
        internal set
    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    // --- Failure / external scheme ---
    /** 主文档加载失败；null 表示正常。页面显示重试入口。 */
    var loadError: WebViewLoadError? by mutableStateOf(null)
        internal set

    /** 页面里点了非 http(s) 链接，等待用户确认后才 startActivity */
    var pendingExternalUrl: String? by mutableStateOf(null)
        internal set

    /** 宿主的生命周期：页面 onPause 时置 true，避免 WebView 在后台继续跑 JS/媒体 */
    var isHostPaused: Boolean by mutableStateOf(false)
        internal set

    // --- Console Message ---
    var consoleMessages: List<ConsoleMessage> by mutableStateOf(emptyList())
        internal set

    // --- Settings ---
    var javaScriptEnabled: Boolean by mutableStateOf(true)

    // --- WebView Instance ---
    var webView: WebView? by mutableStateOf(null)
        internal set

    // --- Public Actions ---

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String> = emptyMap()
    ) {
        loadError = null
        content = WebContent.Url(url, additionalHttpHeaders)
    }

    fun loadData(
        data: String,
        baseUrl: String? = null,
        encoding: String = "utf-8",
        mimeType: String? = null,
        historyUrl: String? = null
    ) {
        loadError = null
        content = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
    }

    // --- Navigation Methods ---
    fun goBack() {
        loadError = null
        webView?.goBack()
    }

    fun goForward() {
        loadError = null
        webView?.goForward()
    }

    /**
     * 刷新当前页。URL 内容直接 reload（不会和 update 里的 loadUrl 打架再发一次请求）；
     * Data 内容靠 [reloadToken] 让 update 重新 loadDataWithBaseURL。
     */
    fun reload() {
        loadError = null
        val view = webView
        if (view == null) {
            if (content is WebContent.Data) reloadToken += 1
            return
        }
        when (content) {
            is WebContent.Data -> {
                lastLoadedData = null
                reloadToken += 1
            }
            else -> view.reload()
        }
    }

    fun stopLoading() {
        webView?.stopLoading()
        isLoading = false
    }

    fun clearHistory() {
        webView?.clearHistory()
    }

    fun onHostPause() {
        isHostPaused = true
        webView?.onPause()
    }

    fun onHostResume() {
        isHostPaused = false
        webView?.onResume()
    }

    fun requestExternalNavigation(url: String) {
        pendingExternalUrl = url
    }

    fun consumePendingExternalNavigation(): String? {
        val url = pendingExternalUrl
        pendingExternalUrl = null
        return url
    }

    fun clearLoadError() {
        loadError = null
    }

    /**
     * 重试当前失败的主文档。直接对 webView 发请求，不动 [content]，
     * 这样不会触发 update 里那套“地址变化才 load”的判断又补一次。
     */
    fun retry() {
        // 渲染进程已经没了：同一实例重试必然再次失败，界面此时只提供外部打开
        if (loadError?.rendererGone == true) return
        val failedUrl = loadError?.url
        loadError = null
        run {
            val view = webView ?: return@run
            when (val current = content) {
                is WebContent.Url -> view.loadUrl(failedUrl ?: current.url, current.additionalHttpHeaders)
                is WebContent.Data -> {
                    lastLoadedData = null
                    reloadToken += 1
                }
                WebContent.NavigatorOnly -> failedUrl?.let(view::loadUrl)
            }
        }
    }

    internal fun onPageStarted(url: String?) {
        isLoading = true
        loadError = null
        currentUrl = url
    }

    internal fun onPageFinished(view: WebView?, url: String?) {
        isLoading = false
        loadingProgress = 0f
        currentUrl = url ?: currentUrl
        pageTitle = view?.title?.take(200)
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    internal fun reportLoadError(
        url: String?,
        description: String?,
        sslError: Boolean,
        rendererGone: Boolean = false,
    ) {
        isLoading = false
        loadingProgress = 0f
        loadError = WebViewLoadError(
            url = url,
            description = description ?: if (sslError) "SSL" else null,
            sslError = sslError,
            rendererGone = rendererGone,
        )
    }

    fun pushConsoleMessage(message: ConsoleMessage) {
        consoleMessages = (consoleMessages + message).takeLast(MAX_CONSOLE_MESSAGES)
    }
}

@Composable
fun rememberWebViewState(
    url: String = "about:blank",
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(url, additionalHttpHeaders) { // Use keys for better recomposition control
    WebViewState(
        initialContent = WebContent.Url(url, additionalHttpHeaders),
        interfaces = interfaces,
        settings = settings
    )
}

@Composable
fun rememberWebViewState(
    data: String,
    baseUrl: String? = null,
    encoding: String = "utf-8",
    mimeType: String? = null,
    historyUrl: String? = null,
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(data, baseUrl, encoding, mimeType, historyUrl) { // Use keys
    WebViewState(
        initialContent = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl),
        interfaces = interfaces,
        settings = settings
    )
}
