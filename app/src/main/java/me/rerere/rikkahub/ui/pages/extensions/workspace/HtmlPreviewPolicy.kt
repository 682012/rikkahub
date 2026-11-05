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
 * 内存加载的主文档用哪种 MIME。
 *
 * SVG 必须是 `image/svg+xml`：它是 XML 文档，只有 HTML 才谈得上用 `<meta>` 带 CSP。
 */
internal fun htmlPreviewDocumentMime(isSvg: Boolean): String = if (isSvg) "image/svg+xml" else "text/html"

/**
 * 真正交给 WebView 的主文档（`loadDataWithBaseURL` 拿不到响应头，CSP 只能走 meta）。
 *
 * - HTML：注入 CSP meta；`<meta charset>` 保持在前面，避免中文正文变成乱码或空白；
 * - HTML 片段（没有 `html`/`head`/doctype，常见于“只贴了一段 body”的产物）：补一层最小文档壳，
 *   否则 meta 会掉进 body、CSP 被忽略，编码也只能靠 WebView 猜；
 * - SVG：原样返回。往 XML 里塞 HTML 的 `<meta>` 会破坏文档，SVG 的网络限制由
 *   `shouldInterceptRequest` 的拦截器负责（默认只有 `'self'` 可达）。
 */
internal fun htmlPreviewDocumentSource(source: String, isSvg: Boolean, csp: String): String {
    if (isSvg) return source
    if (looksLikeHtmlDocument(source)) return injectPreviewCsp(source, csp)
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" + cspMetaTag(csp) +
        "</head><body>" + source + "</body></html>"
}

private fun looksLikeHtmlDocument(source: String): Boolean =
    Regex("<(html|head)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(source) ||
        Regex("^\\uFEFF?\\s*<!doctype", RegexOption.IGNORE_CASE).containsMatchIn(source)

private fun cspMetaTag(csp: String): String =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp.replace("\"", "&quot;")}\">"

private val HTML_PREVIEW_HEAD_TAG = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_PREVIEW_DOCTYPE = Regex("^\\uFEFF?\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_PREVIEW_CHARSET_META = Regex("^\\s*<meta[^>]*charset[^>]*>", RegexOption.IGNORE_CASE)

/**
 * 把应用 CSP 塞进 HTML 文档：保留原网页 CSP，叠加限制；插在 `<meta charset>` 之后（charset 声明要留在
 * 前 1024 字节内），没有 head 时退到 doctype 之后（HTML 解析器会把这段 meta 提升进隐式 head，
 * 放在 doctype 前面则会进 quirks mode）。
 */
internal fun injectPreviewCsp(html: String, csp: String): String {
    // The document's own policy must not disable the application's policy. Multiple CSP
    // policies intersect, so preserve the original and always add our independent policy.
    val meta = cspMetaTag(csp)
    HTML_PREVIEW_HEAD_TAG.find(html)?.let { head ->
        val afterHead = head.range.last + 1
        val charset = HTML_PREVIEW_CHARSET_META.find(html.substring(afterHead))
        val at = charset?.let { afterHead + it.range.last + 1 } ?: afterHead
        return html.substring(0, at) + meta + html.substring(at)
    }
    HTML_PREVIEW_DOCTYPE.find(html)?.let { doctype ->
        val at = doctype.range.last + 1
        return html.substring(0, at) + meta + html.substring(at)
    }
    return meta + html
}

/**
 * 只接受普通标识符的 fragment（`#scene` 之类）。返回 null 表示不该去滚动，
 * 也顺带保证这个 id 能安全地拼进下面那段一次性 JS，不会变成注入点。
 */
internal fun htmlPreviewFragmentId(fragment: String?): String? {
    val id = fragment?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
    return id.takeIf { candidate -> candidate.all { it.isLetterOrDigit() || it in "-_.:" } }
}

/**
 * 主文档改成内存加载后，`loadDataWithBaseURL` 不会替我们跳到 `#id`，
 * 所以加载完成后跑一次这个一次性脚本补上（不是常驻运行时，跑完就没有了）。
 */
internal fun htmlPreviewFragmentScrollScript(fragment: String?): String? {
    val id = htmlPreviewFragmentId(fragment) ?: return null
    return "(function(){try{var t=document.getElementById('$id')||document.querySelector('[name=\"$id\"]');" +
        "if(t&&t.scrollIntoView){t.scrollIntoView();}}catch(e){}})()"
}

/**
 * 加载完之后的一次性探测：页面到底有没有画出东西。
 *
 * 只回 0/1，不读也不传任何正文内容（日志里也不会出现页面文本）。用来兜住“文档提交了、
 * 但看起来是空白的”这种情况，好给用户一条可见的诊断，而不是继续留一张空页。
 */
internal val HTML_PREVIEW_CONTENT_PROBE: String =
    "(function(){try{var r=document.documentElement;" +
        "if(r&&r.tagName==='svg')return 1;var b=document.body;if(!b)return 0;" +
        "if((b.textContent||'').trim().length>0)return 1;" +
        "if(b.querySelector('svg,img,canvas,video,iframe,object,embed,picture,table,input,button,select,textarea'))return 1;" +
        "return 0;}catch(e){return 1;}})()"

internal fun isExternalPreviewResourceAllowed(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    uri.scheme == "https" && uri.userInfo == null && !host.contains(':') &&
        host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
        !host.matches(Regex("[0-9.]+"))
}.getOrDefault(false)
