package me.rerere.ai.provider.providers.geminiweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionService
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiWebHttpTest {

    private val credential = WebSessionCredential(
        WebSessionService.GEMINI,
        mapOf("__Secure-1PSID" to "sanitized-sid", "SID" to "sanitized-sid-2"),
    )
    private val bootstrap = GeminiWebBootstrap(
        snlm0e = "csrf-token",
        buildLabel = "boq_assistant-bard-web-server_20250101.00_p0",
        sessionId = "-1234567890",
    )

    private fun bodyOf(request: okhttp3.Request): FormBody = request.body as FormBody

    private fun innerOf(request: okhttp3.Request): kotlinx.serialization.json.JsonArray {
        val body = bodyOf(request)
        val outer = Json.parseToJsonElement(body.value(0)).jsonArray
        assertEquals(2, outer.size)
        assertTrue(outer[0] is JsonNull)
        return Json.parseToJsonElement(outer[1].jsonPrimitive.content).jsonArray
    }

    @Test
    fun `stream request stays on the official origin with the real rpc shape`() {
        val request = GeminiWebHttp.streamGenerate(
            origin = GEMINI_WEB_ORIGIN,
            credential = credential,
            bootstrap = bootstrap,
            prompt = "你好 prompt",
            requestId = 123456,
        )
        val url = request.url
        assertEquals("https", url.scheme)
        assertEquals("gemini.google.com", url.host)
        assertEquals(
            "/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate",
            url.encodedPath,
        )
        assertEquals("POST", request.method)
        assertEquals("boq_assistant-bard-web-server_20250101.00_p0", url.queryParameter("bl"))
        assertEquals("-1234567890", url.queryParameter("f.sid"))
        assertEquals("123456", url.queryParameter("_reqid"))
        assertEquals("c", url.queryParameter("rt"))
        assertEquals("en", url.queryParameter("hl"))
        assertEquals("us", url.queryParameter("gl"))
    }

    @Test
    fun `stream request headers are honest and do not pretend to be chrome`() {
        val request = GeminiWebHttp.streamGenerate(
            GEMINI_WEB_ORIGIN, credential, bootstrap, "prompt", 100_001
        )
        val userAgent = request.header("User-Agent")!!
        assertEquals("RikkaHub-WebSession/experimental", userAgent)
        assertTrue(!userAgent.contains("Chrome"))
        assertEquals("1", request.header("X-Same-Domain"))
        assertEquals(GEMINI_WEB_ORIGIN, request.header("Origin"))
        assertEquals("$GEMINI_WEB_ORIGIN/app", request.header("Referer"))
        assertNull(request.header("x-goog-ext-525001261-jspb")) // no model selection header
        val cookie = request.header("Cookie")!!
        assertTrue(cookie.contains("__Secure-1PSID=sanitized-sid"))
        assertTrue(cookie.contains("SID=sanitized-sid-2"))
    }

    @Test
    fun `form body carries f req and the csrf token, starting a fresh conversation`() {
        val request = GeminiWebHttp.streamGenerate(
            GEMINI_WEB_ORIGIN, credential, bootstrap, "本地完整上下文", 100_002
        )
        val body = bodyOf(request)
        assertEquals(2, body.size)
        assertEquals("f.req", body.name(0))
        assertEquals("at", body.name(1))
        assertEquals("csrf-token", body.value(1))

        val inner = innerOf(request)
        assertEquals("本地完整上下文", inner[0].jsonArray[0].jsonPrimitive.content)
        // Fresh conversation: no remote chat id is referenced or continued.
        assertTrue(inner[2].jsonArray[0].toString() == "\"\"")
    }

    @Test
    fun `inner request has the verified 81 slot shape`() {
        val inner = innerOf(
            GeminiWebHttp.streamGenerate(GEMINI_WEB_ORIGIN, credential, bootstrap, "p", 100_003)
        )
        assertEquals(81, inner.size)
        // [0] = [prompt, 0, null, null, null, null, 0]
        val message = inner[0].jsonArray
        assertEquals(7, message.size)
        assertEquals("p", message[0].jsonPrimitive.content)
        assertEquals("0", message[1].jsonPrimitive.content)
        for (index in 2..5) assertTrue(message[index] is JsonNull)
        assertEquals("0", message[6].jsonPrimitive.content)
        // [1] = [language], [2] = DEFAULT_METADATA (["","","",null,null,null,null,null,null,""])
        assertEquals("en", inner[1].jsonArray[0].jsonPrimitive.content)
        val metadata = inner[2].jsonArray
        assertEquals(10, metadata.size)
        assertEquals("", metadata[0].jsonPrimitive.content)
        assertEquals("", metadata[1].jsonPrimitive.content)
        assertEquals("", metadata[2].jsonPrimitive.content)
        for (index in 3..8) assertTrue(metadata[index] is JsonNull)
        assertEquals("", metadata[9].jsonPrimitive.content)
        // Fixed scalars and small arrays from the reference client.
        assertEquals("1", inner[6].jsonArray[0].jsonPrimitive.content)
        assertEquals("1", inner[7].jsonPrimitive.content)
        assertEquals("1", inner[10].jsonPrimitive.content)
        assertEquals("0", inner[11].jsonPrimitive.content)
        assertEquals("0", inner[17].jsonArray[0].jsonArray[0].jsonPrimitive.content)
        assertEquals("0", inner[18].jsonPrimitive.content)
        assertEquals("1", inner[27].jsonPrimitive.content)
        assertEquals("4", inner[30].jsonArray[0].jsonPrimitive.content)
        assertEquals("1", inner[41].jsonArray[0].jsonPrimitive.content)
        assertEquals("0", inner[53].jsonPrimitive.content)
        assertTrue(inner[61].jsonArray.isEmpty())
        assertEquals("1", inner[68].jsonPrimitive.content)
        assertEquals("1", inner[79].jsonPrimitive.content)
        assertEquals("1", inner[80].jsonPrimitive.content)
        // Temporary chat flag must stay unset so history retention is not changed.
        assertTrue(inner[45] is JsonNull)
    }

    @Test
    fun `uuid slot 59 matches the jspb header and is uppercase`() {
        val request = GeminiWebHttp.streamGenerate(
            GEMINI_WEB_ORIGIN, credential, bootstrap, "p", 100_004
        )
        val uuid = innerOf(request)[59].jsonPrimitive.content
        assertEquals(uuid, uuid.uppercase())
        assertTrue(uuid.matches(Regex("[0-9A-F-]{36}")))
        val header = request.header(GEMINI_WEB_JSPB_HEADER)!!
        assertEquals("[\"$uuid\",1]", header)
    }

    @Test
    fun `bootstrap request reads the app page with the session cookie`() {
        val request = GeminiWebHttp.bootstrap(GEMINI_WEB_ORIGIN, credential)
        assertEquals("GET", request.method)
        assertEquals("$GEMINI_WEB_ORIGIN/app", request.url.toString())
        assertTrue(request.header("Cookie")!!.contains("__Secure-1PSID"))
    }

    @Test
    fun `credential is never attached to a foreign origin`() {
        assertNull(GeminiWebHttp.cookieHeader("http://127.0.0.1:8080", credential))
        assertNull(GeminiWebHttp.cookieHeader("https://claude.ai", credential))
        assertNotNull(GeminiWebHttp.cookieHeader(GEMINI_WEB_ORIGIN, credential))
    }

    @Test
    fun `freq payload escapes prompt content instead of interpolating it raw`() {
        val envelope = GeminiWebRequestBuilder.envelope("say \"hi\" \\ and\nnewline")
        val outer = Json.parseToJsonElement(envelope.freq).jsonArray
        val inner = Json.parseToJsonElement(outer[1].jsonPrimitive.content).jsonArray
        val prompt = inner[0].jsonArray[0].jsonPrimitive.content
        assertEquals("say \"hi\" \\ and\nnewline", prompt)
        // Round trip through JSON means the raw text cannot break the JSPB envelope.
        assertTrue(outer[1].jsonPrimitive.content.startsWith("["))
    }
}
