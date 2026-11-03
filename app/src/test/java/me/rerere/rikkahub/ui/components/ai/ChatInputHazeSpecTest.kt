package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入区的玻璃效果：用户没开效果时，r9 就是一块半透明厚底；沉浸模式要补一层薄玻璃，
 * 但绝不能改掉用户自己选的效果类型。
 */
class ChatInputHazeSpecTest {

    private fun setting(enabled: Boolean, type: BackgroundEffectType) =
        DisplaySetting(enableBlurEffect = enabled, backgroundEffectType = type)

    @Test
    fun `no effect and no immersion keeps the plain container`() {
        assertNull(chatInputHazeSpec(setting(false, BackgroundEffectType.BLUR), immersive = false))
        assertNull(chatInputHazeSpec(setting(false, BackgroundEffectType.GLASS), immersive = false))
    }

    @Test
    fun `immersive adds a light glass even when the user never enabled blur`() {
        val spec = chatInputHazeSpec(setting(false, BackgroundEffectType.BLUR), immersive = true)
            ?: error("沉浸模式下必须给输入区一层薄玻璃")
        assertEquals(BackgroundEffectType.BLUR, spec.effect)
        assertTrue("模糊半径 ${spec.blurRadiusDp} 太重", spec.blurRadiusDp <= 16f)
        assertTrue("着色 ${spec.tintAlpha} 太厚", spec.tintAlpha <= 0.3f)
        assertTrue(spec.immersive)
    }

    @Test
    fun `user selected effect is kept, only the tint gets thinner`() {
        val glass = chatInputHazeSpec(setting(true, BackgroundEffectType.GLASS), immersive = true)
            ?: error("用户开了玻璃就必须是玻璃")
        assertEquals(BackgroundEffectType.GLASS, glass.effect)
        val r9Glass = chatInputHazeSpec(setting(true, BackgroundEffectType.GLASS), immersive = false)!!
        assertTrue("沉浸模式的玻璃着色不能比 r9 更厚", glass.tintAlpha < r9Glass.tintAlpha)

        val blur = chatInputHazeSpec(setting(true, BackgroundEffectType.BLUR), immersive = true)!!
        assertEquals(BackgroundEffectType.BLUR, blur.effect)
    }
}
