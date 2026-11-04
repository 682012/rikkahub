package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MultiImageSendSettingsTest {
    @Test fun `default starts above one image`() {
        val policy = MultiImageSendSettings()
        assertTrue(policy.enabled)
        assertEquals(1, policy.batchSize)
        assertFalse(policy.shouldBatch(0, true))
        assertFalse(policy.shouldBatch(1, true))
        assertTrue(policy.shouldBatch(2, true))
    }
    @Test fun `threshold is strict and independent of batch size`() {
        val policy = MultiImageSendSettings(threshold = 4, batchSize = 3)
        assertFalse(policy.shouldBatch(4, true))
        assertTrue(policy.shouldBatch(5, true))
        assertEquals(3, policy.normalized().batchSize)
    }
    @Test fun `disabled and non vision leave normal send path`() {
        assertFalse(MultiImageSendSettings(enabled = false).shouldBatch(10, true))
        assertFalse(MultiImageSendSettings().shouldBatch(10, false))
    }
    @Test fun `zero threshold includes single image but not empty message`() {
        val policy = MultiImageSendSettings(threshold = 0)
        assertTrue(policy.shouldBatch(1, true))
        assertFalse(policy.shouldBatch(0, true))
    }
    @Test fun `invalid imported values are clamped`() {
        assertEquals(MultiImageSendSettings(threshold = 0, batchSize = 1),
            MultiImageSendSettings(threshold = -1, batchSize = 0).normalized())
        assertEquals(MultiImageSendSettings(threshold = 100, batchSize = 100),
            MultiImageSendSettings(threshold = Int.MAX_VALUE, batchSize = Int.MAX_VALUE).normalized())
    }
    @Test fun `legacy settings use defaults and values round trip`() {
        assertEquals(MultiImageSendSettings(), Json.decodeFromString<MultiImageSendSettings>("{}"))
        val policy = MultiImageSendSettings(enabled = false, threshold = 7, batchSize = 4)
        assertEquals(policy, Json.decodeFromString<MultiImageSendSettings>(Json.encodeToString(policy)))
    }
}
