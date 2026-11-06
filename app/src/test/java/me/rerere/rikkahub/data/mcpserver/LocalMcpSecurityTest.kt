package me.rerere.rikkahub.data.mcpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpSecurityTest {

    private val port = 8787
    private val token = "s3cret-token-0123456789abcdef"
    private val bearer = "Bearer $token"

    private fun decide(
        authorization: String? = bearer,
        expectedToken: String = token,
        origin: String? = null,
        host: String? = "127.0.0.1:8787",
        port: Int = this.port,
    ) = authorizeLocalMcp(authorization, expectedToken, origin, host, port)

    @Test
    fun `valid bearer token with loopback host passes`() {
        assertNull(decide())
        assertNull(decide(host = "localhost:8787"))
        assertNull(decide(origin = "http://127.0.0.1:8787"))
        assertNull(decide(origin = "http://localhost:8787", host = "localhost:8787"))
    }

    @Test
    fun `missing or malformed authorization is 401`() {
        assertEquals(401, decide(authorization = null))
        assertEquals(401, decide(authorization = ""))
        assertEquals(401, decide(authorization = "   "))
        assertEquals(401, decide(authorization = token)) // no scheme
        assertEquals(401, decide(authorization = "Basic $token"))
        assertEquals(401, decide(authorization = "Bearer"))
        assertEquals(401, decide(authorization = "Bearer "))
        assertEquals(401, decide(authorization = "Bearer ${token}x")) // suffix
        assertEquals(401, decide(authorization = "Bearer ${token.dropLast(1)}")) // prefix of same length
        assertEquals(401, decide(authorization = "Bearer ${token.substring(0, 8)}"))
    }

    @Test
    fun `bearer scheme is case insensitive but the token is not`() {
        assertNull(decide(authorization = "bearer $token"))
        assertNull(decide(authorization = "BEARER $token"))
        assertEquals(401, decide(authorization = "Bearer ${token.uppercase()}"))
    }

    @Test
    fun `query style token is not accepted`() {
        // The endpoint only reads the Authorization header; a token smuggled in the URL never matches.
        assertEquals(401, decide(authorization = "?token=$token"))
        assertEquals(401, decide(authorization = "&token=$token"))
        assertEquals(401, decide(authorization = "token=$token"))
        assertEquals(401, decide(authorization = "Bearer ?token=$token"))
    }

    @Test
    fun `unsafe or weak expected token fails closed with 403`() {
        assertEquals(403, decide(expectedToken = ""))
        assertEquals(403, decide(expectedToken = "       "))
        assertEquals(403, decide(expectedToken = "short"))
        assertEquals(403, decide(expectedToken = "has space in it 1234"))
        assertEquals(403, decide(expectedToken = "tab\tinside 12345"))
        assertEquals(403, decide(expectedToken = "x".repeat(513)))
        // No downgrade: an unsafe configured token never falls back to "open access".
        assertEquals(403, decide(authorization = null, expectedToken = ""))
    }

    @Test
    fun `bad token outranks bad origin`() {
        // Token is checked first, so a hostile origin cannot turn a 401 into a 403-ish information leak.
        assertEquals(401, decide(authorization = "Bearer wrong-token-000", origin = "http://evil.example"))
    }

    @Test
    fun `evil origins are rejected`() {
        val bad = listOf(
            "http://evil.example",
            "http://evil.example:8787",
            "https://127.0.0.1:8787",
            "http://127.0.0.1:8788",
            "http://127.0.0.1",
            "http://localhost",
            "http://localhost:87870",
            "http://user:pw@127.0.0.1:8787",
            "http://user@127.0.0.1:8787",
            "http://127.0.0.1:8787/path",
            "http://127.0.0.1:8787/admin",
            "http://127.0.0.1:8787?x=1",
            "http://127.0.0.1:8787#frag",
            "null",
            "NULL",
            "",
            "   ",
            "ftp://127.0.0.1:8787",
            "ws://127.0.0.1:8787",
            "http://localhost:8787.evil.example",
            "http://127.0.0.1.evil.example:8787",
            "http://0.0.0.0:8787",
            "http://192.168.1.20:8787",
            "http://[::1]:8787",
            "http://localhost:8787/",
            "file:///etc/passwd",
            "not a url",
        )
        for (origin in bad) {
            assertEquals("origin should be rejected: $origin", 403, decide(origin = origin))
        }
    }

    @Test
    fun `absent origin is allowed for local non browser clients`() {
        assertNull(decide(origin = null))
        // Surrounding optional whitespace is tolerated, the value itself is still validated strictly.
        assertNull(decide(origin = "  http://127.0.0.1:8787  "))
        assertNull(decide(origin = "http://LOCALHOST:8787"))
    }

    @Test
    fun `host header must be an exact loopback host with the right port`() {
        val bad = listOf(
            "0.0.0.0:8787",
            "0.0.0.0",
            "[::]:8787",
            "[::1]:8787",
            "[::ffff:127.0.0.1]:8787",
            "192.168.1.20:8787",
            "10.0.0.5:8787",
            "127.0.0.1:8788",
            "127.0.0.1",
            "localhost",
            "localhost:",
            "localhost:08787",
            "localhost:8787.",
            "evil.example:8787",
            "127.0.0.1:8787.evil.example",
            "user@127.0.0.1:8787",
            "127.0.0.1:8787/",
            "127.0.0.1:8787?x=1",
            "",
        )
        for (host in bad) {
            assertEquals("host should be rejected: $host", 403, decide(host = host))
        }
        assertNull(decide(host = "127.0.0.1:8787"))
        assertNull(decide(host = "LOCALHOST:8787"))
    }

    @Test
    fun `missing host is rejected`() {
        assertEquals(403, decide(host = null))
    }

    @Test
    fun `dns rebinding style request is rejected`() {
        // Host header still says loopback (that is what the browser connects to) but Origin is the
        // attacker page that resolved its own name to 127.0.0.1.
        assertEquals(403, decide(origin = "http://attacker.example:8787", host = "127.0.0.1:8787"))
        assertEquals(403, decide(origin = "http://rebind.attacker.example", host = "localhost:8787"))
        // And the reverse trick: loopback Origin but a wildcard Host.
        assertEquals(403, decide(origin = "http://127.0.0.1:8787", host = "0.0.0.0:8787"))
    }

    @Test
    fun `non standard port arguments are rejected`() {
        assertEquals(403, decide(port = 0))
        assertEquals(403, decide(port = -1))
        assertEquals(403, decide(port = 70000))
    }

    @Test
    fun `constant time comparison only matches identical tokens`() {
        assertTrue(constantTimeEquals(token, token))
        assertFalse(constantTimeEquals(token, token + "x"))
        assertFalse(constantTimeEquals(token, ""))
        assertFalse(constantTimeEquals("", token))
        assertTrue(constantTimeEquals("", ""))
        // Different bytes at the same position, same length.
        val flipped = token.dropLast(1) + if (token.last() == 'a') 'b' else 'a'
        assertFalse(constantTimeEquals(token, flipped))
    }

    @Test
    fun `limits are documented and sane`() {
        assertEquals(262_144, MAX_MCP_REQUEST_BYTES)
        assertTrue(MAX_MCP_RESPONSE_CHARS > MAX_MCP_REQUEST_BYTES / 2)
        assertTrue(MAX_MCP_RESPONSE_CHARS > 0)
    }

    @Test
    fun `origin helpers do not depend on authorize`() {
        assertTrue(isAllowedLocalOrigin("http://127.0.0.1:8787", 8787))
        assertFalse(isAllowedLocalOrigin("http://localhost:8787/", 8787))
        assertFalse(isAllowedLocalOrigin("http://localhost:8787", 8788))
        assertTrue(isAllowedLocalHost("localhost:8787", 8787))
        assertFalse(isAllowedLocalHost("localhost:8787", 8788))
    }
}
