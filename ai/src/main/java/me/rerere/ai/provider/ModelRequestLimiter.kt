package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicBoolean

/** 每模型并发上限的硬上界，导入或设置里超出的值一律收敛到它 */
const val MAX_MODEL_CONCURRENT_REQUESTS: Int = 64

/**
 * 把设置里的并发上限收敛成可用值：null 表示不额外限制，其它值落到 1..[MAX_MODEL_CONCURRENT_REQUESTS]。
 *
 * 设置可能是手改或旧备份导入的，0、负数、超大值都按最近合法值处理，不能让它把请求堵死或放穿。
 */
fun normalizeRequestLimit(value: Int?): Int? = when {
    value == null -> null
    value < 1 -> 1
    value > MAX_MODEL_CONCURRENT_REQUESTS -> MAX_MODEL_CONCURRENT_REQUESTS
    else -> value
}

/**
 * 单个模型的一次 API 请求名额，[release] 可重复调用。
 *
 * 不用 suspend 释放：它要在 Flow 的 finally、取消回调和异常路径上都能立刻生效。
 */
class ModelRequestLease internal constructor(private val onRelease: () -> Unit) {
    private val released = AtomicBoolean(false)

    val isReleased: Boolean
        get() = released.get()

    fun release() {
        if (released.compareAndSet(false, true)) {
            onRelease()
        }
    }
}

/**
 * 每模型限额的实时解析：Settings 里同 id 的最新模型优先于调用方手里的旧快照。
 *
 * 会话、任务手里的 Model 是发起时的快照，用户之后改了并发上限也只改 Settings 里的副本。
 * 按 id 找回最新模型再取上限，旧快照上的请求也能遵守新限额；已经删掉的模型继续用它自带的值。
 */
class LiveModelLimits(
    private val baseLimitOf: (Model) -> Int? = { it.maxConcurrentRequests },
) {
    @Volatile
    private var models: Map<Uuid, Model> = emptyMap()

    fun update(updated: List<Model>) {
        models = updated.associateBy { it.id }
    }

    fun limitFor(model: Model): Int? = baseLimitOf(models[model.id] ?: model)
}

/**
 * 每模型 API 并发闸门，按 [Model.id] 分桶：同一个 [Model.id] 共享名额，不同 id 互不影响。
 *
 * 限额每次准入时从 [limitOf] 现取，不在构造时缓存：
 * - 限额调小后不会再有新的请求进入，已运行的请求不被打断，等在途请求结束自然收敛到新上限；
 * - 限额调大后靠 [notifyChanged] 唤醒排队者重新判断，不会另起一套名额池导致双池超发。
 *
 * 只有一次 API 请求占用名额，调用方绝不能在工具执行、重试退避期间持有 [ModelRequestLease]。
 */
class ModelRequestLimiter(
    private val limitOf: (Model) -> Int? = { it.maxConcurrentRequests },
) {
    private val lock = Any()
    private val entries = mutableMapOf<Uuid, Entry>()

    private class Entry {
        var inFlight: Int = 0
        var waiters: Int = 0
        val order = mutableListOf<Any>()
        var gates: MutableList<CompletableDeferred<Unit>> = mutableListOf()

        /** 取走当前所有等待者的唤醒牌，调用方在锁外 complete，避免在锁内跑到别的协程里 */
        fun takeGates(): List<CompletableDeferred<Unit>> {
            if (gates.isEmpty()) return emptyList()
            val taken = gates
            gates = mutableListOf()
            return taken
        }
    }

    /** 取得一个名额；限额已满时挂起等待，等待过程可被取消且不占名额 */
    suspend fun acquire(model: Model): ModelRequestLease {
        val key = model.id
        val ticket = Any()
        var enqueued = false
        var acquired = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var pendingGate: CompletableDeferred<Unit>? = null
                var wake = emptyList<CompletableDeferred<Unit>>()
                synchronized(lock) {
                    val entry = entries.getOrPut(key) { Entry() }
                    if (!enqueued) {
                        entry.order.add(ticket)
                        enqueued = true
                    }
                    val limit = normalizeRequestLimit(limitOf(model))
                    // Keep a stable FIFO ticket across wakeups: a busy agent must not
                    // reacquire immediately and starve an already queued conversation.
                    if (entry.order.firstOrNull() === ticket && (limit == null || entry.inFlight < limit)) {
                        entry.order.removeAt(0)
                        entry.inFlight++
                        acquired = true
                        wake = entry.takeGates()
                    } else {
                        pendingGate = CompletableDeferred<Unit>().also {
                            entry.gates.add(it)
                            entry.waiters++
                        }
                    }
                }
                wake.forEach { it.complete(Unit) }
                if (acquired) return ModelRequestLease { release(key) }
                val gate = requireNotNull(pendingGate)
                try {
                    gate.await()
                } finally {
                    leaveQueue(key, gate)
                }
            }
        } finally {
            if (!acquired && enqueued) {
                val wake = synchronized(lock) {
                    val entry = entries[key]
                    if (entry == null) emptyList() else {
                        entry.order.remove(ticket)
                        val pending = entry.takeGates()
                        evictIfIdle(key, entry)
                        pending
                    }
                }
                wake.forEach { it.complete(Unit) }
            }
        }
    }

    /** 包住一次 API 请求：无论返回、抛异常还是被取消都会归还名额 */
    suspend fun <R> withPermit(model: Model, block: suspend () -> R): R {
        val lease = acquire(model)
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            lease.release()
        }
    }

    /**
     * 限额可能变了：唤醒所有排队者按最新限额重新判断。
     *
     * 只唤醒不打断，已运行的请求继续跑；如果限额没变大，被唤醒的请求会重新排回队列。
     */
    fun notifyChanged() {
        val pending: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            pending = entries.values.flatMap { it.takeGates() }
        }
        pending.forEach { it.complete(Unit) }
    }

    /** 当前在途请求数，仅用于诊断和测试 */
    fun inFlightCount(modelId: Uuid): Int = synchronized(lock) {
        entries[modelId]?.inFlight ?: 0
    }

    /** 当前排队请求数，仅用于诊断和测试 */
    fun waitingCount(modelId: Uuid): Int = synchronized(lock) {
        entries[modelId]?.order?.size ?: 0
    }

    /** 当前保留的桶数量。空桶会被回收，晚到请求不会复用过期限额 */
    fun trackedModelCount(): Int = synchronized(lock) {
        entries.size
    }

    private fun release(key: Uuid) {
        val pending: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            val entry = entries[key] ?: return
            if (entry.inFlight > 0) entry.inFlight--
            pending = entry.takeGates()
            evictIfIdle(key, entry)
        }
        pending.forEach { it.complete(Unit) }
    }

    private fun leaveQueue(key: Uuid, gate: CompletableDeferred<Unit>) {
        synchronized(lock) {
            val entry = entries[key] ?: return
            entry.gates.remove(gate)
            if (entry.waiters > 0) entry.waiters--
            evictIfIdle(key, entry)
        }
    }

    private fun evictIfIdle(key: Uuid, entry: Entry) {
        if (entry.inFlight == 0 && entry.waiters == 0 && entry.gates.isEmpty() && entry.order.isEmpty()) {
            entries.remove(key)
        }
    }
}
