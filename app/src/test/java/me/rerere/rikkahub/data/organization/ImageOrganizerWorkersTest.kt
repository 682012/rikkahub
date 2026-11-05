package me.rerere.rikkahub.data.organization

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageOrganizerWorkersTest {
    @Test fun limitsAreBounded() {
        assertEquals(3, ImageOrganizerState().workers)
        assertEquals(1, effectiveImageWorkers(-1))
        assertEquals(16, effectiveImageWorkers(999))
        assertEquals(2, effectiveImageWorkers(8, 2))
        assertEquals(8, effectiveImageWorkers(8, 0))
    }
    @Test fun settingsSurviveRecreation() = withFile { file ->
        assertTrue(ImageOrganizerWorkerStore(file).write(9))
        assertEquals(9, ImageOrganizerWorkerStore(file).read())
        assertTrue(ImageOrganizerWorkerStore(file).write(99))
        assertEquals(16, ImageOrganizerWorkerStore(file).read())
    }
    @Test fun missingOrDamagedSettingsUseDefault() = withFile { file ->
        assertEquals(3, ImageOrganizerWorkerStore(file).read())
        file.writeText("{broken")
        assertEquals(3, ImageOrganizerWorkerStore(file).read())
    }
    @Test fun failedWriteDoesNotOverwriteExistingFile() = withFile { file ->
        val parent = file.parentFile
        val blocker = File(parent, "not-directory").apply { writeText("unchanged") }
        assertFalse(ImageOrganizerWorkerStore(File(blocker, "workers.json")).write(7))
        assertEquals("unchanged", blocker.readText())
    }
    @Test fun workersAreActuallyParallelAndVisitEachItemOnce() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        forEachImageWorker((0 until 15).toList(), 3) { item ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            delay(20)
            seen += item
            active.decrementAndGet()
        }
        assertEquals(3, peak.get())
        assertEquals((0 until 15).toList(), seen.sorted())
    }
    @Test fun cancelledWorkersDoNotStartRemainingImages() = runBlocking {
        val started = AtomicInteger(); val first = CompletableDeferred<Unit>()
        val job = launch {
            forEachImageWorker((0 until 1000).toList(), 3) {
                started.incrementAndGet(); first.complete(Unit); delay(5000)
            }
        }
        first.await(); job.cancelAndJoin()
        assertTrue(started.get() in 1..3)
    }
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("r19-workers").toFile()
        try { block(File(dir, "workers.json")) } finally { dir.deleteRecursively() }
    }
}
