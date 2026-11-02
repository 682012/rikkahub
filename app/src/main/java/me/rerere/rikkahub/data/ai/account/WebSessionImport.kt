package me.rerere.rikkahub.data.ai.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.rerere.ai.provider.WebSessionService

/**
 * 用户从浏览器复制来的本地 web 会话 cookie。
 *
 * 这是一个纯解析结果: 只包含官方域名、官方 cookie 名, 不含其它 Google / Cloudflare cookie。
 * cookie 值只能进入加密存储, 不得写进设置、日志、分享或备份。
 */
data class ImportedWebSession(
    val cookies: Map<String, String>,
    val warnings: List<String> = emptyList(),
) { override fun toString() = "ImportedWebSession(cookies=[REDACTED])" }

/** 输入被拒绝。消息只描述规则, 绝不回显用户粘贴的内容。 */
class WebSessionImportException(message: String) : IllegalArgumentException(message)

/**
 * 解析 "Cookie:" 请求头格式或浏览器 JSON 导出 (`[{name,value,domain,expires}]`)。
 *
 * 纯函数: 不读剪贴板、不联网、不落盘。调用方负责把结果交给 [WebSessionVault] 加密保存。
 * 只接受站点自己的会话 cookie:
 * - [WebSessionService.GEMINI]: `__Secure-1PSID` (必需) 与 `__Secure-1PSIDTS`;
 * - [WebSessionService.CLAUDE]: `sessionKey` (必需)。
 *
 * OAuth / bearer token 不是 web 会话, 不在这里转换或猜测。
 */
object WebSessionImport {
    /** 单次粘贴的原始文本上限 */
    const val MAX_RAW_BYTES: Int = 64 * 1024

    /** 单次导入的 cookie 条数上限 */
    const val MAX_COOKIES: Int = 32

    private val GEMINI_COOKIE_NAMES = listOf("__Secure-1PSID", "__Secure-1PSIDTS")
    private val CLAUDE_COOKIE_NAMES = listOf("sessionKey")

    private val GEMINI_DOMAINS = setOf("gemini.google.com", "google.com")
    private val CLAUDE_DOMAINS = setOf("claude.ai")

    /** RFC 6265 token 的保守子集, 足够覆盖本站 cookie 名 */
    private val COOKIE_NAME = Regex("[A-Za-z0-9_\\-]{1,64}")

    fun parse(
        service: WebSessionService,
        raw: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImportedWebSession {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw WebSessionImportException(
            "Nothing to import. Paste the Cookie header or the JSON cookie export from your browser."
        )
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES) throw WebSessionImportException(
            "Cookie input is too large. Paste only the two session cookies of ${service.host}."
        )
        return if (trimmed.startsWith("[")) {
            parseJsonExport(service, trimmed, nowMillis)
        } else {
            parseCookieHeader(service, trimmed)
        }
    }

    private fun parseCookieHeader(service: WebSessionService, input: String): ImportedWebSession {
        if (input.any { it == '\r' || it == '\n' }) throw WebSessionImportException(
            "Cookie input must stay on one line; line breaks and extra headers are rejected."
        )
        var text = input.trim()
        if (text.regionMatches(0, "cookie:", 0, 7, ignoreCase = true)) {
            text = text.substring(7).trim()
        }
        if (text.isEmpty()) throw WebSessionImportException(
            "The Cookie header is empty. Copy it again from the browser."
        )
        val pairs = ArrayList<Pair<String, String>>()
        for (segment in text.split(';')) {
            val item = segment.trim()
            if (item.isEmpty()) continue
            val separator = item.indexOf('=')
            if (separator <= 0) throw WebSessionImportException(
                "Every cookie must look like name=value."
            )
            pairs += item.substring(0, separator).trim() to item.substring(separator + 1).trim()
        }
        if (pairs.isEmpty()) throw WebSessionImportException(
            "No cookie was found in the pasted text."
        )
        return finish(service, pairs, emptyList())
    }

