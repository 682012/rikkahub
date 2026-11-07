package me.rerere.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户在设置页自己加的「额外重试的错误匹配类型」。
 *
 * 它只回答一件事：**这条失败的异常文本像不像用户描述的那种错误**。命中之后要不要真的重试，
 * 仍然由 [RetryScope] 决定（用户勾了「自定义匹配」这一类才重试），而且永远过不了下面那几道安全边界。
 *
 * 设计取舍：
 * - [literal] 是默认模式：纯子串匹配，不解释任何正则元字符，用户写 `429 (slow)` 就是字面意思，
 *   不会因为一个没转义的括号让整条规则失效，也没有灾难性回溯的风险。
 * - [CONTAINS] 正则模式是可选项，但只接受**已通过 [CustomRetryRule.validate] 校验**的表达式：
 *   长度上限、拒绝嵌套量词（`(a+)+` 这类灾难性回溯的形状）、并且编译失败的一律不落盘。
 * - 匹配对象只有异常消息，不匹配 HTTP 状态码：状态码仍然只认真实 `Response.code`，
 *   不从文本里猜数字（猜错一个码就会把余额不足当成抖动无限重发）。
 */
@Serializable
enum class CustomRetryMatchMode {
    /** 字面子串，大小写不敏感。默认，也是唯一推荐的模式。 */
    @SerialName("literal")
    LITERAL,

    /** 正则（大小写不敏感）。只接受校验通过的短表达式。 */
    @SerialName("regex")
    REGEX,
}

/**
 * 一条自定义错误匹配规则。
 *
 * 默认值（[pattern] 为空）在 [validate] 里被判为无效，不会参与匹配，所以老配置解码出来是安全的空集合。
 */
@Serializable
data class CustomRetryRule(
    /** 稳定 id，界面用来定位编辑/删除；不参与匹配。 */
    val id: String = "",
    /** 用户看得见的名字，可为空（界面回落到 [pattern]）。 */
    val label: String = "",
    /** 要匹配的文本；大小写不敏感。 */
    val pattern: String = "",
    val mode: CustomRetryMatchMode = CustomRetryMatchMode.LITERAL,
    /** 关掉但保留的规则：不参与匹配，也不删除。 */
    val enabled: Boolean = true,
) {
    companion object {
        /** 单条规则的长度上限：再长就不是"错误匹配"，而是误粘贴的整段日志。 */
        const val MAX_PATTERN_LENGTH: Int = 120
        const val MAX_LABEL_LENGTH: Int = 40
        /** 规则条数上限：防止配置膨胀到每次失败都要扫上千条字符串。 */
        const val MAX_RULES: Int = 20

        /** 拒绝看起来像灾难性回溯的表达式：`(a+)+`、`(.*)*`、`(a|a)*` 的常见形状。 */
        private val NESTED_QUANTIFIER = Regex("""\([^)]*[+*][^)]*\)\s*[+*{]""")
        /** 同一组重复里带选择分支再加量词，也会指数展开。 */
        private val QUANTIFIED_ALTERNATION = Regex("""\([^)]*\|[^)]*\)\s*[+*{!]""")
    }

    /** 这条规则匹配到的文本；空白或未启用时返回空串，调用方按"不匹配"处理。 */
    val effectivePattern: String
        get() = pattern.trim()

    /** 界面显示的标题：没写名字就用模式本身。 */
    val displayLabel: String
        get() = label.trim().ifEmpty { effectivePattern }

    /**
     * 校验一条规则的可用性。返回 null 表示可用，否则返回**给人看的**原因。
     *
     * 校验必须在落盘之前跑：不合法的正则绝对不能存进设置，否则每次请求失败都要重新编译一次坏表达式。
     */
    fun validate(): String? {
        val trimmed = effectivePattern
        if (trimmed.isEmpty()) return "pattern is empty"
        if (trimmed.length > MAX_PATTERN_LENGTH) return "pattern is longer than $MAX_PATTERN_LENGTH characters"
        if (label.length > MAX_LABEL_LENGTH) return "label is longer than $MAX_LABEL_LENGTH characters"
        if (trimmed.any { it.isISOControl() }) return "pattern contains control characters"
        if (mode == CustomRetryMatchMode.REGEX) {
            if (NESTED_QUANTIFIER.containsMatchIn(trimmed) ||
                QUANTIFIED_ALTERNATION.containsMatchIn(trimmed)
            ) {
                return "regex looks like it can backtrack catastrophically"
            }
            val compiled = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return "invalid regex"
            // 用一个短样本跑一遍，编译通过但一跑就炸的表达式（极少见）也挡在落盘之前
            runCatching { compiled.containsMatchIn("probe") }.getOrElse { return "invalid regex" }
        }
        return null
    }

    /** 这条规则是否真的命中这条错误的文本。 */
    fun matches(error: Throwable): Boolean {
        if (!enabled) return false
        val pattern = effectivePattern
        if (pattern.isEmpty()) return false
        // 只看异常消息；没有消息的异常不可能是用户描述的那种
        val text = error.message ?: return false
        if (text.isEmpty()) return false
        return when (mode) {
            CustomRetryMatchMode.LITERAL ->
                text.contains(pattern, ignoreCase = true)

            CustomRetryMatchMode.REGEX -> {
                // 没通过校验的规则一律不跑：设置可能来自旧版本或被手改过
                if (validate() != null) return false
                runCatching {
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
                }.getOrElse {
                    // 运行期仍然炸了：当成不匹配，绝不把它变成"重试一切"
                    false
                }
            }
        }
    }
}

