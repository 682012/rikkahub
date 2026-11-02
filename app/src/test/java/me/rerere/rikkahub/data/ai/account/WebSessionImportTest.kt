package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.WebSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionImportTest {

    private val now = 1_700_000_000_000L

    private fun parse(
        service: WebSessionService,
        raw: String,
        nowMillis: Long = now,
    ): ImportedWebSession = WebSessionImport.parse(service, raw, nowMillis)

    private fun reject(
        service: WebSessionService,
        raw: String,
        nowMillis: Long = now,
    ): WebSessionImportException = try {
        WebSessionImport.parse(service, raw, nowMillis)
        throw AssertionError("expected the cookie input to be rejected")
    } catch (e: WebSessionImportException) {
        e
    }

    @Test
    fun `gemini cookie header with prefix is accepted`() {
        val result = parse(
            WebSessionService.GEMINI,
            "Cookie: __Secure-1PSID=psid-secret-1; __Secure-1PSIDTS=psidts-secret-2",
        )

        assertEquals(
            mapOf("__Secure-1PSID" to "psid-secret-1", "__Secure-1PSIDTS" to "psidts-secret-2"),
            result.cookies,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `cookie prefix is optional and case insensitive`() {
        assertEquals(
            mapOf("__Secure-1PSID" to "value-a"),
            parse(WebSessionService.GEMINI, "cOoKiE: __Secure-1PSID=value-a").cookies,
        )
        assertEquals(
            mapOf("__Secure-1PSID" to "value-a"),
            parse(WebSessionService.GEMINI, "__Secure-1PSID=value-a").cookies,
        )
    }

    @Test
    fun `claude session key alone is accepted`() {
        assertEquals(
            mapOf("sessionKey" to "sk-ant-sid01-value"),
            parse(WebSessionService.CLAUDE, "sessionKey=sk-ant-sid01-value").cookies,
        )
    }

    @Test
    fun `gemini without psid is rejected`() {
        reject(WebSessionService.GEMINI, "__Secure-1PSIDTS=psidts-secret-2")
    }

    @Test
    fun `gemini without psidts warns but imports`() {
        val result = parse(WebSessionService.GEMINI, "__Secure-1PSID=psid-secret-1")
        assertEquals(setOf("__Secure-1PSID"), result.cookies.keys)
        assertTrue(result.warnings.any { it.contains("__Secure-1PSIDTS") })
    }

    @Test
    fun `cloudflare clearance cookie is not imported`() {
        assertEquals(setOf("__Secure-1PSID"), parse(WebSessionService.GEMINI, "__Secure-1PSID=psid-secret-1; cf_clearance=clearance-secret").cookies.keys)
    }

    @Test
    fun `other google account cookies are not imported`() {
        assertEquals(setOf("__Secure-1PSID"), parse(WebSessionService.GEMINI, "__Secure-1PSID=psid-secret-1; SID=account-secret").cookies.keys)
        assertEquals(setOf("__Secure-1PSID"), parse(WebSessionService.GEMINI, "__Secure-1PSID=psid-secret-1; HSID=account-secret").cookies.keys)
    }

    @Test
    fun `claude rejects non session key cookies`() {
        val result = parse(WebSessionService.CLAUDE, "sessionKey=good-value; hsid=other-secret")
        assertEquals(mapOf("sessionKey" to "good-value"), result.cookies)
        assertFalse(result.toString().contains("good-value"))
    }

    @Test
    fun `oauth and bearer credentials are rejected`() {
        reject(WebSessionService.CLAUDE, "Authorization: Bearer sk-ant-oat01-secret")
        reject(WebSessionService.CLAUDE, "oauth_token=sk-ant-oat01-secret")
        reject(WebSessionService.CLAUDE, "__Secure-next-auth.session-token=nextauth-secret")
    }

    @Test
    fun `gemini json export is accepted`() {
        val json = """
            [{"name":"__Secure-1PSID","value":"psid-secret-1","domain":".google.com",
              "expires":${(now + 86_400_000) / 1000.0}},
             {"name":"__Secure-1PSIDTS","value":"psidts-secret-2","domain":".google.com",
              "expires":${(now + 86_400_000) / 1000.0}}]
        """.trimIndent()

        val result = parse(WebSessionService.GEMINI, json)
        assertEquals(
            mapOf("__Secure-1PSID" to "psid-secret-1", "__Secure-1PSIDTS" to "psidts-secret-2"),
            result.cookies,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `claude json export with expiration date is accepted`() {
        val json = """
            [{"name":"sessionKey","value":"sk-ant-sid01-value","domain":"claude.ai",
              "expirationDate":${(now + 3_600_000) / 1000.0}}]
        """.trimIndent()

        assertEquals(mapOf("sessionKey" to "sk-ant-sid01-value"), parse(WebSessionService.CLAUDE, json).cookies)
    }

    @Test
    fun `json cookie without expiry produces a warning`() {
        val result = parse(
            WebSessionService.CLAUDE,
            """[{"name":"sessionKey","value":"sk-ant-sid01-value","domain":"claude.ai"}]""",
        )
        assertTrue(result.warnings.any { it.contains("sessionKey") && it.contains("expiry") })
    }

    @Test
    fun `json from another site is rejected`() {
        val failure = reject(
            WebSessionService.GEMINI,
            """[{"name":"__Secure-1PSID","value":"psid-secret-1","domain":".youtube.com"}]""",
        )
        assertFalse(failure.message.orEmpty().contains("psid-secret-1"))
        assertFalse(failure.message.orEmpty().contains("youtube"))
    }

    @Test
    fun `domain suffix spoofing is rejected`() {
        reject(
            WebSessionService.CLAUDE,
            """[{"name":"sessionKey","value":"sk-ant-sid01-value","domain":"evil-claude.ai"} ]""",
        )
        reject(
            WebSessionService.CLAUDE,
            """[{"name":"sessionKey","value":"sk-ant-sid01-value","domain":"claude.ai.evil.example"} ]""",
        )
    }

    @Test
    fun `expired json cookie is rejected`() {
        val json = """[{"name":"sessionKey","value":"sk-ant-sid01-value","domain":"claude.ai","expires":${
            (now - 60_000) / 1000.0
        }}]"""
        reject(WebSessionService.CLAUDE, json)
    }

    @Test
    fun `duplicate cookie names are rejected`() {
        reject(WebSessionService.GEMINI, "__Secure-1PSID=value-a; __Secure-1PSID=value-b")
        reject(
            WebSessionService.CLAUDE,
            """[{"name":"sessionKey","value":"a","domain":"claude.ai"},
               {"name":"sessionKey","value":"b","domain":"claude.ai"}]""",
        )
    }

    @Test
    fun `crlf header injection is rejected`() {
        val failure = reject(
            WebSessionService.GEMINI,
            "__Secure-1PSID=psid-secret-1\r\nX-Injected: 1",
        )
        assertFalse(failure.message.orEmpty().contains("psid-secret-1"))
        assertFalse(failure.message.orEmpty().contains("X-Injected"))
    }

    @Test
    fun `control characters in value are rejected`() {
        reject(WebSessionService.CLAUDE, "sessionKey=value\u0001secret")
        reject(WebSessionService.CLAUDE, "sessionKey=value with space")
    }

    @Test
    fun `empty value is rejected`() {
        reject(WebSessionService.CLAUDE, "sessionKey=")
    }

    @Test
    fun `blank input is rejected`() {
        reject(WebSessionService.GEMINI, "   ")
        reject(WebSessionService.GEMINI, "Cookie:")
        reject(WebSessionService.GEMINI, ";;;")
    }

    @Test
    fun `oversized input is rejected`() {
        val failure = reject(WebSessionService.GEMINI, "__Secure-1PSID=" + "a".repeat(70_000))
        assertFalse(failure.message.orEmpty().contains("aaaaaaaa"))
    }

    @Test
    fun `more than 32 cookies are rejected`() {
        val many = (1..40).joinToString("; ") { "cookie$it=value$it" }
        val failure = reject(WebSessionService.GEMINI, many)
        assertTrue(failure.message.orEmpty().contains("Too many cookies"))
        assertFalse(failure.message.orEmpty().contains("value40"))
    }

    @Test
    fun `malformed json is rejected`() {
        reject(WebSessionService.GEMINI, """[{"name":"__Secure-1PSID","value":""")
        reject(WebSessionService.GEMINI, """{"name":"__Secure-1PSID","value":"a"}""")
        reject(WebSessionService.GEMINI, "[]")
    }

    @Test
    fun `values with equals signs are allowed for base64 padding`() {
        val result = parse(WebSessionService.CLAUDE, "sessionKey=sk-ant-sid01-value==")
        assertEquals("sk-ant-sid01-value==", result.cookies["sessionKey"])
    }

    @Test
    fun `error messages never echo pasted secrets`() {
        val secret = "super-secret-cookie-value"
        val failures = listOf(
            reject(WebSessionService.GEMINI, "__Secure-1PSIDTS=$secret"),
            reject(WebSessionService.CLAUDE, "sessionKey=$secret\u0001"),
            reject(WebSessionService.CLAUDE, "sessionKey=$secret; sessionKey=conflicting"),
            reject(
                WebSessionService.CLAUDE,
                """[{"name":"sessionKey","value":"$secret","domain":"example.com"}]""",
            ),
        )
        failures.forEach { failure ->
            assertFalse(failure.message.orEmpty().contains(secret))
            assertFalse(failure.toString().contains(secret))
        }
    }

    @Test
    fun `invalid cookie name is rejected without echoing it`() {
        val failure = reject(WebSessionService.CLAUDE, "bad-name=secret-value")
        assertFalse(failure.message.orEmpty().contains("secret-value"))
    }
}
