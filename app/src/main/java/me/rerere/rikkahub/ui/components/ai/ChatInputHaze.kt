package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassStyleScope
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalImmersiveChat

/**
 * 输入区该铺什么效果（纯数据，方便直接跑单元测试）。
 *
 * [immersive] 表示这一刻是沉浸聊天（有壁纸 + 用户没关沉浸）：即使设置里没开模糊，
 * 也给输入区补一层轻量玻璃——否则壁纸上是直接压一块半透明厚底，看起来还是“大黑块”。
 */
internal data class ChatInputHazeSpec(
    val effect: BackgroundEffectType,
    val blurRadiusDp: Float,
    val tintAlpha: Float,
    val immersive: Boolean,
)

/** 没开效果、也不是沉浸模式 → 不加任何效果（返回 null）。 */
internal fun chatInputHazeSpec(displaySetting: DisplaySetting, immersive: Boolean): ChatInputHazeSpec? {
    val userEnabled = displaySetting.enableBlurEffect
    if (!userEnabled && !immersive) return null
    val effect = if (userEnabled) displaySetting.backgroundEffectType else BackgroundEffectType.BLUR
    return ChatInputHazeSpec(
        effect = effect,
        blurRadiusDp = if (immersive) 14f else 12f,
        // 玻璃的着色比 r9 薄：底色本来就被主题层压淡了，再叠一层重的就等于不透明。
        tintAlpha = if (immersive) 0.18f else 0.3f,
        immersive = immersive,
    )
}

/**
 * 输入框容器的背景效果（模糊或玻璃）。
 *
 * 用户自己开了效果就按用户选的来；沉浸模式下没开也会补一层薄玻璃。
 * 开启后容器本身需要保持透明，[containerColor] 由效果负责铺底和着色。
 */
@Composable
internal fun Modifier.chatInputHaze(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: RoundedCornerShape,
): Modifier {
    val spec = chatInputHazeSpec(displaySetting, LocalImmersiveChat.current) ?: return this
    return when (spec.effect) {
        BackgroundEffectType.BLUR -> hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle.Material3 {
                blurRadius(spec.blurRadiusDp.dp)
            },
        )

        BackgroundEffectType.GLASS -> {
            val darkMode = LocalDarkMode.current
            hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = GlassStyle.Material3(
                    containerColor = containerColor,
                    tint = containerColor.copy(alpha = spec.tintAlpha),
                ) {
                    appearanceResponse(dark = darkMode)
                    // Keep background text from competing with the input text.
                    optics(
                        GlassDefaults.optics.copy(
                            blurRadius = OpticalSizeValue.Fixed(16.dp),
                            depth = OpticalSizeValue.Fixed(0.5f),
                        )
                    )
                    shape(shape)
                },
            )
        }
    }
}

// Haze 按系统夜间模式选玻璃的明暗预设，应用内单独设置主题时会选错（深色主题下被提亮发白），
// 所以按应用主题显式写入。深色数值对应库里 internal 的 GlassStyle.regularDark。
private fun GlassStyleScope.appearanceResponse(dark: Boolean) {
    if (dark) {
        specularIntensity(0.38f)
        edgeShadow(Color.Black.copy(alpha = 0.32f))
        ambientResponse(0.08f)
        contrast(0.08f)
        whitePoint(-0.22f)
        chromaMultiplier(1.1f)
    } else {
        specularIntensity(GlassDefaults.specularIntensity)
        edgeShadow(GlassDefaults.edgeShadow)
        ambientResponse(GlassDefaults.ambientResponse)
        contrast(GlassDefaults.contrast)
        whitePoint(GlassDefaults.whitePoint)
        chromaMultiplier(GlassDefaults.chromaMultiplier)
    }
}
