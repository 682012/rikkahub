package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 子代理准入许可池：真 FIFO 排队，`synchronized` 只护极短的账本操作，等待者挂在 continuation 上。
 *
 * 三条必须同时成立的规矩：
 *
 * 1. **严格 FIFO**：等待者进 [waiters] 队列，许可只有一个来源——`active < limit` 时按队首发放。
 *    没有版本号轮询，也不会出现"后到的插队"。
 * 2. **取消不吞许可、也不借许可**：还在排队时被取消，直接从队列里摘掉（没占过许可）；
 *    许可能**已经发出去**（`granted`）而等待者在恢复执行前被取消时，许可原地转给下一个排队者，
 *    没人排队才把计数还回账本——既不会把许可弄丢（后续全部饿死），也不会让计数变负。
 * 3. **limit 跟着最近一次 [acquire] 的参数走**：动态调整（内存等级、设置变更）时立刻按新值放行排在前面的等待者。
 */
internal class SubagentPermitPool {
    private val lock = Any()

    /** 已经发出（含刚授给等待者、但对方还没恢复执行）的许可数。 */
    private var active = 0

    /** 最近一次 [acquire] 指定的上限。 */
    private var limit = 0

    /** FIFO 等待队列。 */
    private val waiters = ArrayDeque<Waiter>()

    private class Waiter(val continuation: CancellableContinuation<Unit>) {
        /** 许可已经记在这个等待者名下（[active] 里已经算过它）。 */
        var granted = false
    }

    /**
     * 申请一个许可，最多允许 [limit] 个同时持有（取最近一次调用的值）。
     *
     * 排队期间取消会抛 [kotlinx.coroutines.CancellationException]，且不消耗许可。
     */
    suspend fun acquire(limit: Int) {
        require(limit > 0) { "limit must be positive, but was $limit" }
        currentCoroutineContext().ensureActive()
        // 新的 limit 可能已经放开排在前面的等待者，先按 FIFO 放行它们，再决定自己要不要排队。
        val handoff = synchronized(lock) {
            this.limit = limit
            drainLocked()
        }
        deliver(handoff)
        suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(continuation)
            // 先装取消钩子：许可可能在下面这一小段里就发出去了，也可能立刻被取消。
            continuation.invokeOnCancellation { onCancelled(waiter) }
            // 「取许可」和「排队」必须在同一个临界区里完成，否则 release 会从空队列旁路过，
            // 许可就没人接（等待者永久挂住）。
            val grantedNow = synchronized(lock) {
                if (waiters.isEmpty() && active < this.limit) {
                    active++
                    waiter.granted = true
                    true
                } else {
                    waiters.addLast(waiter)
                    false
                }
            }
            if (!grantedNow) {
                // 装钩子和真正入队之间被取消：钩子跑的那一刻它还不在队列里，谁也不会把它摘掉，
                // 池子饱和时这个死等待者（连带它整条协程栈）就一直躺在队列里。这里补摘一次。
                if (!continuation.isActive) onCancelled(waiter)
                return@suspendCancellableCoroutine
            }
            if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) else onCancelled(waiter)
        }
    }

    /**
     * 归还一个许可并唤醒队首等待者。
     *
     * 没有挂起点：即使调用方已经被取消（例如 `withContext(NonCancellable)` 里），账本也照样回到正确状态，
     * 不会因为"取消时跳过 release"把许可永远占住，也不会多减成负数。
     */
    suspend fun release() {
        val handoff = synchronized(lock) {
            check(active > 0) { "release() without a matching acquire()" }
            active--
            drainLocked()
        }
        deliver(handoff)
    }

    /**
     * 把当前能发的许可按 FIFO 授给队首。**必须持有 [lock]**。
     *
     * 返回需要在锁外恢复的等待者（恢复动作不能占着锁做）。
     */
    private fun drainLocked(): List<Waiter> {
        if (active >= limit) return emptyList()
        var granted: MutableList<Waiter>? = null
        while (active < limit) {
            if (waiters.isEmpty()) break
            val next = waiters.removeFirst()
            // 已被取消但钩子还没跑完的等待者：跳过它，许可不算给它
            if (!next.continuation.isActive) continue
            next.granted = true
            active++
            val list = granted ?: ArrayList<Waiter>(2).also { granted = it }
            list.add(next)
        }
        return granted ?: emptyList()
    }

    /** 在锁外恢复等待者；对方已经取消就把许可再让出去。 */
    private fun deliver(pending: List<Waiter>) {
        var current = pending
        while (current.isNotEmpty()) {
            val failed = ArrayList<Waiter>(0)
            for (waiter in current) {
                // 恢复前先看它是否还活着：已经取消的等待者不能吃掉许可
                if (waiter.continuation.isActive) {
                    waiter.continuation.resumeWith(Result.success(Unit))
                } else {
                    failed.add(waiter)
                }
            }
            if (failed.isEmpty()) return
            current = synchronized(lock) {
                for (waiter in failed) {
                    // 许可发出后等待者才被取消：把许可还回账本，别让它跟着一起消失
                    if (waiter.granted) {
                        waiter.granted = false
                        active--
                    }
                }
                drainLocked()
            }
        }
    }

    /** 等待者被取消：排队中就摘掉，已经拿到许可就转给下一个人（或还回账本）。 */
    private fun onCancelled(waiter: Waiter) {
        val handoff = synchronized(lock) {
            if (waiters.remove(waiter)) return
            if (!waiter.granted) return
            waiter.granted = false
            active--
            drainLocked()
        }
        deliver(handoff)
    }
}