    private fun parseJsonExport(
        service: WebSessionService,
        input: String,
        nowMillis: Long,
    ): ImportedWebSession {
        val element = try {
            Json.parseToJsonElement(input)
        } catch (e: Exception) {
            throw WebSessionImportException(
                "The pasted JSON is not a cookie export array."
            )
        }
        val array = element as? JsonArray
            ?: throw WebSessionImportException("The cookie export must be a JSON array.")
        if (array.isEmpty()) throw WebSessionImportException("The cookie export is empty.")
        if (array.size > MAX_COOKIES) throw WebSessionImportException(
            "Too many cookies in the export. Import at most $MAX_COOKIES cookies of ${service.host}."
        )

        val warnings = ArrayList<String>()
        val pairs = ArrayList<Pair<String, String>>()
        for (entry in array) {
            val cookie = entry as? JsonObject
                ?: throw WebSessionImportException("Every entry of the cookie export must be an object.")
            val name = cookie.stringField("name")
                ?: throw WebSessionImportException("Every cookie in the export needs a name.")
            if (name !in allowedCookieNames(service)) continue
            val value = cookie.stringField("value")
                ?: throw WebSessionImportException("Cookie $name has no value in the export.")
            val domain = cookie.stringField("domain")
                ?: throw WebSessionImportException("Cookie $name has no domain in the export.")
            if (!domainAllowed(service, domain)) throw WebSessionImportException(
                "Only cookies for ${allowedDomains(service).joinToString(", ")} can be imported; " +
                    "cookies of other sites stay in your browser."
            )
            val expires = cookie.numberField("expires") ?: cookie.numberField("expirationDate")
            if (expires != null && expires > 0) {
                val expiresAtMillis = (expires * 1000.0).toLong()
                if (expiresAtMillis <= nowMillis) throw WebSessionImportException(
                    "Cookie $name has already expired. Export a fresh session from the browser."
                )
            } else {
                warnings += "Cookie $name has no expiry; it will also end when you sign out of the browser."
            }
            pairs += name to value
        }
        return finish(service, pairs, warnings)
    }

    private fun finish(
        service: WebSessionService,
        pairs: List<Pair<String, String>>,
        warnings: List<String>,
    ): ImportedWebSession {
        if (pairs.size > MAX_COOKIES) throw WebSessionImportException(
            "Too many cookies. Import at most $MAX_COOKIES cookies of ${service.host}."
        )
        val allowed = allowedCookieNames(service)
        val cookies = LinkedHashMap<String, String>()
        for ((name, value) in pairs) {
            if (!COOKIE_NAME.matches(name)) throw WebSessionImportException(
                "Cookie names are limited to the session cookies of ${service.host}."
            )
            if (name !in allowed) continue
            if (value.isEmpty()) throw WebSessionImportException(
                "Cookie $name is empty. Export it again from the browser."
            )
            if (!isHeaderToken(value)) throw WebSessionImportException(
                "Cookie $name contains characters that are not allowed in a header value."
            )
            if (cookies[name] == value) continue
            if (cookies.containsKey(name)) throw WebSessionImportException(
                "Cookie $name appears twice. Remove the duplicate and import again."
            )
            cookies[name] = value
        }
        val required = requiredCookieName(service)
        if (!cookies.containsKey(required)) throw WebSessionImportException(
            "${service.host} needs $required to sign in."
        )
        val extraWarnings = warnings.toMutableList()
        if (service == WebSessionService.GEMINI && !cookies.containsKey("__Secure-1PSIDTS")) {
            extraWarnings += "__Secure-1PSIDTS is missing; the session may end sooner."
        }
        return ImportedWebSession(cookies = cookies.toMap(), warnings = extraWarnings)
    }

    private fun allowedCookieNames(service: WebSessionService): List<String> = when (service) {
        WebSessionService.GEMINI -> GEMINI_COOKIE_NAMES
        WebSessionService.CLAUDE -> CLAUDE_COOKIE_NAMES
    }

    private fun requiredCookieName(service: WebSessionService): String = when (service) {
        WebSessionService.GEMINI -> "__Secure-1PSID"
        WebSessionService.CLAUDE -> "sessionKey"
    }

    private fun allowedDomains(service: WebSessionService): Set<String> = when (service) {
        WebSessionService.GEMINI -> GEMINI_DOMAINS
        WebSessionService.CLAUDE -> CLAUDE_DOMAINS
    }

    private fun domainAllowed(service: WebSessionService, domain: String): Boolean =
        domain.trim().trimStart('.').lowercase() in allowedDomains(service)

    /** ASCII 可见字符, 不含分号/控制字符/空格, 避免 header 注入与截断 */
    private fun isHeaderToken(value: String): Boolean =
        value.all { it.code in 0x21..0x7E && it != ';' }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.numberField(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
