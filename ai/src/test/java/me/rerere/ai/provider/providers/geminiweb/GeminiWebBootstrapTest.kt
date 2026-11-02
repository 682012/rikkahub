package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.WebSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiWebBootstrapTest {

    /** Sanitized shape of `window.WIZ_global_data` inside the /app page. */
    private val page = """
        <!DOCTYPE html><html><head><script nonce="x">window.WIZ_global_data = {
        "SNlM0e": "AKp6bc_sanitized_csrf_token",
        "cfb2h": "boq_assistant-bard-web-server_20250101.00_p0",
        "FdrFJe": "-7654321098765432100",
        "hl": "en",
        "note": "braces } and \"quotes\" inside strings must not break parsing"};</script></head>
        <body>app shell</body></html>
    """.trimIndent()

    @Test
    fun `parses bootstrap tokens from the wiz global data blob`() {
        val bootstrap = GeminiWebBootstrap.parse(page)
        assertEquals("AKp6bc_sanitized_csrf_token", bootstrap.snlm0e)
        assertEquals("boq_assistant-bard-web-server_20250101.00_p0", bootstrap.buildLabel)
        assertEquals("-7654321098765432100", bootstrap.sessionId)
    }

    @Test
    fun `toString never exposes the csrf token`() {
        val rendered = GeminiWebBootstrap.parse(page).toString()
        assertTrue(rendered.contains("REDACTED"))
        assertTrue(!rendered.contains("AKp6bc_sanitized_csrf_token"))
    }

    @Test
    fun `login page without csrf token is a session error, not a protocol error`() {
        val loginPage = """
            <html><body><form action="https://accounts.google.com/ServiceLogin">
            <input name="Email"></form></body></html>
        """.trimIndent()
        try {
            GeminiWebBootstrap.parse(loginPage)
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
        }
    }

    @Test
    fun `missing build label is a session error because every rpc needs it`() {
        val noBuildLabel = """window.WIZ_global_data = {"SNlM0e": "token"};"""
        try {
            GeminiWebBootstrap.parse(noBuildLabel)
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
        }
    }

    @Test
    fun `falls back to scanning the page when the blob lacks the other fields`() {
        val split = """
            <script nonce="a">window.WIZ_global_data = {"SNlM0e":"tok_from_fallback"};</script>
            <script nonce="b">var x = {"cfb2h": "build-label-from-fallback", "FdrFJe": "-42"};</script>
        """.trimIndent()
        val bootstrap = GeminiWebBootstrap.parse(split)
        assertEquals("tok_from_fallback", bootstrap.snlm0e)
        assertEquals("build-label-from-fallback", bootstrap.buildLabel)
        assertEquals("-42", bootstrap.sessionId)
    }
}
