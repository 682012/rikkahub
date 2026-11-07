package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.NetworkRouteChangedException
import me.rerere.ai.provider.NetworkUnavailableException
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.util.HttpException
import me.rerere.common.http.NetworkChangedException
import me.rerere.common.http.NetworkHandover
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 随机区间的硬上限（一天），防止外部传入离谱的区间把秒数溢出成负值。 */
private const val MAX_RANGE_SECONDS = 86_400L

/**
 * 退避等待的最大检查粒度：每等这么久重新读一次 [RetryPolicy.isEnabled]。
 *
 * 用户关掉「自动重试」不该等到这一次退避（最长 60 秒）走完才生效，也不该让谁去每秒轮询整个设置对象。
 */
private const val RETRY_GATE_STEP_MS = 1_000L

/**
 * 429 里混着一种「等多久都不会好」的情况：额度真的用完了。
 *
 * 只有服务端**明确**说出这几个额度标记时才算额度错误；普通的 429（"rate limit reached"、
 * "too many requests"）仍然按可重试处理。这里只认服务端自己给的标记，不猜。
 */
private val QUOTA_EXHAUSTED_MARKERS = listOf(
    "insufficient_quota",
    "billing_hard_limit",
    "billing_not_active",
    "exceeded your current quota",
    "credit balance is too low",
)

/**
 * 拿这次失败的**真实** HTTP 状态码，拿不到就是 null。
 *
 * 只认 provider 用真实 `Response.code` 构造出来的 [HttpException.statusCode]；
 * 从错误文本里正则猜数字是明确禁止的——猜错一个码就会把额度不足当成服务端抖动无限重发。
 * `parseErrorDetail()` 解析 body 得到的异常没有码，这里返回 null，调用方（ChatAutoRecovery 等）
 * 必须按「不知道码」处理，不能当成可重试。
 */
fun httpStatusOf(error: Throwable): Int? = (error as? HttpException)?.statusCode

/**
 * 这次失败值不值得再发一次同样的请求。
 *
 * 两条来源：
 * - 传输层：[IOException] 一族（连不上、超时、读到一半断开），但协议违规类
 *   （[WebProtocolException]）和 TLS 握手/证书错误排除在外——后者重发只会再失败一次；
 * - 服务端：[HttpException] 带着真实状态码，且码在 [retryableHttpStatus] 白名单里，
 *   429 还要排除「额度已耗尽」这种等不回来的情况。
 *
 * [HttpException] 没有状态码（纯解析 body 得到的）时返回 false：不确定就不重试。
 * 这条规则对 `withRetry`、ChatService 的自动恢复和 app 侧共用同一个入口，避免三处各写一份。
 */
fun retryableFailure(error: Throwable): Boolean = RetryScope.CLASSIFIED.allows(error)

/**
 * 一次模型请求失败属于哪一类。
 *
 * 这个枚举是「用户可选的重试范围」的唯一词汇表：设置页的多选、[RetryScope]、[RecoveryPolicy]
 * 的分类、[RetryBudget] 的记账都用它，不再各自写 is/else。
 *
 * 类别只描述**请求为什么失败**，不描述「能不能重试」——能不能重试由 [RetryScope] 决定，
 * 用户停止/等待审批/权限/未知工具副作用根本不进这个枚举，它们不是请求错误。
 */
@Serializable
enum class RetryErrorCategory {
    /** 传输层：连不上、超时、读到一半断开、信号中断网、换网作废。 */
    NETWORK,

    /** 服务端临时错误：408 / 429（非额度耗尽）/ 5xx。 */
    SERVER,

    /** 鉴权失败：401 / 403、Web 会话登录过期。重发前必须先修配置。 */
    AUTH,

    /** 余额或配额用尽：402、明确说了额度用完的 429。重发不会自己好。 */
    QUOTA,

    /** 请求本身不合法或响应格式不对：400 / 422、协议违规、没有真实状态码的解析失败。 */
    REQUEST_FORMAT,

