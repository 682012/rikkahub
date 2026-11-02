package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ModelRuntimePersistenceTest {
    @Test fun `old model JSON without runtime overrides keeps legacy defaults`() {
        val old = JsonInstant.decodeFromString<Model>("""{"modelId":"legacy","contextLength":32768}""")
        assertEquals(32768, old.contextLength)
        assertNull(old.autoCompressEnabled)
        assertNull(old.autoCompressThresholdPercent)
        assertNull(old.maxConcurrentRequests)
    }

    @Test fun `backup shaped Settings JSON roundtrip retains per model fields and IDs`() {
        val a = Model(modelId = "same", autoCompressEnabled = true,
            autoCompressThresholdPercent = 65, maxConcurrentRequests = 2, contextLength = 128_000)
        val b = Model(modelId = "same", autoCompressEnabled = false,
            autoCompressThresholdPercent = null, maxConcurrentRequests = 5, contextLength = 200_000)
        val c = Model(modelId = "inherit")
        val original = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(a, b, c))))
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(original))
        assertEquals(original.providers, restored.providers)
        assertEquals(listOf(a.id, b.id, c.id), restored.providers.single().models.map { it.id })
        assertNotEquals(a.id, b.id)
    }

    @Test fun `new override values are written inside existing providers key without new global keys`() {
        val a = Model(modelId = "a", maxConcurrentRequests = 2, autoCompressEnabled = false)
        val provider = ProviderSetting.OpenAI(models = listOf(a))
        val original = Settings(providers = listOf(provider))
        val prefs = mutablePreferencesOf().apply { putSettings(original) }
        val keys = prefs.asMap().keys
        val edited = original.copy(providers = listOf(provider.copy(models = listOf(a.copy(
            contextLength = 500_000, maxConcurrentRequests = 4, autoCompressThresholdPercent = 70)))))
        prefs.putSettings(edited, persisted = original)
        assertEquals(keys, prefs.asMap().keys)
        assertEquals(edited.providers, prefs.toSettings().providers)
    }

    @Test fun `null roundtrips really reset inheritance instead of persisting prior overrides`() {
        val a = Model(autoCompressEnabled = false, autoCompressThresholdPercent = 75, maxConcurrentRequests = 1)
        val reset = a.copy(autoCompressEnabled = null, autoCompressThresholdPercent = null, maxConcurrentRequests = null)
        assertEquals(reset, JsonInstant.decodeFromString<Model>(JsonInstant.encodeToString(reset)))
    }
}
