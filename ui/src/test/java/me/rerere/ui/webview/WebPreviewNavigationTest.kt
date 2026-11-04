package me.rerere.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航策略的单测：这里是“哪个地址能进 WebView、哪个必须拒绝、哪个交给系统”的唯一判定点，
 * 所以每条分支都直接钉住，避免以后改正则时悄悄把 file:/javascript: 放进来。
 */
class WebPreviewNavigationTest {

    @Test
    fun `http and https load inline`() {
        assertEquals(WebPreviewNavigation.Decision.Inline, WebPreviewNavigation.decide("https://example.com/a?b=1"))
        assertEquals(WebPreviewNavigation.Decision.Inline, WebPreviewNavigation.decide("http://example.com"))
        // scheme 大小写不敏感
        assertEquals(WebPreviewNavigation.Decision.Inline, WebPreviewNavigation.decide("HTTPS://Example.com/x"))
        // 两侧空白可以容忍
        assertEquals(WebPreviewNavigation.Decision.Inline, WebPreviewNavigation.decide("  https://example.com  "))
    }

    @Test
    fun `local file and script schemes are blocked`() {
        listOf(
            "file:///sdcard/secret.txt",
            "content://com.example.provider/secret",
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "blob:https://example.com/1234",
            "about:blank",
            "chrome://settings",
            "view-source:https://example.com",
            "jar:file:///data/app/x.jar!/a.html",
            "filesystem:https://example.com/temporary/a",
        ).forEach { url ->
            assertEquals("应被拒绝: $url", WebPreviewNavigation.Decision.Blocked, WebPreviewNavigation.decide(url))
        }
    }

    @Test
    fun `other apps schemes are external and must be confirmed first`() {
        assertEquals(
            WebPreviewNavigation.Decision.External("mailto"),
            WebPreviewNavigation.decide("mailto:someone@example.com"),
        )
        assertEquals(
            WebPreviewNavigation.Decision.External("tel"),
            WebPreviewNavigation.decide("tel:+8613800000000"),
        )
        assertEquals(
            WebPreviewNavigation.Decision.External("intent"),
            WebPreviewNavigation.decide("intent://scan/#Intent;scheme=zxing;end"),
        )
        assertEquals(
            WebPreviewNavigation.Decision.External("mqqopensdkapi"),
            WebPreviewNavigation.decide("mqqopensdkapi://bizAgent/x"),
        )
        // 工作区 sandbox 链接先被 WorkspaceArtifactLink 截走；真到 WebView 里也只是“外部确认”，不会读文件
        assertEquals(
            WebPreviewNavigation.Decision.External("sandbox"),
            WebPreviewNavigation.decide("sandbox:/workspace/a.png"),
        )
    }

    @Test
    fun `blank malformed and space-containing addresses are blocked`() {
        listOf("", "   ", "/workspace/a.png", "example.com", "https://example.com/a b", "https://exa\u0000mple.com")
            .forEach { url ->
                assertEquals("应被拒绝: [$url]", WebPreviewNavigation.Decision.Blocked, WebPreviewNavigation.decide(url))
            }
    }

    @Test
    fun `schemeOf only reports real schemes`() {
        assertEquals("https", WebPreviewNavigation.schemeOf("https://example.com"))
        assertEquals("mailto", WebPreviewNavigation.schemeOf("mailto:a@b.c"))
        assertNull(WebPreviewNavigation.schemeOf("/workspace/a.png"))
        assertNull(WebPreviewNavigation.schemeOf("example.com/path"))
        assertNull(WebPreviewNavigation.schemeOf(""))
    }

    @Test
    fun `isWebUrl is true only for http and https`() {
        assertTrue(WebPreviewNavigation.isWebUrl("https://example.com"))
        assertTrue(WebPreviewNavigation.isWebUrl("http://example.com"))
        assertFalse(WebPreviewNavigation.isWebUrl("file:///sdcard/a"))
        assertFalse(WebPreviewNavigation.isWebUrl("mailto:a@b.c"))
        assertFalse(WebPreviewNavigation.isWebUrl("intent://x"))
    }

    @Test
    fun `displayHost shows only the host`() {
        assertEquals("example.com", WebPreviewNavigation.displayHost("https://www.Example.com:8443/path?token=1#x"))
        assertEquals("example.com", WebPreviewNavigation.displayHost("https://example.com"))
        assertEquals("a.b", WebPreviewNavigation.displayHost("https://user:pass@a.b/c"))
        assertEquals("[::1]", WebPreviewNavigation.displayHost("https://[::1]:8080/x"))
        assertNull(WebPreviewNavigation.displayHost("http://"))
        // 本地内容与非 http(s) 不给 host，让调用方用自己的标题兜底
        assertNull(WebPreviewNavigation.displayHost("file:///sdcard/a.html"))
        assertNull(WebPreviewNavigation.displayHost(""))
    }
}
