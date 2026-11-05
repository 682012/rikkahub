package me.rerere.rikkahub.data.organization

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun effectiveImageWorkers(requested: Int, modelLimit: Int? = null): Int {
    val count = ImageOrganizerState.coerceWorkers(requested)
    return if (modelLimit != null && modelLimit > 0) minOf(count, modelLimit) else count
}

/** Fixed number of workers, no coroutine/image buffer allocated for each waiting item. */
internal suspend fun <T> forEachImageWorker(items: List<T>, workers: Int, action: suspend (T) -> Unit) = coroutineScope {
    val cursor = AtomicInteger(0)
    repeat(minOf(ImageOrganizerState.coerceWorkers(workers), items.size)) {
        launch(Dispatchers.IO) {
            while (true) {
                coroutineContext.ensureActive()
                val index = cursor.getAndIncrement()
                if (index >= items.size) break
                action(items[index])
            }
        }
    }
}
