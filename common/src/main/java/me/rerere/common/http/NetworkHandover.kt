package me.rerere.common.http

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 网络代际信号。
 *
 * [epoch] 只在「网络真的换代」时 +1，[available] 跟随当前默认网络是否可用。
 * 重试链路只需要看这两个字段：代际变了说明换了网络，可以立刻重试；
 * 代际没变说明还是同一个坏网络，按原有退避继续等。
 */
data class NetworkRouteState(
    val epoch: Long = 0L,
    val available: Boolean = true,
)

/**
 * 网络代际在等待期间发生变化时抛出，供上层（例如主代理的 NetworkAwareProvider）判断
 * 是「换网了，立刻重试」还是「同一个网络，按延迟重试」。
 */
class NetworkChangedException(
    val epoch: Long,
    message: String = "network route generation changed: epoch=$epoch",
) : IOException(message)

/**
 * 等待原语。抽成接口只有一个目的：单测可以注入虚拟时间，不用真的 sleep。
 * 生产实现是 [DefaultRouteWaiter]（无阻塞、无轮询）。
 */
interface RouteWaiter {
    /** 等到可用或 [timeoutMillis] 耗尽；期间可用返回 true，超时返回 false。可取消。 */
    suspend fun awaitAvailable(state: StateFlow<NetworkRouteState>, timeoutMillis: Long): Boolean

    /** 挂起 [millis] 毫秒，可取消。 */
    suspend fun sleep(millis: Long)

    /** Wait for a meaningful route change or the original delay, whichever comes first. */
    suspend fun awaitChange(state: StateFlow<NetworkRouteState>, epoch: Long, millis: Long): Boolean = coroutineScope {
        if (millis <= 0L) return@coroutineScope false
        val changed = async { state.first { it.epoch != epoch || !it.available }; true }
        val timer = async { sleep(millis); false }
        try { select { changed.onAwait { it }; timer.onAwait { it } } }
        finally { changed.cancel(); timer.cancel() }
    }

    /** 单调时钟，毫秒。 */
    fun nowMillis(): Long
}

/** 基于 [TimeSource.Monotonic] 与 `delay` / `StateFlow` 的默认实现。 */
object DefaultRouteWaiter : RouteWaiter {
    private val mark: TimeMark = TimeSource.Monotonic.markNow()

    override suspend fun awaitAvailable(
        state: StateFlow<NetworkRouteState>,
        timeoutMillis: Long,
    ): Boolean {
        if (timeoutMillis <= 0L) return state.value.available
        // 直接等状态变化，不轮询、不 sleep
        return withTimeoutOrNull(timeoutMillis) {
            state.first { it.available }
            true
        } ?: false
    }

    override suspend fun sleep(millis: Long) {
        if (millis > 0L) {
            delay(millis)
        }
    }

    override fun nowMillis(): Long = mark.elapsedNow().inWholeMilliseconds
}

private fun RouteWaiter.elapsedSince(startMillis: Long): Duration {
    return (nowMillis() - startMillis).coerceAtLeast(0L).milliseconds
}

/**
 * 只记录「当前默认网络」的代际状态，不累积历史。
 *
 * - 启动后第一条非空 key 视为初代，不计切换；
 * - 之后 key 变化，或者掉线（key = null）后恢复，代际 +1；
 * - 同一个 key 的重复回调（含连接能力噪声）不改变代际，只更新 [NetworkRouteState.available]。
 */