    /** 答复是空的或没收完：半截流、空内容。 */
    EMPTY_RESPONSE,

    /** 其它（含自定义供应商抛出的自有异常）：不知道原因，默认不猜。 */
    UNKNOWN,

    /**
     * 命中用户在设置页自己写的「额外重试的错误匹配类型」。
     *
     * 它不是一个真实的错误原因，而是「这条失败被用户的自定义规则认领了」这个事实。
     * 只有用户显式勾上这一类（或打开"任何错误"）才会重试，且永远不会越过
     * [isSafeToReplay]：已经产生副作用的非幂等写操作不会被自动重试。
     */
    CUSTOM_MATCH,
}

/**
 * 用户想要的重试范围。
 *
 * - [RetryScopeMode.CLASSIFIED]（默认）：只重试历史上一直重试的那几类（传输层、408/429/5xx、
 *   半截流）。401/403/402/格式错误一律不试，这是老版本行为，升级后一个字都不会变。
 * - [RetryScopeMode.CUSTOM]：在旧分类之上追加用户勾选的类别（[categories] 是**追加**，不是替换：
 *   勾选「鉴权」不代表不要网络重试）。
 * - [RetryScopeMode.ANY_ERROR]：任何模型请求错误都重试，等价于勾满所有类别。
 *
 * 任何模式下用户停止、等待审批、未经授权的权限错误、不确定的工具副作用都不在重试范围里，
 * 这是安全边界，不会被这个设置打开。
 */
