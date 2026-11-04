package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionOverrides
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设编辑草稿的规则（纯 JVM，不碰 Compose/Android）。
 *
 * 这些断言都是用户能看见的行为：超长不截断只报错、保存只写打开时那一格、
 * 切角色不串写、恢复内置不动名字/图片/模型/开关。
 */
class CompanionPersonaDraftTest {
    private val limit = FloatingCompanionLimits.MAX_PERSONA_LENGTH

    private fun settings() = FloatingCompanionSettings(
        preset = FloatingCompanionPreset.WHALE_MAID,
        name = "小鲸",
        aiPersonaPrompt = "旧的自写人设",
        aiModelMode = CompanionModelMode.FIXED,
        animationEnabled = false,
        sizeDp = 90,
        characterOverrides = mapOf(
            "dragon_girl" to FloatingCompanionOverrides(
                name = "小白龙",
                aiPersonaPrompt = "龙的自写人设",
                customImagePath = "/data/user/0/app/files/floating_companions/dragon.png",
            ),
        ),
    )

    @Test
    fun `length is utf16 not characters`() {
        assertEquals(2, CompanionPersonaDraft.length("🐉"))
        assertEquals(3, CompanionPersonaDraft.length("a🐉"))
        assertEquals(0, CompanionPersonaDraft.length(""))
    }

    @Test
    fun `limit is the settings limit`() {
        assertEquals(16_000, CompanionPersonaDraft.limit)
        assertEquals(FloatingCompanionLimits.MAX_PERSONA_LENGTH, CompanionPersonaDraft.limit)
    }

    @Test
    fun `exactly at the limit can be saved`() {
        val draft = "角".repeat(limit)
        assertEquals(limit, draft.length)
        assertFalse(CompanionPersonaDraft.isOverLimit(draft))
        assertTrue(CompanionPersonaDraft.canSave(draft))
        assertEquals(
            draft,
            CompanionPersonaDraft.apply(settings(), "whale_maid", draft).aiPersonaPrompt,
        )
    }

    @Test
    fun `over the limit is rejected without truncating the draft`() {
        val companion = settings()
        val draft = "龙".repeat(limit + 1)
        assertTrue(CompanionPersonaDraft.isOverLimit(draft))
        assertFalse(CompanionPersonaDraft.canSave(draft))
        assertEquals(1, CompanionPersonaDraft.overBy(draft))

        val error = runCatching { CompanionPersonaDraft.apply(companion, "whale_maid", draft) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        // 草稿原样还在，设置一格都没动。
        assertEquals(limit + 1, draft.length)
        assertEquals("旧的自写人设", companion.aiPersonaPrompt)
    }

    @Test
    fun `blank draft is not a save`() {
        assertFalse(CompanionPersonaDraft.canSave(""))
        assertFalse(CompanionPersonaDraft.canSave("   \n  "))
    }

    @Test
    fun `saving to an inactive owner leaves every other field alone`() {
        val saved = CompanionPersonaDraft.apply(settings(), "dragon_girl", "换掉的龙人设")

        assertEquals("换掉的龙人设", saved.characterOverrides.getValue("dragon_girl").aiPersonaPrompt)
        // 另一只（当前选中）的自写人设没被顺手改掉。
        assertEquals("旧的自写人设", saved.aiPersonaPrompt)
        assertEquals("小鲸", saved.name)
        assertEquals(CompanionModelMode.FIXED, saved.aiModelMode)
        assertEquals(false, saved.animationEnabled)
        assertEquals(90, saved.sizeDp)
        // 同一格里的名字和图片也还在。
        assertEquals("小白龙", saved.characterOverrides.getValue("dragon_girl").name)
        assertEquals(
            "/data/user/0/app/files/floating_companions/dragon.png",
            saved.characterOverrides.getValue("dragon_girl").customImagePath,
        )
    }

    @Test
    fun `saving never leaks into the character selected later`() {
        // 编辑器打开时是小鲸，中途切到小白龙再保存：只能写到打开时的小鲸那一格。
        val whileEditing = settings().selectPreset(FloatingCompanionPreset.DRAGON_GIRL)
        val saved = CompanionPersonaDraft.apply(whileEditing, "whale_maid", "鲸的新人设")

        assertEquals("鲸的新人设", saved.characterOverrides.getValue("whale_maid").aiPersonaPrompt)
        assertEquals("龙的自写人设", saved.aiPersonaPrompt) // 小白龙那一格没被串写
        assertEquals("龙的自写人设", saved.characterOverrides.getValue("dragon_girl").aiPersonaPrompt)
    }

    @Test
    fun `saved draft keeps its own whitespace`() {
        val draft = "  第一行\n\n最后一行  \n"
        val saved = CompanionPersonaDraft.apply(settings(), "whale_maid", draft)
        assertEquals(draft, saved.aiPersonaPrompt)
        // 读设置时也不许 trim / take。
        assertEquals(draft, saved.normalized().aiPersonaPrompt)
        assertEquals(draft, saved.normalized().personaOverrideOf("whale_maid"))
    }

    @Test
    fun `restore clears only that persona`() {
        val saved = CompanionPersonaDraft.clear(settings(), "dragon_girl")

        assertEquals("", saved.characterOverrides.getValue("dragon_girl").aiPersonaPrompt)
        assertEquals("小白龙", saved.characterOverrides.getValue("dragon_girl").name)
        assertEquals(
            "/data/user/0/app/files/floating_companions/dragon.png",
            saved.characterOverrides.getValue("dragon_girl").customImagePath,
        )
        assertEquals("旧的自写人设", saved.aiPersonaPrompt)
        assertEquals(90, saved.sizeDp)
    }

    @Test
    fun `restoring the active character clears the legacy field only`() {
        val saved = CompanionPersonaDraft.clear(settings(), "whale_maid")
        assertEquals("", saved.aiPersonaPrompt)
        assertEquals("小鲸", saved.name)
        assertNull(saved.customImagePath)
        assertEquals("龙的自写人设", saved.characterOverrides.getValue("dragon_girl").aiPersonaPrompt)
    }

    @Test
    fun `dirty check follows the draft`() {
        assertFalse(CompanionPersonaDraft.isDirty("同样的全文", "同样的全文"))
        assertTrue(CompanionPersonaDraft.isDirty("同样的全文", "同样的全文 "))
        assertTrue(CompanionPersonaDraft.isDirty("", "新写的"))
    }
}
