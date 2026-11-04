package me.rerere.rikkahub.data.companion

import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r14：设置页缩略图和浮窗共用的图片来源决策。
 *
 * 这些用例锁住的都是"界面上说的事必须等于浮窗真会画的事"：
 * 六只内置角色没覆盖时要能看见自己的 idle/tap，覆盖优先，坏路径不能伪装成功，
 * 空的自定义不能声称用应用图标（实际是小鲸），LOGO 必须走图标而不是某只角色的立绘。
 */
class CompanionImagePlanR14Test {

    private val six = listOf(
        FloatingCompanionPreset.DRAGON_GIRL to "gpt",
        FloatingCompanionPreset.SUN_READER to "claude",
        FloatingCompanionPreset.WHALE_MAID to "deepseek",
        FloatingCompanionPreset.GEMINI_CAT to "gemini",
        FloatingCompanionPreset.GLM_FOX to "glm",
        FloatingCompanionPreset.QWEN_GIRL to "qwen",
    )

    @Test
    fun `six built-in characters preview their own idle and tap art`() {
        val idleModels = mutableSetOf<String>()
        six.forEach { (preset, id) ->
            val plan = CompanionImageResolution.plan(
                FloatingCompanionSettings(preset = preset),
                resolvedCustomIdle = null,
                resolvedCustomTap = null,
            )
            assertEquals(preset, plan.effectivePreset)
            assertEquals(id, plan.characterId)
            assertFalse(plan.isAppIcon)
            assertFalse(plan.customPresetEmpty)
            assertEquals(CompanionImageOrigin.BUILT_IN, plan.idle.origin)
            assertEquals(CompanionImageOrigin.BUILT_IN, plan.tap.origin)
            assertTrue(plan.idle.model!!.endsWith("companions/six/$id/idle.png"))
            assertTrue(plan.tap.model!!.endsWith("companions/six/$id/tap.png"))
            assertFalse(plan.idle.customBroken)
            assertFalse(plan.tap.customBroken)
            assertFalse(plan.tap.fallsBackToIdle)
            idleModels.add(plan.idle.model!!)
        }
        // 六张不能悄悄退成同一张图（旧代码的 bug 是全都画小鲸）
        assertEquals(6L, idleModels.size.toLong())
    }

    @Test
    fun `legacy aliases keep the portrait they render`() {
        val cat = CompanionImageResolution.plan(FloatingCompanionSettings(preset = FloatingCompanionPreset.CAT), null, null)
        val dragon = CompanionImageResolution.plan(FloatingCompanionSettings(preset = FloatingCompanionPreset.DRAGON), null, null)
        assertEquals(FloatingCompanionPreset.WHALE_MAID, cat.effectivePreset)
        assertEquals("deepseek", cat.characterId)
        assertEquals(FloatingCompanionPreset.DRAGON_GIRL, dragon.effectivePreset)
        assertEquals("gpt", dragon.characterId)
    }

