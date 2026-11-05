package me.rerere.rikkahub.data.organization

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ImageOrganizerBackgroundTest {
    @get:Rule val temp = TemporaryFolder()
    private fun image(id: String) = ImageOrganizerItem(id, id, "/tmp/$id", id, 10, ImageSource.UPLOAD)
    @Test fun autoNeverExpandsEmptyScopeAndBoundsThreshold() {
        assertFalse(ImageAutoConfig(enabled = true, sources = emptySet()).coerce().enabled)
        assertEquals(2, ImageAutoConfig(minNewImages = 15, maxPerRun = 2).coerce().minNewImages)
        assertEquals(emptyList<String>(), selectAutoCandidates(listOf(image("a")), ImageAutoConfig()))
    }
    @Test fun exclusionPrecedesBatchCap() {
        val all = (1..30).map { image("$it") }
        val selected = selectAutoCandidates(all, ImageAutoConfig(enabled = true, maxPerRun = 3), (1..20).map { "$it" }.toSet())
        assertEquals(listOf("21", "22", "23"), selected)
    }
    @Test fun queueStorePersistsAndReportsCorruption() {
        val file = temp.newFile(); file.delete()
        val store = ImageOrganizerJobStore(file)
        val job = ImageOrganizerJob("j", pendingIds = listOf("a", "b"), total = 2)
        assertTrue(store.write(job)); assertEquals(job, store.read())
        val updated = job.record("a", false).record("b", true).settled()
        assertEquals(ImageJobStatus.DONE, updated.status); assertEquals(2, updated.progress().done)
        file.writeText("broken"); assertNull(store.read()); assertNotNull(store.lastFailure)
    }
    @Test fun planFiltersInventedAndDuplicateIds() {
        val result = ImagePlanParser.parse("""{"buckets":[{"name":"风景","items":["a","a","fake"]},{"name":"截图","items":["a","b"]}]}""", setOf("a", "b"))
        assertNotNull(result.plan); assertEquals(2, result.plan!!.totalItems); assertEquals(1, result.droppedItems)
    }
}