@Serializable
data class RetryScope(
    val mode: RetryScopeMode = RetryScopeMode.CLASSIFIED,
    val categories: Set<RetryErrorCategory> = emptySet(),
    /**
     * 用户自己写的错误匹配规则；空列表等于没配，老配置解码出来就是空的。
     *
     * 只要集合里有至少一条通过校验的规则，[RetryScopeMode.CUSTOM] 下就自动视为勾上了
     * [RetryErrorCategory.CUSTOM_MATCH]（界面不用再手动点一次）。命中规则只换来"允许重试"，
     * 永远过不了 [isSafeToReplay] 那道写操作边界：已经执行过非幂等写工具的一轮不会被自动重试，
     * 连 [RetryScopeMode.ANY_ERROR] 也一样。
     */
    val customRules: List<CustomRetryRule> = emptyList(),
    /**
     * 这次失败能不能安全重放。默认 true = 老行为。
     *
     * 调用方已经知道这一轮执行过非幂等写工具时传 false：此时自定义匹配、甚至"任何错误"都不生效，
     * 免得把一次已经落盘的写操作重发成两次。
     */
    val safeToReplay: Boolean = true,
) {
    /** 默认就重试的类别：老版本 `retryableFailure` 放行的就是这几类。 */
    val effectiveCategories: Set<RetryErrorCategory>
        get() = when (mode) {
            RetryScopeMode.CLASSIFIED -> LEGACY_CATEGORIES
            // 追加：默认分类永远在，勾选的只是多出来的部分
            RetryScopeMode.CUSTOM -> LEGACY_CATEGORIES + categories + optionalCategories
            RetryScopeMode.ANY_ERROR -> RetryErrorCategory.entries.toSet()
        }

    /** CUSTOM 模式下实际追加的类别：[categories] 加上"有可用自定义规则"时的 [RetryErrorCategory.CUSTOM_MATCH]。 */
    private val optionalCategories: Set<RetryErrorCategory>
        get() = categories + if (hasUsableCustomRules) setOf(RetryErrorCategory.CUSTOM_MATCH) else emptySet()

    /** 有至少一条通过校验且启用的自定义规则。 */
    val hasUsableCustomRules: Boolean
        get() = enabledCustomRules.isNotEmpty()

    /** 通过校验且启用的规则（缓存一次，避免每次失败都重算）。 */
    private val enabledCustomRules: List<CustomRetryRule> by lazy { CustomRetryRules.usable(customRules) }

    /** 这一类的失败要不要再发一次同样的请求。 */
    fun allows(category: RetryErrorCategory): Boolean {
        // 写操作边界优先：有副作用时任何类别都不放行，包括"任何错误"
        if (!safeToReplay) return false
        if (category == RetryErrorCategory.CUSTOM_MATCH) {
            return category in effectiveCategories || (mode == RetryScopeMode.CUSTOM && hasUsableCustomRules)
        }
        return category in effectiveCategories
    }

    /**
     * 这条错误要不要重试。
     *
     * 判定顺序：
     * 1. 写操作边界：不可重放时直接 false；
     * 2. 用户自定义规则命中 → [RetryErrorCategory.CUSTOM_MATCH]（真实类别是网络/服务端等，
     *    仍然按真实类别走默认放行，自定义规则只是"额外追加"而不是替换）；
     * 3. 否则按 [retryCategoryOf] 的真实分类。
     */
    fun allows(error: Throwable): Boolean {
        if (!safeToReplay) return false
        val category = retryCategoryOf(error)
        // 真实类别本来就放行（网络/5xx/半截流）时不必看自定义规则
        if (allows(category)) return true
        if (!hasUsableCustomRules) return false
        if (RetryErrorCategory.CUSTOM_MATCH !in effectiveCategories) return false
        return enabledCustomRules.any { it.matches(error) }
    }

    /** 这条错误是被哪一类放行的（界面与日志用）；不放行返回 null。 */
    fun matchedCategory(error: Throwable): RetryErrorCategory? {
        if (!allows(error)) return null
        val category = retryCategoryOf(error)
        if (allows(category)) return category
        return RetryErrorCategory.CUSTOM_MATCH
    }

    /** 是否是「任何错误都试」模式，界面拿它显示额度风险提示 */
    val triesEverything: Boolean get() = mode == RetryScopeMode.ANY_ERROR

    companion object {
        /** 老行为：传输层 + 408/429/5xx + 半截流。 */
        val LEGACY_CATEGORIES: Set<RetryErrorCategory> = setOf(
            RetryErrorCategory.NETWORK,
            RetryErrorCategory.SERVER,
            RetryErrorCategory.EMPTY_RESPONSE,
        )

        /** 用户能在设置页追加勾选的类别（其余类别默认就已包含）。 */
        val OPTIONAL_CATEGORIES: List<RetryErrorCategory> = listOf(
            RetryErrorCategory.AUTH,
            RetryErrorCategory.QUOTA,
            RetryErrorCategory.REQUEST_FORMAT,
            RetryErrorCategory.UNKNOWN,
        )

        val CLASSIFIED = RetryScope()
        val ANY_ERROR = RetryScope(mode = RetryScopeMode.ANY_ERROR)
    }
}

/** 用户选的具体是哪种范围，和 [RetryScope] 一起序列化进设置。 */
@Serializable
enum class RetryScopeMode {
    @SerialName("classified")
    CLASSIFIED,

    @SerialName("custom")
    CUSTOM,

    @SerialName("any_error")
    ANY_ERROR,
}

/**
 * 把一条真实异常折算成 [RetryErrorCategory]。
 *
 * 只读异常的真实类型和 [HttpException.statusCode]；**不从错误文本里正则猜数字**，
 * 猜错一个码就会把余额不足当成服务端抖动无限重发。拿不到码的 [HttpException]
 * （`parseErrorDetail()` 解析 body 得到的那些）一律按 [RetryErrorCategory.REQUEST_FORMAT] 处理。
 *
 * @param httpStatus 调用方额外知道的状态码（测试和 App 的会话上下文会传），真实异常里的码优先。
 */
