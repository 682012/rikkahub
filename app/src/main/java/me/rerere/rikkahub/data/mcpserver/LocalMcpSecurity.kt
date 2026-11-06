package me.rerere.rikkahub.data.mcpserver

import java.net.URI

/**
 * Upper bound for one MCP request body, in bytes, accepted by the local endpoint.
 *
 * The HTTP layer is expected to answer `413` before parsing anything larger. The value is a hard
 * limit, not a hint: a 256 KiB body is already far beyond any JSON-RPC message this server needs
 * (the biggest legitimate payload is a `tools/call` with a handful of string arguments).
 */
const val MAX_MCP_REQUEST_BYTES = 262_144

/** Upper bound for one serialized MCP response, in characters. Tool text is truncated well below this. */
const val MAX_MCP_RESPONSE_CHARS = 1_048_576

/**
 * Shortest bearer token this endpoint is willing to compare against.
 *
 * A token shorter than this is treated as a configuration mistake and fails closed (403) instead of
 * "sort of working": there is no fallback to an unauthenticated mode, ever.
 */
private const val MIN_TOKEN_CHARS = 8

/** Refuse absurd header values before they reach the comparison loop. */
private const val MAX_TOKEN_CHARS = 512

private const val LOOPBACK_V4 = "127.0.0.1"
private const val LOOPBACK_NAME = "localhost"

/**
 * Authorization decision for the local MCP HTTP endpoint.
 *
 * Returns `null` when the request may proceed, `401` when the bearer token is missing or wrong, and
 * `403` when the token setup itself is unsafe or the `Origin` / `Host` headers are not a plain
 * loopback browser-less or same-machine browser request.
 *
 * Deliberately pure: no coroutines, no Android, no I/O, so it can be unit tested and reused by the
 * Ktor route without knowing anything about the rest of the app.
 *
 * Rules, in evaluation order:
 * 1. `expectedToken` must be non-blank and free of whitespace/control characters, otherwise 403.
 *    There is no "empty token means open access" mode.
 * 2. `Authorization` must be exactly `Bearer <token>` (scheme case-insensitive, no extra fields) and
 *    the token must match `expectedToken` in constant time for equal-length candidates. Anything
 *    else is 401. Tokens passed as a query parameter or cookie are not supported at all.
 * 3. `Origin`, when present, must be exactly `http://localhost:<port>` or `http://127.0.0.1:<port>`
 *    with no userinfo, path, query or fragment. `Origin: null` (opaque browser origin) is rejected.
 *    An absent `Origin` is allowed: non-browser local clients (curl, scripts) do not send one.
 * 4. `Host` must be `127.0.0.1:<port>` or `localhost:<port>`. `0.0.0.0`, LAN addresses, IPv6
 *    bracket hosts and wrong ports are rejected, which blocks DNS-rebinding style requests that
 *    reach the loopback port through a hostile hostname.
 */
fun authorizeLocalMcp(
    authorization: String?,
    expectedToken: String,
    origin: String?,
    host: String?,
    port: Int,
): Int? {
    if (port !in 1..65535) return HTTP_FORBIDDEN
    if (!isSafeToken(expectedToken)) return HTTP_FORBIDDEN

    val presented = bearerToken(authorization) ?: return HTTP_UNAUTHORIZED
    if (!isSafeToken(presented) || !constantTimeEquals(presented, expectedToken)) {
        return HTTP_UNAUTHORIZED
    }

    if (origin != null && !isAllowedLocalOrigin(origin, port)) return HTTP_FORBIDDEN
    if (host == null || !isAllowedLocalHost(host, port)) return HTTP_FORBIDDEN
    return null
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/**
 * True when [origin] is a loopback browser origin for exactly this [port]: scheme `http`, host
 * `localhost` or `127.0.0.1`, the given port, and nothing else - no userinfo, no path (not even a
 * trailing `/`), no query, no fragment. Browsers send a bare origin, so anything with a path is
 * treated as a malformed or hostile header.
 */
fun isAllowedLocalOrigin(origin: String, port: Int): Boolean {
    if (port !in 1..65535) return false
    val raw = origin.trim()
    if (raw.isEmpty()) return false
    if (raw.equals("null", ignoreCase = true)) return false

    val uri = try {
        URI(raw)
    } catch (e: Exception) {
        // URISyntaxException (and anything else a malformed header throws) is a rejection, not a crash.
        return false
    }

    if (!uri.isAbsolute) return false
    val scheme = uri.scheme ?: return false
    if (!scheme.equals("http", ignoreCase = true)) return false
    if (uri.rawUserInfo != null) return false
    if (uri.rawQuery != null) return false
    if (uri.rawFragment != null) return false
    val path = uri.rawPath ?: ""
    if (path.isNotEmpty()) return false
    if (uri.port != port) return false
    val host = uri.host ?: return false
    return host.equals(LOOPBACK_V4, ignoreCase = true) || host.equals(LOOPBACK_NAME, ignoreCase = true)
}

/**
 * True when the `Host` header is exactly `127.0.0.1:<port>` or `localhost:<port>`.
 *
 * The port must be present and equal to [port]; `0.0.0.0`, wildcard and LAN hosts, bracketed IPv6
 * literals (`[::1]`, `[::ffff:127.0.0.1]`) and trailing-dot hostnames are rejected.
 */
fun isAllowedLocalHost(host: String, port: Int): Boolean {
    if (port !in 1..65535) return false
    val raw = host.trim()
    if (raw.isEmpty()) return false
    if (raw.contains('@') || raw.contains('/') || raw.contains('?') || raw.contains('#')) return false

    val separator = raw.lastIndexOf(':')
    if (separator <= 0 || separator == raw.length - 1) return false

    val name = raw.substring(0, separator)
    val portText = raw.substring(separator + 1)
    if (name.any { it == ':' }) return false // bracketed IPv6, or a second colon
    if (name.isEmpty() || name.endsWith(".")) return false
    if (portText != port.toString()) return false
    return name.equals(LOOPBACK_V4, ignoreCase = true) || name.equals(LOOPBACK_NAME, ignoreCase = true)
}

/** Constant-time-ish equality for secret comparison: never returns early on the first differing byte. */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    var diff = left.size xor right.size
    val length = maxOf(left.size, right.size)
    for (index in 0 until length) {
        val l = if (index < left.size) left[index].toInt() else 0
        val r = if (index < right.size) right[index].toInt() else 0
        diff = diff or (l xor r)
    }
    return diff == 0
}

/** A token must be non-blank printable ASCII with no whitespace, so it cannot smuggle a second header field. */
internal fun isSafeToken(token: String): Boolean {
    if (token.length < MIN_TOKEN_CHARS || token.length > MAX_TOKEN_CHARS) return false
    return token.all { it.code in 0x21..0x7E }
}

private fun bearerToken(header: String?): String? {
    val raw = header?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val space = raw.indexOf(' ')
    if (space <= 0) return null
    if (!raw.substring(0, space).equals("Bearer", ignoreCase = true)) return null
    val value = raw.substring(space + 1).trim()
    if (value.isEmpty()) return null
    if (value.any { it == ' ' || it == '\t' }) return null // "Bearer a b" is not one token
    return value
}
