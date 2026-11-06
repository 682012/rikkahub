package me.rerere.rikkahub.ui.pages.chat

import java.util.Locale

/**
 * 缓存命中率的纯计算模型（无 Compose / 无 Android 依赖，可 JVM 单测）。
 *
 * 三条硬约束，全部来自真实上游口径：
 * 1. 分子分母必须来自同一批记录：调用方先把 source 过滤好（主对话 / 子代理 / 辅助各自单独算），
 *    这里只做同一批内按模型分组的拆分，绝不把别的来源的 prompt 拼进分母。
 * 2. "上游报了 0 命中"和"上游没报缓存字段"是两种事实，必须分开（r22 schema36 起有
 *    `usage_record.cached_reported`，旧行由 35->36 迁移把 `cached_tokens > 0` 的行标成已上报）：
 *    - 已上报（[CacheSample.reported] = true）：prompt 进分母，cached 哪怕 0 也是真实 0%，照算；
 *    - 未上报（false 或旧数据里的 null）：prompt 一律剔除并单独计数，绝不当 0%。
 *    没带覆盖度标志的样本（[CacheSample.reported] = null）按旧规则**逐条**推断：只有这一条自己的
 *    `cached > 0` 才算已上报；同一模型另一条报了命中，不能反过来把这条未知的输入塞进分母。
 * 3. 分母配对在 (source, provider, model) 内完成：调用方先按来源过滤，这里再按 modelKey 分组。
 * 4. 缓存是 input 的子集（OpenAI cached_tokens、Claude cache_read_input_tokens、Google
 *    cachedContentTokenCount 都含在 prompt 里），合计 token 不重复相加；Claude 的
 *    cache_creation 属于输入、不算命中，provider 层已经这样映射。
 *    上游报出负数或 cached > prompt 时整批作废，不给百分比（不能靠 clamp 造出 100%）。
 *    聚合行里出现坏行（含被同组大输入盖住的情况）由 DAO 的 invalidCacheReports 计数带上来
 *    （[CacheSample.invalid]），汇总后整批 [CacheHitRateState.INVALID]，不靠除法反推。
 */
enum class CacheHitRateState {
    /** 本批记录里没有任何模型报过有效缓存：只能显示"未报告"，不能说 0%。 */
    UNKNOWN,

    /** 至少一个模型报过缓存，比例只用这些模型的 prompt 做分母。 */
    REPORTED,

    /** 上游报告自相矛盾（负数 / 大于输入）：不给比例，只展示原始值。 */
    INVALID,
}

data class CacheHitRate(
    val state: CacheHitRateState = CacheHitRateState.UNKNOWN,
    /** 本批全部来源上报的缓存合计（未知记为 0，不会虚增）。 */
    val cachedTokens: Long = 0,
    /** 真正能当分母用的输入：只含上游明确上报过缓存的记录（含上报 0 命中的记录）。 */
    val eligiblePromptTokens: Long = 0,
    /** 能当分子用的命中量：与 [eligiblePromptTokens] 取自同一批记录，不掺入未上报的记录。 */
    val eligibleCachedTokens: Long = 0,
    /** 口径不明（上游没报缓存字段）而被排除在比例之外的输入，单独展示，不藏着。 */
    val excludedPromptTokens: Long = 0,
    val excludedRequests: Long = 0,
    /** 已上报但命中为 0 的那部分输入：真实 0% 和"未知"必须能分开看。 */
    val zeroHitPromptTokens: Long = 0,
) {
    /** 只有 [CacheHitRateState.REPORTED] 才有比例；其余情况为 null，UI 不许自己算。 */
    val fraction: Double? =
        if (state == CacheHitRateState.REPORTED && eligiblePromptTokens > 0) {
            (eligibleCachedTokens.toDouble() / eligiblePromptTokens).coerceIn(0.0, 1.0)
        } else {
            null
        }

    val percentText: String? = fraction?.let { "%.1f%%".format(Locale.US, it * 100.0) }

    val hasRatio: Boolean get() = fraction != null
}

/**
 * 同一来源内按 (provider, model) + 覆盖度分组的缓存样本。
 *
 * [modelKey] 必须带 provider 前缀（库里同一 modelId 可能挂在多个 provider 上），
 * 否则 A 渠道的命中会被配到 B 渠道的输入上。
 */