    @Test
    fun `custom idle wins and built-in tap still applies`() {
        val mine = "file:///data/user/0/app/files/floating_companions/companion-mine.png"
        val plan = CompanionImageResolution.plan(
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.DRAGON_GIRL,
                customImagePath = mine,
            ),
            resolvedCustomIdle = mine,
            resolvedCustomTap = null,
        )
        assertEquals(CompanionImageOrigin.CUSTOM, plan.idle.origin)
        assertEquals(mine, plan.idle.model)
        assertTrue(plan.idle.hasCustom)
        assertFalse(plan.idle.customBroken)
        assertEquals(CompanionImageOrigin.BUILT_IN, plan.tap.origin)
        assertTrue(plan.tap.model!!.endsWith("gpt/tap.png"))
    }

    @Test
    fun `custom tap wins over the built-in tap`() {
        val tap = "file:///data/user/0/app/files/floating_companions/companion-tap.png"
        val plan = CompanionImageResolution.plan(
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.WHALE_MAID,
                customTapImagePath = tap,
            ),
            resolvedCustomIdle = null,
            resolvedCustomTap = tap,
        )
        assertEquals(CompanionImageOrigin.BUILT_IN, plan.idle.origin)
        assertEquals(CompanionImageOrigin.CUSTOM, plan.tap.origin)
        assertEquals(tap, plan.tap.model)
        assertFalse(plan.tap.fallsBackToIdle)
    }

    @Test
    fun `unreadable custom image is reported as broken instead of missing`() {
        val gone = "file:///data/user/0/app/files/floating_companions/gone.png"
        val plan = CompanionImageResolution.plan(
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.GEMINI_CAT,
                customImagePath = gone,
                customTapImagePath = gone,
            ),
            resolvedCustomIdle = null,
            resolvedCustomTap = null,
        )
        // 掉回内置图继续能看，但状态必须是"坏了"，不是"没设置"
        assertEquals(CompanionImageOrigin.BUILT_IN, plan.idle.origin)
        assertTrue(plan.idle.hasCustom)
        assertTrue(plan.idle.customBroken)
        assertTrue(plan.tap.customBroken)
        assertTrue(plan.idle.model!!.endsWith("gemini/idle.png"))
        assertTrue(plan.tap.model!!.endsWith("gemini/tap.png"))
    }

    @Test
    fun `empty custom preset falls back to the whale portrait not the app icon`() {
        val plan = CompanionImageResolution.plan(
            FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM),
            resolvedCustomIdle = null,
            resolvedCustomTap = null,
        )
        assertFalse(plan.isAppIcon)
        assertTrue(plan.customPresetEmpty)
        assertEquals(FloatingCompanionPreset.WHALE_MAID, plan.effectivePreset)
        assertEquals(CompanionCatalog.DEEPSEEK.idleAsset, plan.idle.model)
        // 空 CUSTOM 的 effectivePreset 是 WHALE_MAID，既有浮窗也会使用小鲸的点击图。
        assertEquals(CompanionCatalog.DEEPSEEK.tapAsset, plan.tap.model)
        assertEquals(CompanionImageOrigin.BUILT_IN, plan.tap.origin)
        assertFalse(plan.tap.fallsBackToIdle)
    }

    @Test
    fun `logo preset is the app icon and draws no portrait`() {
        val withLeftoverCustom = FloatingCompanionSettings(
            preset = FloatingCompanionPreset.LOGO,
            customImagePath = "file:///data/user/0/app/files/floating_companions/old.png",
        )
        val plan = CompanionImageResolution.plan(withLeftoverCustom, null, null)
        assertTrue(plan.isAppIcon)
        assertEquals(FloatingCompanionPreset.LOGO, plan.effectivePreset)
        assertNull(plan.characterId)
        assertEquals(CompanionImageOrigin.APP_ICON, plan.idle.origin)
        assertNull(plan.idle.model)
        assertNull(plan.tap.model)
        assertTrue(plan.tap.fallsBackToIdle)
        // 绝不能把某只角色的立绘当成图标气泡的预览
        assertNotEquals(CompanionCatalog.DEEPSEEK.idleAsset, plan.idle.model)
    }

    @Test
    fun `tap image equal to idle tells the user nothing changes`() {
        val same = "file:///data/user/0/app/files/floating_companions/same.png"
        val plan = CompanionImageResolution.plan(
            FloatingCompanionSettings(
                preset = FloatingCompanionPreset.QWEN_GIRL,
                customImagePath = same,
                customTapImagePath = same,
            ),
            resolvedCustomIdle = same,
            resolvedCustomTap = same,
        )
        assertEquals(CompanionImageOrigin.CUSTOM, plan.idle.origin)
        assertEquals(CompanionImageOrigin.CUSTOM, plan.tap.origin)
        assertNull(plan.tap.model)
        assertTrue(plan.tap.fallsBackToIdle)
    }

    @Test
    fun `custom preset with its own idle and no tap keeps the same picture`() {
        val mine = "file:///data/user/0/app/files/floating_companions/mine.png"
        val settings = FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM, customImagePath = mine)
        val plan = CompanionImageResolution.plan(settings, mine, null)
        assertEquals(FloatingCompanionPreset.CUSTOM, plan.effectivePreset)
        assertEquals(mine, plan.idle.model)
        assertNull(plan.tap.model)
        assertTrue(plan.tap.fallsBackToIdle)
    }

    @Test
    fun `custom preset with a missing nonempty path falls back without claiming built-in tap`() {
        val gone = "file:///data/user/0/app/files/floating_companions/gone.png"
        val settings = FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM, customImagePath = gone, customTapImagePath = gone)
        val plan = CompanionImageResolution.plan(settings, null, null)
        assertEquals(FloatingCompanionPreset.CUSTOM, plan.effectivePreset)
        assertEquals(CompanionCatalog.DEEPSEEK.idleAsset, plan.idle.model)
        assertTrue(plan.idle.customBroken)
        assertTrue(plan.tap.customBroken)
        assertNull(plan.tap.model)
        assertTrue(plan.tap.fallsBackToIdle)
    }

    @Test
    fun `custom tile reads its own stored image not the active character override`() {
        val own = "file:///data/user/0/app/files/floating_companions/my-custom.png"
        val gpt = "file:///data/user/0/app/files/floating_companions/my-gpt.png"
        val custom = FloatingCompanionSettings(preset = FloatingCompanionPreset.CUSTOM, customImagePath = own)
        val switched = custom.selectPreset(FloatingCompanionPreset.DRAGON_GIRL).copy(customImagePath = gpt)
        assertEquals(gpt, switched.customImagePath)
        assertEquals(own, switched.selectPreset(FloatingCompanionPreset.CUSTOM).customImagePath)
        assertEquals(gpt, switched.selectPreset(FloatingCompanionPreset.CUSTOM).selectPreset(FloatingCompanionPreset.DRAGON_GIRL).customImagePath)
    }

}
