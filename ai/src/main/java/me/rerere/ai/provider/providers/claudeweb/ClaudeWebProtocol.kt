package me.rerere.ai.provider.providers.claudeweb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import me.rerere.ai.util.json

/** The only origin the web session is ever sent to. Provider settings cannot change it. */
internal const val CLAUDE_WEB_ORIGIN = "https://claude.ai"
internal const val CLAUDE_WEB_HOST = "claude.ai"

/**
 * Honest client identity: not a browser, not the official Claude client. No UA impersonation and no
 * browser-fingerprint reproduction; if claude.ai refuses this client, the user is told to sign in again
 * rather than being pushed through a challenge.
 */
internal const val CLAUDE_WEB_USER_AGENT = "RikkaHub-WebSession/experimental"

internal const val CLAUDE_WEB_DEFAULT_MODEL_ID = "claude-web-default"
internal const val CLAUDE_WEB_DEFAULT_MODEL_NAME = "Claude 网页默认"

/** The web completion endpoint wants a parent message; a fresh conversation has to start at this sentinel. */
internal const val CLAUDE_WEB_PARENT_MESSAGE_UUID = "00000000-0000-4000-8000-000000000000"

/** Only the session cookie is ours; Cloudflare/browser-state cookies are deliberately never replayed. */
private val CLAUDE_WEB_COOKIE_ALLOWLIST = setOf("sessionKey")

/**
 * Builds the Cookie header from our own narrow allowlist.
 *
 * `sessionKey` is the only credential the web session actually needs. Everything else the browser holds
 * (`__cf_bm`, `cf_clearance`, analytics, ...) belongs to the browser and is not forwarded, so this client
 * cannot be mistaken for a copied browser profile.
 */
internal fun claudeWebCookieHeader(credential: WebSessionCredential): String {
    if (credential.service != WebSessionService.CLAUDE ||
        !credential.service.host.equals(CLAUDE_WEB_HOST, ignoreCase = true)
    ) {
        throw WebSessionException("当前凭据不是 claude.ai 的网页会话，已拒绝发送。")
    }
    val allowed = credential.cookies
        .filterKeys { it in CLAUDE_WEB_COOKIE_ALLOWLIST }
        .toSortedMap()
    val sessionKey = allowed["sessionKey"]?.takeIf { it.isNotBlank() }
        ?: throw WebSessionException("Claude 网页会话缺少 sessionKey，请在浏览器重新登录后重新导入会话。")
    if (sessionKey.any { it == '\r' || it == '\n' || it == ';' }) {
        throw WebSessionException("Claude 网页会话的 sessionKey 格式不合法，已拒绝发送。")
    }
    return allowed.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal data class ClaudeWebOrganization(
    val uuid: String,
    val name: String?,
    val personal: Boolean,
)

/**
 * Parses `GET /api/organizations`.
 *
 * Personal-organization detection only reads explicit markers. It never guesses from list order and never
 * falls back to another organization: talking to the wrong workspace would silently spend the wrong
 * account's quota.
 */
internal fun parseClaudeWebOrganizations(body: String): List<ClaudeWebOrganization> {
    val root = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw WebProtocolException("Claude Web 组织列表不是合法 JSON")
    }
    val array = root as? JsonArray
        ?: throw WebProtocolException("Claude Web 组织列表格式不正确")
    return array.mapIndexed { index, element ->
        val obj = element as? JsonObject
            ?: throw WebProtocolException("Claude Web 组织列表第 ${index + 1} 项不是对象")
        val uuid = obj.string("uuid")?.takeIf { it.isNotBlank() }
            ?: throw WebProtocolException("Claude Web 组织列表第 ${index + 1} 项缺少 uuid")
        ClaudeWebOrganization(
            uuid = uuid,
            name = obj.string("name"),
            personal = obj.looksPersonal(),
        )
    }
}

/**
 * Chooses exactly one organization.
 *
 * - an explicit `organizationId` is honoured or rejected, never replaced;
 * - a single organization is unambiguous;
 * - with several organizations only one explicit personal marker is accepted;
 * - anything else asks the user to choose instead of crossing workspaces.
 */