/**
 * 一组自定义规则的纯函数工具：过滤、去重、按条数上限截断。
 *
 * 这些都放在 ai 模块里，设置页和测试共用同一份实现，界面不做第二套判断。
 */
object CustomRetryRules {
    /** 只留下能用且启用的规则，并去重（同 id 或同 模式+文本 视为同一条）。 */
    fun usable(rules: List<CustomRetryRule>): List<CustomRetryRule> {
        val seen = HashSet<String>()
        return rules.asSequence()
            .filter { it.enabled && it.validate() == null }
            .filter { seen.add(it.dedupeKey()) }
            .take(CustomRetryRule.MAX_RULES)
            .toList()
    }

    /**
     * 把一条规则写进列表（同 id 覆盖，否则追加）。
     *
     * 不合法的规则**不会**被写进去：返回 null 表示这次编辑应当被界面拒绝并给出原因。
     */
    fun upsert(rules: List<CustomRetryRule>, rule: CustomRetryRule): List<CustomRetryRule>? {
        if (rule.validate() != null) return null
        val existing = rules.indexOfFirst { it.id.isNotEmpty() && it.id == rule.id }
        val next = if (existing >= 0) {
            rules.toMutableList().also { it[existing] = rule }
        } else {
            // 没有 id 的新规则补一个稳定的（用内容哈希，不依赖系统时间，测试可复现）
            val withId = if (rule.id.isNotEmpty()) rule else rule.copy(id = rule.dedupeKey().hashString())
            if (rules.any { it.dedupeKey() == withId.dedupeKey() }) return rules
            rules + withId
        }
        if (next.size > CustomRetryRule.MAX_RULES) return null
        return next
    }

    fun remove(rules: List<CustomRetryRule>, id: String): List<CustomRetryRule> =
        rules.filterNot { it.id.isNotEmpty() && it.id == id }
}

/** 去重键：模式和文本都相同就是同一条规则（大小写不敏感）。 */
internal fun CustomRetryRule.dedupeKey(): String =
    "${mode.name}:${effectivePattern.lowercase()}"

/** 不依赖 Random/时间的内容哈希：同一条规则每次算出来都一样。 */
private fun String.hashString(): String {
    var hash = 1125899906842597L
    for (c in this) hash = 31 * hash + c.code
    return "c" + (hash and Long.MAX_VALUE).toString(36)
}

/**
 * 这条错误是否命中用户的任意一条自定义规则（且不是被明确排除的写操作）。
 *
 * 安全边界（失败即不重试）：**已经产生副作用的请求不会被自定义规则变成可重试**。
 * 判定入口是 [isSafeToReplay]：非幂等写操作标记为不可重放时，这里一律返回 false，
 * 无论用户写了多宽的自定义规则。
 */
fun customRuleMatches(
    rules: List<CustomRetryRule>,
    error: Throwable,
    safeToReplay: Boolean = true,
): Boolean {
    if (!safeToReplay) return false
    if (rules.isEmpty()) return false
    return CustomRetryRules.usable(rules).any { it.matches(error) }
}

/**
 * 一次失败在这次调用里「可不可以重放」。
 *
 * 只有明确知道请求没有产生副作用（工具还没执行、响应没写进任何地方）时才允许重放。
 * 默认 **true** 保持老行为（AI 请求重发本身是幂等的读取），但调用方一旦知道这一轮已经执行过
 * 非幂等写工具，必须传 false：自定义匹配再宽也不能把写操作自动重试。
 */
fun isSafeToReplay(hasNonIdempotentSideEffect: Boolean): Boolean =
    !hasNonIdempotentSideEffect
