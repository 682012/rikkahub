package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ConversationAutoFileSettingsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root

    @Test
    fun `defaults to disabled when nothing is stored`() {
        val store = ConversationAutoFileSettingsStore(root)
        val settings = store.read(Uuid.random())

        assertFalse(settings.enabled)
        assertEquals(ConversationAutoFileSettings.DEFAULT_IDLE_HOURS, settings.idleHours)
        assertTrue(settings.collapseFamilies)
    }

    @Test
    fun `round trips settings and clamps idle hours`() = runTest {
        val store = ConversationAutoFileSettingsStore(root)
        val assistantId = Uuid.random()

        store.write(assistantId, ConversationAutoFileSettings(enabled = true, idleHours = 5, collapseFamilies = false))
        val stored = store.read(assistantId)
        assertTrue(stored.enabled)
        assertEquals(ConversationAutoFileSettings.MIN_IDLE_HOURS, stored.idleHours)
        assertFalse(stored.collapseFamilies)

        store.write(assistantId, ConversationAutoFileSettings(enabled = true, idleHours = 9_999))
        assertEquals(ConversationAutoFileSettings.MAX_IDLE_HOURS, store.read(assistantId).idleHours)
    }

    @Test
    fun `corrupt file falls back to disabled and keeps a backup`() {
        val store = ConversationAutoFileSettingsStore(root)
        val assistantId = Uuid.random()
        val file = store.fileFor(assistantId)
        file.parentFile.mkdirs()
        file.writeText("{ not json")

        val settings = store.read(assistantId)

        assertFalse(settings.enabled)
        val backups = file.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".bak") }
        assertEquals(1, backups.size)
        assertFalse(file.exists())
    }

    @Test
    fun `file owned by another assistant is not trusted`() {
        val store = ConversationAutoFileSettingsStore(root)
        val assistantId = Uuid.random()
        val other = Uuid.random()
        val file = store.fileFor(assistantId)
        file.parentFile.mkdirs()
        file.writeText(
            """{"version":1,"assistantId":"$other","settings":{"enabled":true,"idleHours":30,"collapseFamilies":true}}""",
        )

        val settings = store.read(assistantId)

        assertFalse(settings.enabled)
        assertTrue(file.parentFile.listFiles().orEmpty().any { it.name.endsWith(".bak") })
    }

    @Test
    fun `enabled assistant ids only lists enabled entries`() = runTest {
        val store = ConversationAutoFileSettingsStore(root)
        val on = Uuid.random()
        val off = Uuid.random()

        store.write(on, ConversationAutoFileSettings(enabled = true))
        store.write(off, ConversationAutoFileSettings(enabled = false))

        assertEquals(listOf(on), store.enabledAssistantIds())
    }
}