internal fun selectClaudeWebOrganization(
    organizations: List<ClaudeWebOrganization>,
    configuredOrganizationId: String?,
): ClaudeWebOrganization {
    if (organizations.isEmpty()) {
        throw WebSessionException("该 Claude 会话没有返回任何组织，无法确定要使用的账号空间。")
    }
    val configured = configuredOrganizationId?.trim()?.takeIf { it.isNotEmpty() }
    if (configured != null) {
        return organizations.firstOrNull { it.uuid == configured }
            ?: throw WebSessionException(
                "提供商设置里的 organizationId 不在该账号的组织列表中，请重新选择；未跨组织自动切换。"
            )
    }
    if (organizations.size == 1) return organizations.single()

    val personal = organizations.filter { it.personal }
    if (personal.size == 1) return personal.single()

    val reason = if (personal.size > 1) "存在多个个人组织" else "无法唯一判断个人组织"
    throw WebSessionException(
        "该 Claude 账号有 ${organizations.size} 个组织，$reason。" +
            "请在提供商设置中填写 organizationId 后重试；未自动跨组织选择。"
    )
}

private val PERSONAL_FLAGS = listOf("is_personal", "isPersonal", "personal")
private val PERSONAL_TYPES = setOf(
    "personal",
    "individual",
    "claude_free",
    "claude_pro",
    "claude_max",
    "free",
    "pro",
    "max",
)

private fun JsonObject.looksPersonal(): Boolean {
    for (flag in PERSONAL_FLAGS) {
        if ((this[flag] as? JsonPrimitive)?.booleanOrNull == true) return true
    }
    val type = listOf("organization_type", "organizationType", "plan_type", "planType", "type")
        .firstNotNullOfOrNull { string(it)?.trim()?.lowercase() }
    return type in PERSONAL_TYPES
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * HTTP answers that mean "the session itself is the problem". They are [WebSessionException], not
 * [java.io.IOException], so no retry layer can turn a rejected login into an account lockout.
 */
internal fun classifyClaudeWebHttpFailure(code: Int, body: String): Exception {
    val challenge = looksLikeBrowserChallenge(body)
    return when {
        code in 300..399 -> WebSessionException(
            "Claude Web 会话被重定向（HTTP $code），未跟随跳转；请在浏览器重新登录并重新导入会话。"
        )

        code == 401 || code == 403 -> WebSessionException(
            if (challenge) {
                "claude.ai 返回了人机验证/挑战页面（HTTP $code）；不会尝试绕过验证码、Cloudflare 或 TLS 指纹，" +
                    "请在浏览器完成验证后重新导入会话。"
            } else {
                "Claude Web 会话被拒绝（HTTP $code）；会话可能已失效，请在浏览器重新登录并重新导入。"
            }
        )

        code == 429 -> WebSessionException(
            "Claude Web 请求被限流（HTTP 429）；不会自动重试，请稍后再试。"
        )

        challenge -> WebSessionException(
            "claude.ai 返回了人机验证/挑战页面；不会尝试绕过验证码、Cloudflare 或 TLS 指纹，" +
                "请在浏览器完成验证后重新导入会话。"
        )

        else -> WebProtocolException("Claude Web 返回 HTTP $code")
    }
}

/** Maps an SSE/API error object. Only the error type is surfaced; server text is not echoed. */
internal fun claudeWebApiError(root: JsonObject): Exception {
    val type = ((root["error"] as? JsonObject)?.string("type") ?: root.string("type"))
        .orEmpty()
        .lowercase()
    return when {
        "rate_limit" in type ||
            "usage_limit" in type ||
            "message_limit" in type ||
            "quota" in type -> WebSessionException(
            "Claude Web 账号触发限流或额度限制（${type.take(80).filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "unknown" }}）；不会自动重试，请稍后再试。"
        )

        "authentication" in type ||
            "permission" in type ||
            "session" in type ||
            "unauthorized" in type ||
            "challenge" in type ||
            "captcha" in type -> WebSessionException(
            "Claude Web 会话被拒绝（${type.take(80).filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "unknown" }}）；请在浏览器重新登录并重新导入会话。"
        )

        "invalid_request" in type -> WebSessionException(
            "Claude Web 拒绝了请求（$type）；这不是会话失效，也不会自动重试。"
        )

        else -> WebProtocolException(
            "Claude Web 返回错误事件（${type.take(80).filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "unknown" }}）"
        )
    }
}

internal fun looksLikeBrowserChallenge(body: String): Boolean {
    val lower = body.take(8192).lowercase()
    return lower.contains("captcha") ||
        lower.contains("challenge-platform") ||
        lower.contains("cf-chl") ||
        lower.contains("just a moment") ||
        lower.contains("verify you are human") ||
        lower.contains("challenge_required")
}

internal fun looksLikeLoginPage(body: String): Boolean {
    val lower = body.take(8192).lowercase()
    return lower.contains("sign in") ||
        lower.contains("log in") ||
        lower.contains("login") ||
        lower.contains("accounts/anthropic")
}

