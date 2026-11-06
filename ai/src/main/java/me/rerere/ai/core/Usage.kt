package me.rerere.ai.core

import kotlinx.serialization.Serializable

/**
 * 一次请求的 token 用量快照。
 *
 * 缓存字段的真实含义（parser 必须按这个填）：
 * - [cachedTokens] = **命中读缓存**的输入 token（OpenAI `prompt_tokens_details.cached_tokens` /
 *   顶层 `cached_tokens`、DeepSeek `prompt_cache_hit_tokens`、Anthropic `cache_read_input_tokens`、
 *   Google `cachedContentTokenCount`）。它是 [promptTokens] 的**子集**，合计 token 时不能再加一遍。
 * - [cacheCreationTokens] = 本次**新写入**缓存的输入 token（Anthropic `cache_creation_input_tokens`）。
 *   它同样属于输入、按输入计费，但**不是命中**，绝不能混进 [cachedTokens]。
 * - 两个 provider 对同一模型也可能一次上报、一次缺字段：所以"上报了 0 命中"和"根本没报缓存字段"
 *   必须能区分，靠 [cachedReported]：
 *   - `true`：上游明确给了缓存字段，[cachedTokens] 是真实值（**0 也是真实值**）；
 *   - `null`：上游没报这个字段，[cachedTokens] 的 0 **只是缺省**，不能当 0% 命中率；
 *   - 旧数据（schema35 及更早）没有这一位，解码后就是 `null`，按未知处理，不伪造 0%。
 *
 * 新增字段都有默认值，向后兼容：旧 JSON 解码得到 null，旧调用点（含 UI 测试）不改也能编译。
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    /** 缓存命中字段是否由上游明确上报：true=上报（含 0），null=未知。 */
    val cachedReported: Boolean? = null,
    /** 新写入缓存的输入 token（Claude cache_creation）；null=该 provider 无此概念或没报。 */
    val cacheCreationTokens: Int? = null,
) {
    /** 缓存命中是否可信（上报了就是可信，包括真 0）。 */
    val hasKnownCache: Boolean get() = cachedReported == true
}

/**
 * 合并同一次请求内的 usage 快照：各字段取最新的**有效**值。
 *
 * 覆盖度（[TokenUsage.cachedReported]）跟着取值来源一起走，不能被"另一个快照的 0"抹掉：
 * - 后一个快照有明确缓存上报 → 用它的值和它的 true（显式 0 也是真实值）；
 * - 后一个快照没报缓存字段 → 沿用前一个快照的值和覆盖度，不把它降级成未知。
 *
 * 跨请求（如多轮工具调用的各个步骤）累计请用 [sum]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val otherCacheKnown = other.cachedReported ?: (other.cachedTokens > 0)
    val cachedTokens: Int
    val cachedReported: Boolean?
    if (otherCacheKnown == true) {
        // 不 clamp：上游报出负数/大于输入时必须原样保留，否则 UI 的异常检测永远看不到。
        cachedTokens = other.cachedTokens
        cachedReported = true
    } else if (this != null) {
        cachedTokens = this.cachedTokens
        cachedReported = this.cachedReported
    } else {
        cachedTokens = other.cachedTokens
        cachedReported = null
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cachedReported = cachedReported,
        cacheCreationTokens = other.cacheCreationTokens ?: this?.cacheCreationTokens,
    )
}

/**
 * 累加多次请求的 usage，每次请求都会按完整上下文计费，因此 prompt 也直接相加。
 *
 * **覆盖度只降不升**：参与累加的任意一次请求没上报缓存（`null`），合计就只能是 `null`（部分覆盖），
 * 绝不能因为另一次报了缓存就把整批说成"完整已上报"。同理 [TokenUsage.cacheCreationTokens]
 * 只要有一次未知，合计就是 `null`，不拿已知的那部分冒充总量。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    val bothCacheKnown = cachedReported == true && other.cachedReported == true
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        cachedReported = if (bothCacheKnown) true else null,
        cacheCreationTokens = if (cacheCreationTokens != null && other.cacheCreationTokens != null) {
            cacheCreationTokens + other.cacheCreationTokens!!
        } else {
            null
        },
    )
}
