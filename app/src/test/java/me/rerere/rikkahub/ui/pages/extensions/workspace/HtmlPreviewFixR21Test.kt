package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r21 网页/SVG 预览修复的纯策略测试。
 *
 * 这些用例钉住的是“交给 WebView 的那份文档长什么样”，不需要真机：
 * - 主文档改成内存加载后，HTML 的 CSP 只能靠 meta，SVG 一定不能被塞 HTML 标记；
 * - charset 要留在 `<meta http-equiv>` 前面，否则中文正文会乱码/空白；
 * - 片段式 HTML 要补文档壳，否则 meta 掉进 body，CSP 被忽略；
 * - `#fragment`、相对资源作用域、外网开关的老行为不能被这次改动带坏。
 */
class HtmlPreviewFixR21Test {

    private val cspOffline = htmlPreviewCsp(false)
    private val cspOnline = htmlPreviewCsp(true)

    private val fullPage = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>顺着海风骑</title>
        <style>:root{--paper:#f4f1e8}body{margin:0;background:var(--paper)}.scene{aspect-ratio:1200/700}</style>
        </head>
        <body>
        <main class="page"><h1 id="page-title">今天，顺着<span>海风</span>骑。</h1>
        <svg class="scene" id="scene" viewBox="0 0 1200 700"><title id="art-title">鹈鹕</title><path d="M0 0h1200v700H0z"/></svg>
        </main>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun documentMimeKeepsSvgAsXml() {
        assertEquals("text/html", htmlPreviewDocumentMime(false))
        assertEquals("image/svg+xml", htmlPreviewDocumentMime(true))
    }

    @Test
    fun htmlDocumentGetsCspMetaInsideHeadAfterCharset() {
        val doc = htmlPreviewDocumentSource(fullPage, isSvg = false, csp = cspOffline)
        val meta = "Content-Security-Policy"
        assertTrue(Regex(meta).findAll(doc).count() == 1)
        // charset 必须在 CSP meta 前面，否则中文正文有变成乱码/空白的风险
        assertTrue(doc.indexOf("charset") < doc.indexOf("http-equiv=\"Content-Security-Policy\""))
        assertTrue(doc.indexOf("<meta charset=\"utf-8\">") < doc.indexOf("http-equiv=\"Content-Security-Policy\""))
        // 仍然在 head 里，且在 title 之前
        assertTrue(doc.indexOf("<head>") < doc.indexOf("http-equiv=\"Content-Security-Policy\""))
        assertTrue(doc.indexOf("http-equiv=\"Content-Security-Policy\"") < doc.indexOf("<title>"))
        // 页面本体一个字都不能少：标题、SVG、CSS 变量、aspect-ratio 都还在
        listOf("今天，顺着", "id=\"scene\"", "viewBox=\"0 0 1200 700\"", "--paper:#f4f1e8", "aspect-ratio:1200/700")
            .forEach { assertTrue(it, doc.contains(it)) }
        assertTrue(doc.trimStart().startsWith("<!DOCTYPE html>"))
        // 默认断网：CSP 里不能有 https:
        assertFalse(doc.contains("https:"))
        assertTrue(doc.contains("frame-src 'none'"))
    }

    @Test
    fun networkSwitchOnlyWidensCspWhenTurnedOn() {
        val offline = htmlPreviewDocumentSource(fullPage, isSvg = false, csp = cspOffline)
        assertFalse(offline.contains("https:"))
        val online = htmlPreviewDocumentSource(fullPage, isSvg = false, csp = cspOnline)
        assertTrue(online.contains("https:"))
        // 打开外网也只放行 https 主站资源，frame/worker/form 依旧封死
        assertTrue(online.contains("frame-src 'none'"))
        assertTrue(online.contains("worker-src 'none'"))
    }

    @Test
    fun documentPolicyCannotBypassApplicationPolicy() {
        val already = """<html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src * 'unsafe-inline'"></head><body>x</body></html>"""
        val doc = htmlPreviewDocumentSource(already, isSvg = false, csp = cspOffline)
        assertEquals(2, Regex("Content-Security-Policy").findAll(doc).count())
        assertTrue(doc.contains("frame-src 'none'"))
        assertTrue(doc.contains("default-src * 'unsafe-inline'"))
        assertEquals(doc, injectPreviewCsp(already, cspOffline))
    }

    @Test
    fun svgDocumentIsPassedThroughUntouched() {
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><title>t</title><circle cx="5" cy="5" r="4"/></svg>
        """.trimIndent()
        // SVG 是 XML：塞 HTML 的 <meta> 会直接破坏文档，必须原样交给 WebView
        assertEquals(svg, htmlPreviewDocumentSource(svg, isSvg = true, csp = cspOffline))
        assertFalse(htmlPreviewDocumentSource(svg, isSvg = true, csp = cspOffline).contains("<meta"))
        assertTrue(htmlPreviewDocumentSource(svg, isSvg = true, csp = cspOnline) == svg)
    }

    @Test
    fun htmlFragmentGetsMinimalDocumentShell() {
        val fragment = "<div style=\"width:100%\">只有一段</div><script>void 0</script>"
        val doc = htmlPreviewDocumentSource(fragment, isSvg = false, csp = cspOffline)
        assertTrue(doc.startsWith("<!DOCTYPE html><html><head><meta charset=\"utf-8\">"))
        assertTrue(doc.contains("Content-Security-Policy"))
        assertTrue(doc.contains("<body>") && doc.endsWith("</body></html>"))
        assertTrue(doc.contains("只有一段"))
        // 片段式 HTML 以前是把 meta 顶在 body 里（CSP 会被忽略），现在必须进 head
        assertTrue(doc.indexOf("http-equiv=\"Content-Security-Policy\"") < doc.indexOf("<body>"))
    }

    @Test
    fun doctypeOnlyDocumentKeepsMetaOutsideQuirksMode() {
        val doc = htmlPreviewDocumentSource("<!doctype html><p>x</p>", isSvg = false, csp = cspOffline)
        assertTrue(doc.indexOf("<!doctype") < doc.indexOf("<meta http-equiv="))
    }

    @Test
    fun fragmentIdIsScopedAndInjectionProof() {
        assertEquals("scene", htmlPreviewFragmentId("#scene"))
        assertEquals("scene", htmlPreviewFragmentId(" scene "))
        assertEquals("a-b_c.d:e", htmlPreviewFragmentId("#a-b_c.d:e"))
        listOf("", "   ", "#", "#" + "x".repeat(65), "x');alert(1);//", "a<b", "a b", "a/../b", "a\";alert(1)//")
            .forEach { assertNull(it, htmlPreviewFragmentId(it)) }

        val script = htmlPreviewFragmentScrollScript("#scene")
        assertTrue(script!!.contains("getElementById('scene')"))
        assertTrue(script.contains("scrollIntoView"))
        // 带引号/括号的 fragment 根本不会生成脚本
        assertNull(htmlPreviewFragmentScrollScript("x');alert(1);//"))
    }

    @Test
    fun contentProbeOnlyReportsPresence() {
        // 探针只回 0/1，不读正文内容，也不把页面文本带进日志
        assertFalse(HTML_PREVIEW_CONTENT_PROBE.contains("innerHTML"))
        assertFalse(HTML_PREVIEW_CONTENT_PROBE.contains("console."))
        assertTrue(HTML_PREVIEW_CONTENT_PROBE.contains("return 1"))
        assertTrue(HTML_PREVIEW_CONTENT_PROBE.contains("return 0"))
        // 独立 SVG 文档没有 body，必须走 documentElement 分支
        assertTrue(HTML_PREVIEW_CONTENT_PROBE.contains("tagName==='svg'"))
    }

    @Test
    fun reloadPathStillMapsToInMemoryDocument() {
        // 页面自己 reload 时会重新请求主文档路径，拦截器仍然要用内存文档顶上
        assertEquals("index.html", htmlPreviewResourcePath("https://p.invalid/index.html", "p.invalid"))
        assertEquals("index.svg", htmlPreviewResourcePath("https://p.invalid/index.svg", "p.invalid"))
        assertEquals("index.html", htmlPreviewResourcePath("https://p.invalid/index.html#scene", "p.invalid"))
        assertNull(htmlPreviewResourcePath("https://p.invalid/../etc/passwd.html", "p.invalid"))
        assertNull(htmlPreviewResourcePath("https://evil.invalid/index.html", "p.invalid"))
        assertFalse(isExternalPreviewResourceAllowed("http://cdn.example/x.js"))
        assertTrue(isExternalPreviewResourceAllowed("https://cdn.example/x.js"))
    }
}
