package me.rerere.rikkahub.data.ai.clientprofile

import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.data.model.ConversationClientProfile

/**
 * 档绝不碰的请求头名（小写比较）。
 *
 * 两类：
 * 1. 凭证 —— `authorization` / `x-api-key` / `x-goog-api-key` / cookie / 组织与项目标识。
 *    用户自己设了就永远原值发出去，档不覆盖、不改写、不代填。
 * 2. 协议所有权已在 provider 实现里 —— `anthropic-version` 由 ClaudeProvider 发、
 *    `content-type`/`content-length`/`host` 由 OkHttp 发。档再写一遍只会打架，所以直接不允许。
 */
internal val PROFILE_FORBIDDEN_HEADER_NAMES: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
    "cookie",
    "cookie2",
    "set-cookie",
    "openai-organization",
    "openai-project",
    "anthropic-version",
    "anthropic-beta",
    "content-type",
    "content-length",
    "host",
)

/**
 * 各档要加的**非凭证**请求头。
 *
 * 只放公开仓库里能确认的客户端标识，且刻意不带版本号：版本要跟着真客户端发版跑，
 * 猜一个版本号等于伪造。这里也不含任何账号、密钥、签名。
 *
 * - CODEX：openai/codex 的 CLI 走 Responses 时用 `originator` 标识来源（Rust 版为 `codex_cli_rs`）。
 * - CLAUDE_CODE：anthropic 的 CLI 用 `x-app: cli` 与 `user-agent: claude-cli/<ver> (external, cli)`；
 *   版本段省略，只保留产品标识。
 * - PI：pi-ai 是统一封装，不带自家产品标识头，所以不加 —— 不编一个出来。
 */
internal fun profileHeadersFor(
    profile: ConversationClientProfile,
    dialect: ProfileWireDialect,
): List<CustomHeader> = when (profile) {
    ConversationClientProfile.CODEX -> when (dialect) {
        ProfileWireDialect.RESPONSES,
        ProfileWireDialect.CHAT_COMPLETIONS,
        -> listOf(CustomHeader("originator", "codex_cli_rs"))

        else -> emptyList()
    }

    ConversationClientProfile.CLAUDE_CODE -> when (dialect) {
        ProfileWireDialect.ANTHROPIC_MESSAGES -> listOf(
            CustomHeader("x-app", "cli"),
            CustomHeader("user-agent", "claude-cli"),
        )

        else -> emptyList()
    }

    // 原生与 Pi 不加头；AUTO 在这里不该出现（解析阶段已经确定有效档）
    ConversationClientProfile.PI,
    ConversationClientProfile.NATIVE,
    ConversationClientProfile.AUTO,
    -> emptyList()
}

/** 一次头合并的结果。 */
internal data class ProfileHeaderMerge(
    val merged: List<CustomHeader>,
    /** 真正由档新增、准备上线的头 */
    val added: List<CustomHeader>,
    /** 被丢掉的名字（日志/诊断用，不带上值，避免把敏感串写进日志） */
    val dropped: List<String>,
)

/**
 * 把档内头并进用户已有的头。
 *
 * 优先级：用户（assistant + model 的 customHeaders）> provider > 档。
 * 同名按大小写不敏感比较，用户有就整条跳过；含 CR/LF 的头直接拒（请求头注入）；
 * 凭证与 provider 自有的协议头（见 [PROFILE_FORBIDDEN_HEADER_NAMES]）一律不加。
 */
internal fun sanitizeProfileHeaders(
    userHeaders: List<CustomHeader>,
    profileHeaders: List<CustomHeader>,
): ProfileHeaderMerge {
    val userNames = userHeaders.map { it.name.lowercase() }.toHashSet()
    val seen = HashSet<String>(userNames)
    val added = mutableListOf<CustomHeader>()
    val dropped = mutableListOf<String>()

    for (header in profileHeaders) {
        val name = header.name.trim()
        if (name.isEmpty()) {
            dropped += header.name
            continue
        }
        // CRLF 会把一个头拆成两个，属于请求头注入，直接拒绝而不是"清洗后照发"
        if (name.any { it == '\r' || it == '\n' } || header.value.any { it == '\r' || it == '\n' }) {
            dropped += name
            continue
        }
        val lower = name.lowercase()
        if (lower in PROFILE_FORBIDDEN_HEADER_NAMES) {
            dropped += name
            continue
        }
        if (!seen.add(lower)) continue // 用户已有同名，用户优先
        added += CustomHeader(name, header.value)
    }

    return ProfileHeaderMerge(
        merged = userHeaders + added,
        added = added,
        dropped = dropped,
    )
}

/**
 * 把档内请求体参数并进用户已有的 customBody。
 *
 * 用户（assistant/model）自己写的同名键优先，档不覆盖 —— 有人会拿 customBody 覆盖 `store`
 * 或者给网关加私有字段，那种配置不能被兼容档悄悄改掉。
 */
internal fun mergeProfileBodiesInto(
    userBodies: List<CustomBody>,
    profileBodies: List<CustomBody>,
): List<CustomBody> {
    if (profileBodies.isEmpty()) return userBodies
    val userKeys = userBodies.map { it.key }.toHashSet()
    val seen = HashSet<String>(userKeys)
    val added = profileBodies.filter { body ->
        body.key.isNotBlank() && seen.add(body.key)
    }
    return userBodies + added
}
