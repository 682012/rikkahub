package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Admission and wake-up version are protected by the same lock; cancellation never consumes a permit. */
internal class SubagentPermitPool {
    private val mutex = Mutex()
    private var active = 0
    private val version = MutableStateFlow(0L)

    suspend fun acquire(limit: Int) {
        require(limit > 0)
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = mutex.withLock {
                if (active < limit) { active++; null } else version.value
            } ?: return
            version.first { it != observed }
        }
    }

    suspend fun release() = mutex.withLock {
        check(active > 0)
        active--
        version.value++
    }
}
