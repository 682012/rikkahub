package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.net.URI
import java.util.Locale

internal const val HTML_PREVIEW_MAX_RESOURCE_BYTES = 8 * 1024 * 1024
internal const val HTML_PREVIEW_MAX_TOTAL_BYTES = 32 * 1024 * 1024

/** Web content may see web assets in the selected directory, never arbitrary local files. */
internal fun htmlPreviewResourcePath(url: String, host: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host != host || uri.port != -1 || uri.userInfo != null) return null
    val path = uri.path.removePrefix("/")
    if (!isPreviewAssetPath(path)) return null
    path
}.getOrNull()

/**
 * 一条相对网页资源路径能不能交给 WebView：不含 `..`/隐藏文件/反手斜杠/百分号编码，
 * 扩展名在网页资源白名单内。workspace 的 scoped reader 和聊天附件预览共用这一份判定。
 */
internal fun isPreviewAssetPath(relative: String): Boolean {
    if (relative.isBlank() || relative.startsWith('/') || relative.length > 512) return false
    if (relative.any { it == '\\' || it == '%' || it.code < 32 }) return false
    val parts = relative.split('/')
    if (parts.any { it.isBlank() || it.startsWith('.') }) return false
    return parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in HTML_PREVIEW_RESOURCE_EXTENSIONS
}

private val HTML_PREVIEW_RESOURCE_EXTENSIONS = setOf(
    "html", "htm", "xhtml", "css", "js", "mjs", "json", "svg",
    "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "bmp",
    "woff", "woff2", "ttf", "otf", "mp3", "ogg", "wav", "mp4", "webm",
)

internal fun htmlPreviewCsp(allowNetwork: Boolean): String {
    val sources = if (allowNetwork) "'self' https:" else "'self'"
    return "default-src $sources data: blob:; script-src $sources 'unsafe-inline' 'unsafe-eval'; " +
        "style-src $sources 'unsafe-inline'; connect-src $sources; frame-src 'none'; " +
        "worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'self'"
}

/**
 * 极少数 WebView 不会为自选 `.invalid` 主文档回调 `shouldInterceptRequest`（外网不可达时直接出错误页，
 * 也就是白屏）。这种情况退化成内存文档，但相对资源仍然解析到同一个 https origin，
 * 依旧走拦截器；CSP 改由 `<meta http-equiv>` 带上，网络默认仍然是关的。
 */
internal fun injectPreviewCsp(html: String, csp: String): String {
    val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp.replace("\"", "&quot;")}\">"
    Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { head ->
        val at = head.range.last + 1
        return html.substring(0, at) + meta + html.substring(at)
    }
    Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { doctype ->
        val at = doctype.range.last + 1
        return html.substring(0, at) + meta + html.substring(at)
    }
    return meta + html
}

internal fun isExternalPreviewResourceAllowed(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    uri.scheme == "https" && uri.userInfo == null && !host.contains(':') &&
        host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
        !host.matches(Regex("[0-9.]+"))
}.getOrDefault(false)
