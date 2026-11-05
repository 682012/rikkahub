package me.rerere.rikkahub.data.organization.autofile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAutoFilePromptTest {

    @Test
    fun `prompt lists at most sixteen items and no thinking or attachments`() {
        val items = (1..20).map { index ->
            AutoFileClassifyItem(
                id = Uuid.random(),
                title = "会话$index",
                excerpt = "开头 $index\n…\n结尾 $index",
            )
        }
        val folders = listOf(AutoFileFolderRef(Uuid.random(), "工作"))

        val prompt = ConversationAutoFilePrompt.build(items, folders)

        assertTrue(prompt.contains("工作"))
        assertEquals(16, Regex("- id: ").findAll(prompt).count())
        assertFalse(prompt.contains("会话17"))
        // 明确要求严格 JSON、带四个字段
        assertTrue(prompt.contains("\"assignments\""))
        assertTrue(prompt.contains("\"isNew\""))
        assertTrue(prompt.contains("\"confidence\""))
        assertTrue(prompt.contains("不要代码块"))
    }
}
