package me.rerere.rikkahub.data.companion

import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r12 六小只角色清单的定向回归。
 *
 * 只断言用户/提示词真正依赖的东西：名字与台词齐全、旧枚举仍然指向原来那只、
 * 立绘路径真的落在包内 `companions/six/<id>/` 下，`CUSTOM`/`LOGO` 不冒充某个内置角色。
 * 不读 assets 字节（JVM 单测没有 Android 资源），路径规则由这里固定。
 */
class CatalogR12Test {

    private val expectedOrder = listOf("gpt", "claude", "deepseek", "gemini", "glm", "qwen")

    @Test
    fun `six characters are exposed once in a stable order`() {
        assertEquals(6, CompanionCatalog.all.size)
        assertEquals(expectedOrder, CompanionCatalog.all.map { it.id })
        assertEquals(6, CompanionCatalog.all.map { it.displayName }.toSet().size)
        CompanionCatalog.all.forEach { profile ->
            assertSame(profile, CompanionCatalog.byId(profile.id))
        }
        assertNull(CompanionCatalog.byId("marin"))
        assertNull(CompanionCatalog.byId(""))
    }

    @Test
    fun `every character has a name a persona and eight unique tap lines`() {
        CompanionCatalog.all.forEach { profile ->
            assertTrue(profile.id, profile.displayName.isNotBlank())
            assertTrue(profile.id, profile.shortDescription.isNotBlank())
            // 完整个性提示，不是旧的一行英文
            assertTrue(profile.id, profile.persona.length > 100)
            assertEquals(profile.id, 8, profile.tapLines.size)
            assertEquals(profile.id, 8, profile.tapLines.toSet().size)
            assertTrue(profile.id, profile.tapLines.all { it.isNotBlank() })
            assertTrue(profile.id, profile.tapLines.all { it.length <= 80 })
        }
    }

    @Test
    fun `legacy presets keep the character they were approved as`() {
        assertEquals(CompanionCatalog.DEEPSEEK, CompanionCatalog.profile(FloatingCompanionPreset.CAT))
        assertEquals(CompanionCatalog.DEEPSEEK, CompanionCatalog.profile(FloatingCompanionPreset.WHALE_MAID))
        assertEquals(CompanionCatalog.GPT, CompanionCatalog.profile(FloatingCompanionPreset.DRAGON))
        assertEquals(CompanionCatalog.GPT, CompanionCatalog.profile(FloatingCompanionPreset.DRAGON_GIRL))
        assertEquals(CompanionCatalog.CLAUDE, CompanionCatalog.profile(FloatingCompanionPreset.SUN_READER))
        assertEquals(CompanionCatalog.GEMINI, CompanionCatalog.profile(FloatingCompanionPreset.GEMINI_CAT))
        assertEquals(CompanionCatalog.GLM, CompanionCatalog.profile(FloatingCompanionPreset.GLM_FOX))
        assertEquals(CompanionCatalog.QWEN, CompanionCatalog.profile(FloatingCompanionPreset.QWEN_GIRL))
        assertNull(CompanionCatalog.profile(FloatingCompanionPreset.CUSTOM))
        assertNull(CompanionCatalog.profile(FloatingCompanionPreset.LOGO))
    }

    @Test
    fun `portraits live under the packaged six folder as png`() {
        CompanionCatalog.all.forEach { profile ->
            assertEquals(
                "file:///android_asset/companions/six/${profile.id}/idle.png",
                profile.idleAsset,
            )
            assertEquals("file:///android_asset/companions/six/${profile.id}/tap.png", profile.tapAsset)
            assertEquals(profile.idleAsset, CompanionCatalog.portraitAsset(presetOf(profile)))
            assertEquals(profile.tapAsset, CompanionCatalog.portraitTapAsset(presetOf(profile)))
            assertEquals(profile.tapLines, CompanionCatalog.portraitTapLines(presetOf(profile)))
        }
    }

    @Test
    fun `custom and logo fall back to the old whale portrait without pretending to be a character`() {
        assertNull(CompanionCatalog.portraitDisplayName(FloatingCompanionPreset.CUSTOM))
        assertNull(CompanionCatalog.portraitDisplayName(FloatingCompanionPreset.LOGO))
        assertNull(CompanionCatalog.portraitTapAsset(FloatingCompanionPreset.CUSTOM))
        assertNull(CompanionCatalog.portraitTapAsset(FloatingCompanionPreset.LOGO))
        // r10 的自定义图坏掉时退回 whale-maid.webp，这里必须还是同一只小鲸的图，不留空白
        assertEquals(CompanionCatalog.DEEPSEEK.idleAsset, CompanionCatalog.portraitAsset(FloatingCompanionPreset.CUSTOM))
        assertEquals(CompanionCatalog.DEEPSEEK.idleAsset, CompanionCatalog.portraitAsset(FloatingCompanionPreset.LOGO))
        assertTrue(CompanionCatalog.portraitTapLines(FloatingCompanionPreset.CUSTOM).isEmpty())
        assertTrue(CompanionCatalog.portraitTapLines(FloatingCompanionPreset.LOGO).isEmpty())
    }

    @Test
    fun `prompt personas and portrait names share one source`() {
        for (preset in FloatingCompanionPreset.entries) {
            assertTrue("$preset 没有默认名", CompanionPersonas.defaultName(preset).isNotBlank())
            assertTrue("$preset 没有内置人设", CompanionPersonas.builtIn(preset).isNotBlank())
            assertTrue(CompanionCatalog.portraitAsset(preset).isNotBlank())
            // 界面名和提示词兜底名必须是同一份，避免"界面叫小鲸、提示词还叫 Marin"
            val profile = CompanionCatalog.profile(preset)
            if (profile != null) {
                assertEquals(profile.displayName, CompanionPersonas.defaultName(preset))
                assertEquals(profile.persona, CompanionPersonas.builtIn(preset))
                assertEquals(profile.displayName, CompanionCatalog.portraitDisplayName(preset))
            }
        }
    }

    private fun presetOf(profile: CompanionProfile): FloatingCompanionPreset =
        when (profile.id) {
            "gpt" -> FloatingCompanionPreset.DRAGON_GIRL
            "claude" -> FloatingCompanionPreset.SUN_READER
            "deepseek" -> FloatingCompanionPreset.WHALE_MAID
            "gemini" -> FloatingCompanionPreset.GEMINI_CAT
            "glm" -> FloatingCompanionPreset.GLM_FOX
            "qwen" -> FloatingCompanionPreset.QWEN_GIRL
            else -> error("unexpected catalog id: ${profile.id}")
        }
}
