package me.rerere.rikkahub.data.mcpserver

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑用例：token 生成、端口/设置校验、授权分类过滤、客户端配置 JSON。
 * 不碰 Android Context，也不起服务器。
 */
class LocalMcpSettingsValidationTest {

    @Test
    fun `token is 256bit base64url with prefix and unique`() {
        val a = newLocalMcpToken()
        val b = newLocalMcpToken()
        assertTrue(a.startsWith("lmcp_"))
        assertEquals(5 + 43, a.length) // 32 bytes -> 43 base64url chars, no padding
        assertNotEquals(a, b)
        assertTrue(a.removePrefix("lmcp_").all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(isLocalMcpTokenAcceptable(a))
    }

    @Test
    fun `port validation rejects privileged and out of range`() {
        assertEquals(LocalMcpSettingsProblem.PORT_PRIVILEGED, validateLocalMcpPort(80))
        assertEquals(LocalMcpSettingsProblem.PORT_OUT_OF_RANGE, validateLocalMcpPort(0))
        assertEquals(LocalMcpSettingsProblem.PORT_OUT_OF_RANGE, validateLocalMcpPort(70000))
        assertNull(validateLocalMcpPort(LOCAL_MCP_DEFAULT_PORT))
        assertNull(validateLocalMcpPort(LOCAL_MCP_MIN_PORT))
        assertNull(validateLocalMcpPort(LOCAL_MCP_MAX_PORT))
    }

    @Test
    fun `defaults are safe and assistant is optional only when not required`() {
        val settings = LocalMcpSettings()
        assertFalse(settings.enabled)
        assertEquals(8766, settings.port)
        assertTrue(settings.allowRead)
        assertFalse(settings.allowMutations)
        assertFalse(settings.allowModelRequests)
        assertFalse(settings.allowDevice)
        assertFalse(settings.allowAutomation)
        assertNull(settings.assistantId)
        assertFalse(settings.hasAssistant)

        assertTrue(validateLocalMcpSettings(settings).isEmpty())
        assertEquals(
            listOf(LocalMcpSettingsProblem.ASSISTANT_NOT_SELECTED),
            validateLocalMcpSettings(settings, requireAssistant = true),
        )
    }

    @Test
    fun `sanitize fixes bad port short token and blank assistant`() {
        val broken = LocalMcpSettings(port = 80, token = "short", assistantId = "   ")
        val fixed = sanitizeLocalMcpSettings(broken, SecureRandom())
        assertEquals(LOCAL_MCP_DEFAULT_PORT, fixed.port)
        assertTrue(isLocalMcpTokenAcceptable(fixed.token))
        assertNull(fixed.assistantId)
    }

    @Test
    fun `valid token survives sanitize`() {
        val settings = LocalMcpSettings(token = newLocalMcpToken(), assistantId = "uuid-1")
        assertEquals(settings.token, sanitizeLocalMcpSettings(settings).token)
        assertEquals("uuid-1", sanitizeLocalMcpSettings(settings).assistantId)
    }

    @Test
    fun `category filter follows user grants`() {
        val read = LocalMcpTool(name = "conversation_list", category = LocalMcpToolCategory.READ)
        val write = LocalMcpTool(
            name = "conversation_send",
            readOnly = false,
            category = LocalMcpToolCategory.MUTATION,
        )
        val model = LocalMcpTool(name = "chat_generate", category = LocalMcpToolCategory.MODEL)
        val device = LocalMcpTool(name = "device_tap", category = LocalMcpToolCategory.DEVICE)
        val automation = LocalMcpTool(name = "automation_run", category = LocalMcpToolCategory.AUTOMATION)
        val all = listOf(read, write, model, device, automation)

        val defaults = LocalMcpSettings()
        assertEquals(listOf(read), defaults.visibleTools(all))
        assertTrue(read.readOnly)
        assertFalse(write.readOnly)

        val trusted = defaults.copy(
            allowMutations = true,
            allowModelRequests = true,
            allowDevice = true,
            allowAutomation = true,
        )
        assertEquals(all, trusted.visibleTools(all))
    }

    @Test
    fun `client config json carries endpoint and bearer token`() {
        val settings = LocalMcpSettings(port = 9100, token = "lmcp_testtoken")
        val config = Json.parseToJsonElement(localMcpClientConfigJson(settings)) as JsonObject
        val server = config.jsonObject["mcpServers"]!!.jsonObject["local-app"]!!.jsonObject
        assertEquals("http://127.0.0.1:9100/mcp", server["url"]!!.jsonPrimitive.content)
        assertEquals(
            "Bearer lmcp_testtoken",
            server["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `masked token and toString do not leak the secret`() {
        val token = newLocalMcpToken()
        val settings = LocalMcpSettings(token = token)
        assertFalse(settings.toString().contains(token))
        assertFalse(maskToken(token).contains(token))
        assertEquals("«empty»", maskToken(""))
    }

    @Test
    fun `accept header check only allows json wildcards`() {
        assertTrue(acceptAcceptsJson("application/json"))
        assertTrue(acceptAcceptsJson("application/json, text/event-stream"))
        assertTrue(acceptAcceptsJson("*/*"))
        assertTrue(acceptAcceptsJson("application/json; charset=utf-8"))
        assertFalse(acceptAcceptsJson("text/event-stream"))
        assertFalse(acceptAcceptsJson("text/html"))
    }
}
