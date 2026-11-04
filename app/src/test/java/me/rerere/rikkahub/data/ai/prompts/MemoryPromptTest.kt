package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `profile is injected in full with its version and other files only appear in the listing`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        val listing = prompt.block("memory_listing")
        assertEquals("/topics/food.md | What the user eats and avoids | aliases: diet, 饮食", listing)
        // 清单不带日期: 日期一跨天就变, 会让系统提示之后的全部前缀缓存失效
        assertFalse(listing.contains("updated"))
        assertFalse(listing.contains(MemoryFile.PROFILE_PATH))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
    fun `the listing order only depends on the files, not on the order they are passed in`() {
        val areas = MemoryFile(
            path = "/areas/house.md",
            content = "---\nname: house\ndescription: The house hunt\n---\n\n- [stated] Looking in Hangzhou\n",
            updatedAt = 0,
        )
        val books = MemoryFile(
            path = "/topics/books.md",
            content = "---\nname: books\ndescription: What the user reads\n---\n\n- [stated] Reads sci-fi\n",
            updatedAt = 0,
        )

        // 同一批文件换个顺序传进来, 系统提示必须逐字节相同, 否则前缀缓存白失一次
        val first = buildMemoryPrompt(listOf(profile, food, areas, books))
        val second = buildMemoryPrompt(listOf(books, profile, areas, food))

        assertEquals(first, second)
        assertTrue(first.indexOf("/topics/books.md") < first.indexOf("/topics/food.md"))
    }

    @Test
    fun `a path that appears twice is listed once`() {
        val prompt = buildMemoryPrompt(listOf(profile, food, food))

        // 只看清单: 全文里的 /topics/food.md 还会出现在 <memory_format> 的示例里
        assertEquals(1, prompt.block("memory_listing").split("/topics/food.md").size - 1)
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }
}
