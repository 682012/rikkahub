package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.ai.provider.NetworkRouteChangedException
import me.rerere.ai.provider.NetworkUnavailableException
import me.rerere.ai.provider.WebProtocolException
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
 * 真 HTTP 服务端错误里，**只有**这几类还能再试：
 * - 408 请求超时：请求没被处理，重发不会重复计费；
 * - 429 频率限制：等一会儿就恢复；
 * - 500..599 服务端故障：同一份请求换个时刻可能就好了。
 *
 * 其余状态码一律不试。特别是 401/403（要用户重新授权）、402（余额/配额）、400/422（请求本身不合法）
 * 重发一次结果完全一样，只会多花一次往返和一次用户等待。
 */
private fun retryableHttpStatus(status: Int): Boolean =
    status == 408 || status == 429 || status in 500..599

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
fun retryableFailure(error: Throwable): Boolean {
    if (error is HttpException) {
        val status = error.statusCode ?: return false
        if (!retryableHttpStatus(status)) return false
        if (status == 429 && looksLikeQuotaExhausted(error)) return false
        return true
    }
    return error is IOException &&
        error !is WebProtocolException &&
        error !is javax.net.ssl.SSLHandshakeException &&
        error !is javax.net.ssl.SSLPeerUnverifiedException
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
    val budget = RetryBudget(policy.maxRetries)
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
            val kind = RecoveryPolicy.classify(failureFacts(error))
            if (!budget.record(kind, epoch)) throw error
            val backoff = policy.delayFor(budget.attempt)
            onRetry(RetryAttempt(error, budget.attempt, policy.maxRetries, backoff))
            // 网络切换导致的失败不必干等完整退避；没有网络事件时 awaitRetry 同样按 backoff 等满。
            // 等待本身可取消，停止生成不会被这里的等待拖住。
            if (handover == null) delay(backoff) else handover.awaitRetry(epoch, backoff)
        }
    }
}