fun retryCategoryOf(error: Throwable, httpStatus: Int? = null): RetryErrorCategory {
    val status = httpStatusOf(error) ?: httpStatus
    if (error is HttpException) {
        return when {
            status == 401 || status == 403 -> RetryErrorCategory.AUTH
            status == 402 -> RetryErrorCategory.QUOTA
            status == 429 && looksLikeQuotaExhausted(error) -> RetryErrorCategory.QUOTA
            status == 408 || status == 429 || (status != null && status in 500..599) ->
                RetryErrorCategory.SERVER
            status == null -> RetryErrorCategory.REQUEST_FORMAT
            status != null && status in 400..499 -> RetryErrorCategory.REQUEST_FORMAT
            // 1xx/3xx/6xx 这类真实但不是"服务端临时错误"的码：旧 `retryableFailure` 明确不重试，
            // 这里也必须落在默认不放行的类别里，否则 CLASSIFIED 会比旧行为多试一次。
            else -> RetryErrorCategory.UNKNOWN
        }
    }
    return when {
        status == 401 || status == 403 -> RetryErrorCategory.AUTH
        status == 402 -> RetryErrorCategory.QUOTA
        error is WebSessionException -> RetryErrorCategory.AUTH
        error is WebProtocolException -> RetryErrorCategory.REQUEST_FORMAT
        error is StreamTruncatedException -> RetryErrorCategory.EMPTY_RESPONSE
        error is NetworkUnavailableException -> RetryErrorCategory.NETWORK
        error is NetworkRouteChangedException || error is NetworkChangedException ->
            RetryErrorCategory.NETWORK
        error is javax.net.ssl.SSLHandshakeException ||
            error is javax.net.ssl.SSLPeerUnverifiedException -> RetryErrorCategory.UNKNOWN
        error is IOException -> RetryErrorCategory.NETWORK
        status == 408 || status == 429 || (status != null && status in 500..599) ->
            RetryErrorCategory.SERVER
        status != null && status in 400..499 -> RetryErrorCategory.REQUEST_FORMAT
        else -> RetryErrorCategory.UNKNOWN
    }
}

/** 服务端是否在这条错误里明确说了「额度用完了」。只看文本里有没有那几个标记，不做任何推断。 */
private fun looksLikeQuotaExhausted(error: HttpException): Boolean {
    val text = error.message.orEmpty().lowercase()
    return QUOTA_EXHAUSTED_MARKERS.any { it in text }
}

/**
 * 重试前等多久的算法。
 *
 * 默认为 [Exponential]，和加入这个抽象之前的行为完全一致；App 的"网络 → 自动重试"
 * 会显式指定 [Fixed] 或 [RandomRange]，这样设置页上写的秒数就是实际等待的秒数。
 */
sealed interface RetryDelay {
    /** 指数退避：第 n 次重试等 `initialDelay * 2^(n-1)`，并受 `maxDelay` 限制。 */
    data object Exponential : RetryDelay

    /** 每次重试都等 [delay]。 */
    data class Fixed(val delay: Duration) : RetryDelay

    /**
     * 每次重试在 [min]~[max] 里随机取一个整秒值（含两端），不是逐次递增。
     *
     * [random] 可以注入：测试传 `Random(seed)` 就能拿到确定的序列，不靠概率。
     */
    data class RandomRange(
        val min: Duration,
        val max: Duration,
        val random: Random = Random.Default,
    ) : RetryDelay {
        init {
            require(min <= max) { "RandomRange min ($min) must not be greater than max ($max)" }
        }

        /** 取这一次的等待时长；区间退化成一个点（min == max）时就是固定等待。 */
        fun pick(): Duration {
            val low = min.inWholeSeconds.coerceIn(0L, MAX_RANGE_SECONDS).toInt()
            val high = max.inWholeSeconds.coerceIn(0L, MAX_RANGE_SECONDS).toInt()
            if (high <= low) return low.seconds
            return random.nextInt(low, high + 1).seconds
        }
    }

