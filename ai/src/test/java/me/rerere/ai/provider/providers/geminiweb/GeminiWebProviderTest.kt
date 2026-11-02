package me.rerere.ai.provider.providers.geminiweb

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionCredentialProvider
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiWebProviderTest {

    private val credential = WebSessionCredential(WebSessionService.GEMINI, mapOf("SID" to "sanitized"))
    private val bootstrap = GeminiWebBootstrap("csrf", "build-label", "-1")

    private class FakeCredentials : WebSessionCredentialProvider {
        var calls = 0
        var failWith: Throwable? = null
        override suspend fun getSession(setting: ProviderSetting.WebAccount): WebSessionCredential {
            calls++
            failWith?.let { throw it }
            return WebSessionCredential(WebSessionService.GEMINI, mapOf("SID" to "sanitized"))
        }
    }

    private class FakeTransport(
        private val response: String = "answer",
        private val failWith: Throwable? = null,
    ) : GeminiWebTransport {
        var bootstrapCalls = 0
        var streamCalls = 0
        override suspend fun bootstrap(credential: WebSessionCredential): GeminiWebBootstrap {
            bootstrapCalls++
            failWith?.let { throw it }
            return GeminiWebBootstrap("csrf", "bl", "-1")
        }

        override suspend fun streamGenerate(
            credential: WebSessionCredential,
            bootstrap: GeminiWebBootstrap,
            prompt: String,
            requestId: Int,
            onFrame: suspend (GeminiWebFrame) -> Unit,
        ) {
            streamCalls++
            onFrame(GeminiWebFrameParser.parse(GeminiWebFixtures.frame(response)))
        }
    }

    private fun setting() = ProviderSetting.GeminiWeb()

    @Test
    fun `listModels performs a real bootstrap login check`() = runTest {
        val credentials = FakeCredentials()
        val transport = FakeTransport()
        val models = GeminiWebProvider(credentials, transport).listModels(setting())
        assertEquals(1, credentials.calls)
        assertEquals(1, transport.bootstrapCalls)
        assertEquals(1, models.size)
        assertEquals(GEMINI_WEB_DEFAULT_MODEL_ID, models[0].modelId)
        assertEquals(GEMINI_WEB_DEFAULT_CONTEXT_LENGTH, models[0].contextLength)
    }

    @Test
    fun `listModels fails instead of showing a static signed-in list`() = runTest {
        val credentials = FakeCredentials()
        val transport = FakeTransport(failWith = WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE))
        try {
            GeminiWebProvider(credentials, transport).listModels(setting())
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
        }
    }

    @Test
    fun `generateText rejects an arbitrary model id because the web call selects no model`() = runTest {
        val transport = FakeTransport()
        val provider = GeminiWebProvider(FakeCredentials(), transport)
        try {
            provider.generateText(
                providerSetting = setting(),
                messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))),
                params = TextGenerationParams(model = me.rerere.ai.provider.Model(modelId = "gemini-3-pro")),
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(GEMINI_WEB_DEFAULT_MODEL_ID))
        }
        assertEquals(0, transport.bootstrapCalls)
        assertEquals(0, transport.streamCalls)
    }

    @Test
    fun `generateText with web-default uses the real session and terminal answer`() = runTest {
        val transport = FakeTransport(response = "ok")
        val provider = GeminiWebProvider(FakeCredentials(), transport)
        provider.generateText(
            providerSetting = setting(),
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))),
            params = TextGenerationParams(model = me.rerere.ai.provider.Model(modelId = GEMINI_WEB_DEFAULT_MODEL_ID)),
        )
        assertEquals(1, transport.bootstrapCalls)
        assertEquals(1, transport.streamCalls)
    }
}