data class CacheSample(
    val modelKey: String,
    val promptTokens: Long,
    val cachedTokens: Long,
    val requests: Long = 0,
    /**
     * 这一行是否来自"上游明确上报过缓存"的记录。
     * true = 已上报（cached 为 0 也是真实 0%）；false/null = 未上报，不参与比例。
     * null 时按旧数据规则推断：`cachedTokens > 0` 视为已上报。
     */
    val reported: Boolean? = null,
    /**
     * 这一行背后是否存在上游自相矛盾的记录（cached < 0 / prompt < 0 / cached > prompt）。
     *
     * 聚合后单条坏行可能被同组的大输入盖住（9000+(-5) 这类合计仍然"合法"），所以由 SQL 逐行
     * 计数（`invalidCacheReports`）带上来，不能靠聚合值反推。任意一条为 true，整批判 [CacheHitRateState.INVALID]。
     */
    val invalid: Boolean = false,
)

/**
 * 按模型分组算命中率：报过缓存的模型进分子分母，没报过的整个模型排除并单独计数。
 *
 * 某个模型的聚合值里 cached > prompt 或 cached < 0 视为上游异常，整批 [CacheHitRateState.INVALID]；
 * 带 [CacheSample.invalid] 的行同样直接作废整批（那种坏行常常被同组的大输入掩盖）。
 */
fun computeCacheHitRate(samples: List<CacheSample>): CacheHitRate {
    if (samples.isEmpty()) return CacheHitRate()

    var totalCached = 0L
    var eligiblePrompt = 0L
    var eligibleCached = 0L
    var excludedPrompt = 0L
    var excludedRequests = 0L
    var zeroHitPrompt = 0L
    var reportedAny = false
    var invalid = samples.any { it.invalid }

    fun accept(prompt: Long, cached: Long, requests: Long, known: Boolean) {
        if (cached < 0 || cached > prompt) invalid = true
        if (!known) {
            excludedPrompt += prompt
            excludedRequests += requests
            return
        }
        reportedAny = true
        eligiblePrompt += prompt
        eligibleCached += cached
        if (cached == 0L) zeroHitPrompt += prompt
    }

    // 带明确覆盖度的行按行判定：同一模型也可能一次报了 0、一次没报字段，不能整模型一刀切。
    samples.filter { it.reported != null }.forEach { sample ->
        accept(
            prompt = sample.promptTokens.coerceAtLeast(0),
            cached = sample.cachedTokens,
            requests = sample.requests.coerceAtLeast(0),
            known = sample.reported == true,
        )
    }
    // 没带覆盖度的旧样本：逐条推断，只有自己 cached > 0 才算已上报。
    // 绝不按 modelKey 把同模型的样本先合并——那样一条命中就能把同模型其它"没报字段"的输入
    // 拖进分母，分母被稀释、命中率被压低，等于把未知当已知。
    samples.filter { it.reported == null }.forEach { sample ->
        val prompt = sample.promptTokens.coerceAtLeast(0)
        val cached = sample.cachedTokens
        accept(
            prompt = prompt,
            cached = cached,
            requests = sample.requests.coerceAtLeast(0),
            known = cached > 0,
        )
    }

    samples.forEach { totalCached += it.cachedTokens }

    val state = when {
        invalid -> CacheHitRateState.INVALID
        reportedAny -> CacheHitRateState.REPORTED
        else -> CacheHitRateState.UNKNOWN
    }
    return CacheHitRate(
        state = state,
        cachedTokens = totalCached,
        eligiblePromptTokens = if (state == CacheHitRateState.REPORTED) eligiblePrompt else 0,
        eligibleCachedTokens = if (state == CacheHitRateState.REPORTED) eligibleCached else 0,
        excludedPromptTokens = excludedPrompt,
        excludedRequests = excludedRequests,
        zeroHitPromptTokens = zeroHitPrompt,
    )
}

/** usage_record.source 归一化：库里有历史大小写混写（fork.15 直接写枚举名，个别导入数据是小写）。 */
fun normalizeUsageSource(source: String): String = source.trim().uppercase(Locale.ROOT)

/** 主对话：只有这一种来源算"本对话用量"的默认口径。 */
const val USAGE_SOURCE_CHAT: String = "CHAT"

/** 子代理：conversation_id 与父会话相同，只能靠 source 区分，绝不能只按 conversation_id 统计。 */
const val USAGE_SOURCE_SUBAGENT: String = "SUBAGENT"

/** 一个来源在本次统计里的归属分类，供 UI 单列展示。 */
enum class UsageSourceRole {
    CHAT,
    SUBAGENT,

    /** 摘要、标题、快速模型、翻译、OCR、记忆、悬浮窗等辅助请求。 */
    AUXILIARY,
}

fun roleOfUsageSource(source: String): UsageSourceRole = when (normalizeUsageSource(source)) {
    USAGE_SOURCE_CHAT -> UsageSourceRole.CHAT
    USAGE_SOURCE_SUBAGENT -> UsageSourceRole.SUBAGENT
    else -> UsageSourceRole.AUXILIARY
}