    companion object {
        fun fixed(seconds: Int): Fixed = Fixed(seconds.coerceAtLeast(0).seconds)

        fun randomSeconds(min: Int, max: Int, random: Random = Random.Default): RandomRange {
            val low = minOf(min, max).coerceIn(0, MAX_RANGE_SECONDS.toInt())
            val high = maxOf(min, max).coerceIn(0, MAX_RANGE_SECONDS.toInt())
            return RandomRange(low.seconds, high.seconds, random)
        }
    }
}

/**
 * 运行期的「现在还要不要重试」开关。
 *
 * 用 fun interface 而不是裸 `() -> Boolean`：默认值必须是**同一个对象**（[ALWAYS]），否则
 * [RetryPolicy] 作为 data class 的相等性会被每次新建的 lambda 破坏（`RetryPolicy.NONE ==
 * RetryPolicy(0)` 会变成 false，老测试和老调用点都靠这个）。
 */
fun interface RetryGate {
    fun isEnabled(): Boolean

    companion object {
        val ALWAYS: RetryGate = RetryGate { true }
    }
}

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次，每次重试前按 [delayMode] 等待。
 *
 * 默认只重试 [IOException]，也就是连不上、超时、读到一半断开这类网络故障。
 * [delayMode] 默认是 [RetryDelay.Exponential]（1s、2s、4s……上限 [maxDelay]）；
 * [RetryDelay.Fixed] 和 [RetryDelay.RandomRange] 是用户显式配置的等待时长，
 * 此时不再套用 [initialDelay]/[maxDelay] 的退避逻辑——用户写多少就等多少。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    /** 指数退避的上限。重试次数调大后指数增长会失控，超过这个值就按固定间隔重试。 */
    val maxDelay: Duration = 60.seconds,
    /**
     * 默认只重试真正的传输层故障（连不上、超时、读到一半断开）和服务端临时性 HTTP 错误
     * （408 / 429 / 5xx，且 429 不是「额度用完」）。判定规则只有 [retryableFailure] 一份：
     * 这里、ChatService 的自动恢复、app 侧的阻塞判断都走它，任何一处单独改 is/else 都会分叉。
     *
     * [WebProtocolException] 同样继承 [IOException]，但它表示协议/格式违规（响应不是 JSON、
     * 会话 id 对不上、被挑战页拦住）。同一份请求重发只会再失败一次，所以白名单里明确排除：
     * 否则这类失败会先占满用户设置的重试次数，还会被当成网络故障挂进重连队列。
     * 401/403/402/400/422 也属于「重发结果一样」，[retryableFailure] 里同样排除。
     */
    val retryOn: (Throwable) -> Boolean = { retryableFailure(it) },
    val delayMode: RetryDelay = RetryDelay.Exponential,
    /**
     * 用户选的重试范围。默认 [RetryScope.CLASSIFIED]，也就是老行为。
     *
     * 它不只影响 [retryOn]：`withRetry` 的预算记账（[RetryBudget]）和 App 侧的自动恢复
     * （ChatAutoRecovery）读的是同一个对象，所以"界面勾了鉴权"在请求层和整轮恢复层是同一件事，
     * 不会出现界面说会试、实际不试这种分叉。
     */
    val scope: RetryScope = RetryScope.CLASSIFIED,
    /**
     * 是否一直重试到用户停止。
     *
     * true 时 [maxRetries] 不再限制真正失败的重试次数（离线/换网仍各有独立上限）。
     * 这是用户明确打开的高风险选项：会一直花额度，只有取消生成、关闭总开关或修好配置才会停。
     */
    val unlimitedRetries: Boolean = false,
    /**
     * 用户「自动重试」总开关的**实时**读取口，默认恒为 true（老行为）[RetryGate.ALWAYS]。
     *
     * 只影响"要不要再发一次"，不影响首发的第一次尝试：用户关掉开关时，已经在跑的请求不会被这里打断，
     * 但**已经排上的重试**会在下一个 1 秒检查点立刻放弃。[withRetry] 在每次真正重试之前和退避等待期间
     * 各读一次，所以不需要谁去轮询整个 Settings 对象，也不依赖构造策略时的设置快照。
     *
     * 不放进 [scope]：范围描述"哪些错误值得重试"，开关描述"用户现在还想不想重试"，两者正交。
     */
    val retryGate: RetryGate = RetryGate.ALWAYS,
    /**
     * 整轮共享的一份记账（[RetryBudget]）；null 表示每次 [withRetry] 自己新建一份（老行为）。
     *
     * AgentLoop 的每一步模型请求都会调一次 [withRetry]：不共享时"每步 N 次"会随工具步数放大成
     * N × 步数。调用方（ChatRequestFactory）在一轮开始时建一份放进这里，整轮的每一次步骤重试都从
     * 同一份预算里扣，用户设置的次数就是**整轮**的次数。
     */
    val sharedBudget: RetryBudget? = null,
    /**
     * 可选的网络代际观察者：退避期间网络换了路由（切 Wi-Fi、切 VPN）时提前结束等待。
     *
     * 为 null 时完全按 [delayMode] 等，和不传这个参数之前的行为一致。
     * 它只改变"等多久"，不改变"要不要重试"——是否重试仍然只看 [maxRetries] 和 [retryOn]。
     */
    val networkHandover: NetworkHandover? = null,
) {
    /**
     * 第 [retry] 次重试（从 1 开始）之前要等多久。
     *
     * 单独抽出来的原因：等待时长现在有三种算法，测试和界面文案都要能直接问"到底等多久"，
     * 而不是只能通过跑一遍 [withRetry] 去猜。
     */
    fun delayFor(retry: Int): Duration = when (val mode = delayMode) {
        RetryDelay.Exponential -> {
            val scale = 1 shl (retry - 1).coerceIn(0, 20)
            minOf(initialDelay * scale, maxDelay)
        }

        is RetryDelay.Fixed -> mode.delay
        is RetryDelay.RandomRange -> mode.pick()
    }

    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)
    }
}

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
    /** 这次失败属于哪一类，界面用它显示"在等什么"。[withRetry] 之外的调用方可以留空。 */
    val category: RetryErrorCategory? = null,
    /** 是否无限重试（[RetryPolicy.unlimitedRetries]），界面据此不显示"共 N 次"。 */
    val unlimited: Boolean = false,
)

