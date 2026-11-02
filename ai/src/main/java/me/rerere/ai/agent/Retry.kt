package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.common.http.NetworkHandover
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 随机区间的硬上限（一天），防止外部传入离谱的区间把秒数溢出成负值。 */
private const val MAX_RANGE_SECONDS = 86_400L

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
    val retryOn: (Throwable) -> Boolean = {
        it is IOException && it !is javax.net.ssl.SSLHandshakeException && it !is javax.net.ssl.SSLPeerUnverifiedException
    },
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
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 *
 * 每次尝试开始前记下当时的网络代际；如果 [RetryPolicy.networkHandover] 在退避期间换了路由，
 * 就交给它提前结束等待，而不是干等完整退避。没有网络事件时仍然等 [RetryPolicy.delayFor] 给的时长，
 * 所以"最多重试几次"和"哪些错误重试"的语义都没变。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    val handover = policy.networkHandover
    var retries = 0
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
            if (error is CancellationException || retries >= policy.maxRetries || !policy.retryOn(error)) {
                throw error
            }
            retries++
            val backoff = policy.delayFor(retries)
            onRetry(RetryAttempt(error, retries, policy.maxRetries, backoff))
            // 网络切换导致的失败不必干等完整退避；没有网络事件时 awaitRetry 同样按 backoff 等满。
            // 等待本身可取消，停止生成不会被这里的等待拖住。
            if (handover == null) delay(backoff) else handover.awaitRetry(epoch, backoff)
        }
    }
}