class NetworkHandover(
    private val waiter: RouteWaiter = DefaultRouteWaiter,
) {
    private val _state = MutableStateFlow(NetworkRouteState())
    val state: StateFlow<NetworkRouteState> = _state.asStateFlow()

    /** 当前代际对应的路由标识；null 表示没有路由或尚未确定。 */
    private var currentKey: String? = null

    /** 是否已经见过第一条非空 key（决定第一条 key 算不算「换代」）。 */
    private var seenAnyKey: Boolean = false

    /**
     * 更新默认网络状态。[key] 为网络标识，[available] 为该网络当前是否可用。
     *
     * 回调可能来自任意线程，也可能是重复的，这里做去重；不保存历史 key 列表。
     */
    @Synchronized
    fun updateRoute(key: String?, available: Boolean) {
        val previous = _state.value
        var nextEpoch = previous.epoch

        if (key == null) {
            // 掉线：只清掉当前 key，等真正恢复时再 +1，避免「丢-恢复」被算成两次换代
            currentKey = null
        } else if (!seenAnyKey) {
            // 初代：第一条非空 key 不计切换
            seenAnyKey = true
            currentKey = key
        } else if (currentKey != key || (!previous.available && available)) {
            currentKey = key
            nextEpoch += 1L
        }

        val next = NetworkRouteState(epoch = nextEpoch, available = available)
        if (next != previous) {
            _state.value = next
        }
    }

    /**
     * 等到网络可用；已可用立即返回 true。
     * 等待有界（默认 120s）且可取消，超时返回 false。
     */
    suspend fun awaitUsable(timeoutMillis: Long = DEFAULT_USABLE_TIMEOUT_MILLIS): Boolean {
        if (_state.value.available) return true
        if (timeoutMillis <= 0L) return false
        return waiter.awaitAvailable(state, timeoutMillis)
    }

    /**
     * 计算本次重试前应该等多久，返回实际等待时长。
     *
     * - 代际已变化且可用：只等一小段稳定期（[STABILIZE_MILLIS]），返回实际等待；
     * - 离线：等恢复，最多等用户配置的这一次间隔（[regularDelay]），恢复得快就提前醒；
     * - 代际未变且可用：按 [regularDelay] 等，期间换网/掉线会提前结束等待。
     *
     * 等待预算一律来自调用方传入的真实重试配置，不再内部暗改成固定的 [OFFLINE_RECOVERY_MILLIS]
     * —— 用户设 30~60 秒就最多等 30~60 秒，只有没传配置（0）时才补一次稳定期。
     * 全程只有一次有界挂起，不循环、不轮询。
     */
    suspend fun awaitRetry(attemptEpoch: Long, regularDelay: Duration): Duration {
        val start = waiter.nowMillis()
        val budget = waitBudget(regularDelay)
        val snapshot = _state.value

        if (snapshot.epoch != attemptEpoch && snapshot.available) {
            // 换网了：给新网络一点点稳定期再重试，避免刚切换就撞上还没就绪的路由
            waiter.sleep(STABILIZE_MILLIS)
            return waiter.elapsedSince(start)
        }

        if (!snapshot.available) {
            // 离线：等恢复，但只等到这次配置的预算用完，恢复后补一小段稳定期
            val recovered = waiter.awaitAvailable(state, budget)
            if (recovered) waiter.sleep(stabilizeRemaining(start, budget))
            return waiter.elapsedSince(start)
        }

        // A VPN can recover while this wait is already in progress. Do not miss that event.
        val changed = waiter.awaitChange(state, attemptEpoch, budget)
        if (changed) {
            if (!state.value.available) {
                // 退避期间掉线了：这不是「换到新网络」，不能把剩下几秒的预算当作已用完、
                // 立刻拿旧配置去撞一次必然失败的请求（那会白白记一次「没发出去」）。
                // 用这次预算剩下的部分等恢复，恢复后再补一个稳定期。
                val remainingBudget = (budget - (waiter.nowMillis() - start)).coerceAtLeast(0L)
                if (waiter.awaitAvailable(state, remainingBudget)) {
                    waiter.sleep(stabilizeRemaining(start, budget))
                }
            } else {
                val remaining = stabilizeRemaining(start, budget)
                val usable = state.value.available || waiter.awaitAvailable(state, remaining)
                if (usable) waiter.sleep(remaining)
            }
        }
        return waiter.elapsedSince(start)
    }

    /**
     * 同一个网络 handle 的内部路由变了（VPN 改内部路由、底层 Wi-Fi/蜂窝换了）。
     * 句柄没变所以 [updateRoute] 看不到换代，这里显式按「换代」处理，让等待中的重试和
     * 在途请求立刻知道要走新路由。
     *
     * 只有当前路由可用时才换代（离线期间交给恢复路径）。重复属性由 [NetworkRouteTracker]
     * 按签名去重；这里不加时间窗，以免 Wi-Fi→蜂窝的连续真实路由变化被误吞。
     */
    @Synchronized
    fun notifyRouteRefreshed() {
        val previous = _state.value
        if (!previous.available) return
        _state.value = previous.copy(epoch = previous.epoch + 1L)
    }

    /** 等待预算：用户真实的这次间隔（设置范围 1–600 秒）；为 0 时至少补一个稳定期，避免忙等风暴。 */
    private fun waitBudget(regularDelay: Duration): Long {
        val configured = regularDelay.inWholeMilliseconds.coerceIn(0L, MAX_CONFIGURED_DELAY_MILLIS)
        return if (configured > 0L) configured else STABILIZE_MILLIS
    }

    /** 恢复之后只补稳定期，且不超过这笔预算剩下的部分。 */
    private fun stabilizeRemaining(startMillis: Long, budgetMillis: Long): Long =
        (budgetMillis - (waiter.nowMillis() - startMillis)).coerceIn(0L, STABILIZE_MILLIS)

    companion object {
        /** [awaitUsable] 默认等待上限。调用方有重试策略时应当自己传更小的预算。 */
        const val DEFAULT_USABLE_TIMEOUT_MILLIS: Long = 120_000L

        /**
         * 代际变化后的稳定期。
         *
         * 换网后新路由基本立刻可用，用户要的是「马上重连」，所以只留 250ms 避开刚切换的
         * 瞬时抖动，不做成 750ms 的额外等待。
         */
        const val STABILIZE_MILLIS: Long = 250L

        /** 设置页允许的最大重连间隔：600 秒。 */
        const val MAX_CONFIGURED_DELAY_MILLIS: Long = 600_000L

        /** 单次离线等待的硬上限，至少覆盖最大用户配置。 */
        const val OFFLINE_RECOVERY_MILLIS: Long = MAX_CONFIGURED_DELAY_MILLIS
    }
}