/**
 * 把真实异常折算成 [FailureFacts]。
 *
 * [withRetry] 和 ChatService 必须用同一个折算：两边各写一套 is/else 一旦分叉，
 * 最难查的就是「界面显示已停止，队列还在后台重发」这种事故。
 */
fun failureFacts(error: Throwable, cancelled: Boolean = false): FailureFacts = FailureFacts(
    cancelled = cancelled,
    notSent = error is NetworkUnavailableException,
    routeRetired = error is NetworkRouteChangedException || error is NetworkChangedException,
    truncatedStream = error is StreamTruncatedException,
    // 真实响应码直接取自 typed HttpException：有了这一条，5xx 才会被归成 SERVER 而不是 TERMINAL，
    // [withRetry] 的 retryOn 放行之后才不会在预算记账那一步又被判成不可重试。
    httpStatus = httpStatusOf(error),
    // 429 里「额度用完」与普通限流共用状态码，只有这一条事实能让两层判断保持一致：
    // [retryableFailure] 不重试，[RecoveryPolicy] 也必须归成 TERMINAL。
    quotaExhausted = error is HttpException && error.statusCode == 429 && looksLikeQuotaExhausted(error),
    transport = error is IOException,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 *
 * 每次尝试开始前记下当时的网络代际；如果 [RetryPolicy.networkHandover] 在退避期间换了路由，
 * 就交给它提前结束等待，而不是干等完整退避。没有网络事件时仍然等 [RetryPolicy.delayFor] 给的时长，
 * 所以"最多重试几次"和"哪些错误重试"的语义都没变。
 *
 * 记账交给 [RetryBudget]：只有真正失败的尝试才消耗 [RetryPolicy.maxRetries]。
 * 预算默认每次调用一份；[RetryPolicy.sharedBudget] 非空时（AgentLoop 的一轮多步）整轮共用一份，
 * 所以"用户设 3 次"在一轮里就是 3 次，不随工具步数放大。
 *
 * 重试资格在每次重试前和退避期间（每 [RETRY_GATE_STEP_MS]）重新读 [RetryPolicy.retryGate]：
 * 关掉总开关后不会再有"已经排上、还在后台等着"的重试。
 * 请求根本没发出去（[NetworkUnavailableException]）和连接被换网作废
 * （[NetworkRouteChangedException] / [NetworkChangedException]）各有一份独立的有界预算——
 * 拿用户的三次机会去扣这两类失败，正是「切一下 Wi-Fi 就再也不重连」的直接原因。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    val handover = policy.networkHandover
    // 一轮内多个步骤共用同一份预算；没有共享时仍然是一次调用一份（老行为）。
    val budget = policy.sharedBudget ?: RetryBudget(policy.maxRetries, policy.scope, policy.unlimitedRetries)
    while (true) {
        // 已取消/已停止的协程不能再被拉起；正常取消在这里直接抛出，不消耗重试次数
        currentCoroutineContext().ensureActive()
        // 这次尝试是在哪个网络代际上开始的，退避时用它判断网络是否已经变了
        val epoch = handover?.state?.value?.epoch ?: 0L
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || !policy.retryOn(error)) {
                throw error
            }
            // 用户关掉总开关之后不再排新的重试；这里读的是实时值，不是构造策略时的设置快照。
            if (!policy.retryGate.isEnabled()) throw error
            val kind = RecoveryPolicy.classify(failureFacts(error), policy.scope)
            if (!budget.record(kind, epoch)) throw error
            val backoff = policy.delayFor(budget.attempt)
            onRetry(
                RetryAttempt(
                    cause = error,
                    retry = budget.attempt,
                    maxRetries = policy.maxRetries,
                    delay = backoff,
                    category = retryCategoryOf(error),
                    unlimited = policy.unlimitedRetries,
                )
            )
            // 网络切换导致的失败不必干等完整退避；没有网络事件时 awaitRetry 同样按 backoff 等满。
            // 等待本身可取消，停止生成不会被这里的等待拖住。
            if (handover == null) {
                // 普通退避切成不超过 1 秒的小段：每段之前重新读一次总开关，
                // 关掉开关时已经排上的等待立刻结束，而不是等满这次退避。
                var remaining = backoff.inWholeMilliseconds
                while (remaining > 0) {
                    if (!policy.retryGate.isEnabled()) throw error
                    val step = remaining.coerceAtMost(RETRY_GATE_STEP_MS)
                    delay(step)
                    remaining -= step
                }
            } else {
                // 有网络观察者时**不切片** awaitRetry：它的代际/稳定期语义依赖"一次调用整段"，
                // 切片会让换网后的每一段都重新睡一遍稳定期，把重连拖长。
                // 改成"每次最多挂起 1 秒再看一眼开关"：退避仍然是同一次 awaitRetry 调用，
                // 总时长和换网唤醒行为与旧实现一致，只是多了一个开关检查点。
                coroutineScope {
                    val wait = async { handover.awaitRetry(epoch, backoff) }
                    while (true) {
                        val finished = withTimeoutOrNull(RETRY_GATE_STEP_MS) { wait.await() } != null
                        if (finished) break
                        if (!policy.retryGate.isEnabled()) {
                            wait.cancel()
                            throw error
                        }
                    }
                }
            }
            if (!policy.retryGate.isEnabled()) throw error
        }
    }
}
