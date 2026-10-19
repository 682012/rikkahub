package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyMessageCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `restores only matching canonical legacy text`() {
        val dir = temp.newFolder("msg_cache")
        val payload = "x".repeat(50_000)
        val file = File(dir, "0123456789abcdef0123456789abcdef.txt").apply { writeText(payload) }
        val marker = "\n…[full text: ${file.absolutePath}]"
        val preview = payload.take(2_000) + marker
        assertEquals(payload, LegacyMessageCache(dir).restoreText(preview))
        assertNull(LegacyMessageCache(dir).restoreText("y".repeat(2_000) + marker))
    }

    @Test fun `does not read arbitrary private files`() {
        val secret = temp.newFile("private.txt").apply { writeText("secret") }
        val text = "x".repeat(2_000) + "\n…[full text: ${secret.absolutePath}]"
        assertNull(LegacyMessageCache(temp.newFolder("msg_cache")).restoreText(text))
        assertNull(LegacyMessageCache(temp.newFolder("other")).readPreview(text, 100))
    }
}
