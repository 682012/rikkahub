package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.chat.MeshGradientBackground

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }
val LocalWallpaperActive = staticCompositionLocalOf { false }
val LocalPageContainer = staticCompositionLocalOf { Color.Unspecified }
internal val LocalWallpaperTone = staticCompositionLocalOf<Color?> { null }

/**
 * 这一刻是不是“沉浸聊天”：有背景（壁纸/助手渐变）且用户没关掉 [AppearanceSettings.immersiveChat]。
 *
 * 只在这一个地方算：顶部栏、消息、输入区、遮罩全都读它，保证一套观感同时切换；
 * 没有背景时恒为 false，于是没有壁纸的用户看到的就是原来的默认外观。
 */
val LocalImmersiveChat = staticCompositionLocalOf { false }

/**
 * 当前的沉浸对比预算（整层压暗 + 正文薄底）。
 *
 * null 表示不在沉浸模式：所有面板回到 r9 的“必要时加厚到读得清”。
 */
internal val LocalImmersiveContrast = staticCompositionLocalOf<ImmersiveContrastPlan?> { null }

/** Backgrounds stay opaque on image failure; only successful images unlock translucent pages. */
@Composable
fun AppAppearanceHost(settings: Settings, content: @Composable () -> Unit) {
    val appearance = settings.displaySetting.appearance.normalized()
    val source = appearance.wallpaper.takeIf { appearance.immersiveWallpaper && appearance.wallpaperOpacity > 0f }
    var loaded by remember(source) { mutableStateOf(false) }
    var tone by remember(source) { mutableStateOf<Color?>(null) }
    var sample by remember(source) { mutableStateOf<WallpaperSample?>(null) }
    val active = source != null && loaded
    val scheme = MaterialTheme.colorScheme.withWallpaperTone(if (active) tone else null, appearance.wallpaperAdaptiveTint)
    val immersive = appearance.immersiveChatActive(active)
    val plan = immersiveContrast(scheme, appearance, if (active) sample else null, immersive)
    CompositionLocalProvider(
        LocalImmersiveChat provides immersive,
        LocalImmersiveContrast provides plan,
    ) {
        Box(Modifier.fillMaxSize().background(scheme.background)) {
            // 遮罩要读同一份预算，所以壁纸和内容一起放在这两个 local 里面。
            if (source != null) WallpaperBackdrop(
                source = source,
                appearance = appearance,
                onLoaded = { loaded = it },
                onTone = { tone = it },
                onSample = { sample = it },
            )
            MaterialExpressiveTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides scheme.onBackground,
                    LocalAppearance provides appearance,
                    LocalWallpaperActive provides active,
                    LocalWallpaperTone provides if (active) tone else null,
                    LocalPageContainer provides if (active) Color.Transparent else scheme.background,
                    content = content,
                )
            }
        }
    }
}

/** A restored conversation uses its own assistant, never the currently selected drawer assistant. */
@Composable
fun ChatAppearanceHost(settings: Settings, assistant: Assistant, content: @Composable () -> Unit) {
    val appearance = settings.displaySetting.appearance.normalized()
    val inheritedActive = LocalWallpaperActive.current
    val inheritedTone = LocalWallpaperTone.current
    val inheritedPlan = LocalImmersiveContrast.current
    val assistantImage = assistant.background?.takeIf { it.isNotBlank() }
    val image = if (assistant.useGradientBackground) null else assistantImage
        ?: appearance.wallpaper.takeIf { !appearance.immersiveWallpaper }
    val opacity = if (assistantImage != null) assistant.backgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        else appearance.wallpaperOpacity
    val visibleImage = image.takeIf { opacity > 0f }
    var loaded by remember(visibleImage) { mutableStateOf(false) }
    var localTone by remember(visibleImage) { mutableStateOf<Color?>(null) }
    var localSample by remember(visibleImage) { mutableStateOf<WallpaperSample?>(null) }
    val hasLocal = assistant.useGradientBackground || (visibleImage != null && loaded)
    val active = hasLocal || inheritedActive
    val tone = if (hasLocal) localTone else inheritedTone
    val scheme = if (hasLocal) MaterialTheme.colorScheme.withWallpaperTone(tone, appearance.wallpaperAdaptiveTint) else MaterialTheme.colorScheme
    val immersive = appearance.immersiveChatActive(active)
    val plan = when {
        !immersive -> null
        // 会话自己带背景：按它自己的采样算，别借外层的预算。
        hasLocal -> immersiveContrast(
            scheme = scheme,
            appearance = appearance,
            sample = if (assistant.useGradientBackground) null else localSample,
            immersive = true,
        )
        // 只继承外层壁纸：直接用外层的采样结果，不要再按最坏情况重算一遍。
        else -> inheritedPlan ?: immersiveContrast(scheme, appearance, null, true)
    }
    CompositionLocalProvider(
        LocalImmersiveChat provides immersive,
        LocalImmersiveContrast provides plan,
    ) {
        Box(Modifier.fillMaxSize().then(if (hasLocal) Modifier.background(scheme.background) else Modifier)) {
            if (assistant.useGradientBackground) {
                MeshGradientBackground(Modifier.fillMaxSize())
                WallpaperProtection(appearance)
            } else if (visibleImage != null) {
                WallpaperBackdrop(
                    source = visibleImage,
                    appearance = appearance.copy(wallpaperOpacity = opacity),
                    onLoaded = { loaded = it },
                    onTone = { localTone = it },
                    onSample = { localSample = it },
                )
            }
            MaterialExpressiveTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides scheme.onBackground,
                    LocalAppearance provides appearance,
                    LocalWallpaperActive provides active,
                    LocalWallpaperTone provides tone,
                    LocalPageContainer provides if (active) Color.Transparent else scheme.background,
                    content = content,
                )
            }
        }
    }
}

/**
 * 算这一刻的沉浸预算。
 *
 * 壁纸采样（主色调 + 明暗两端）在壁纸加载完成时才有；没采到就别假装安全，
 * 按最坏情况（纯黑 + 纯白）保护，宁可厚一点。
 */
@Composable
private fun immersiveContrast(
    scheme: ColorScheme,
    appearance: AppearanceSettings,
    sample: WallpaperSample?,
    immersive: Boolean,
): ImmersiveContrastPlan? {
    if (!immersive) return null
    val backdrops = remember(sample) {
        sample?.luminances()?.map { grayFromLuminance(it) }
            ?: listOf(grayFromLuminance(0f), grayFromLuminance(1f))
    }
    val surface = remember(appearance.surfaceOpacity) { immersiveTextBase(appearance.surfaceOpacity) }
    // 会把消息、标题栏、输入区铺成面板的那几种基色：预算要照顾最不利的那一块。
    val panelBases = remember(scheme) {
        listOf(scheme.surfaceContainerLow, scheme.surfaceContainerHigh, scheme.primaryContainer)
    }
    return remember(scheme.background, scheme.onSurface, backdrops, surface, panelBases, appearance.wallpaperScrim) {
        immersiveContrastPlan(
            background = scheme.background,
            foreground = scheme.onSurface,
            beneath = backdrops,
            requestedSurface = surface,
            requestedScrim = appearance.wallpaperScrim,
            panelBases = panelBases,
        )
    }
}

/** Minimum contrast over both extremes of any underlying wallpaper, rather than a fixed lucky tint. */
fun surfaceContrast(background: Color, foreground: Color): Float {
    val light = maxOf(background.luminance(), foreground.luminance())
    val dark = minOf(background.luminance(), foreground.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}

fun safeSurfaceOpacity(background: Color, foreground: Color, requested: Float): Float {
    val initial = requested.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    fun safe(alpha: Float) = listOf(Color.Black, Color.White).all { beneath ->
        surfaceContrast(background.copy(alpha = alpha).compositeOver(beneath), foreground) >= 4.5f
    }
    if (safe(initial)) return initial
    if (!safe(1f)) return 1f // A low-contrast custom theme must not be made worse by translucency.
    var low = initial
    var high = 1f
    repeat(16) { val mid = (low + high) / 2f; if (safe(mid)) high = mid else low = mid }
    return high
}

/**
 * 面板色（气泡、输入区、抽屉、浮岛标题栏）。
 *
 * - 没有壁纸：原色返回，观感和原来一模一样；
 * - 有壁纸 + 沉浸：只铺一层薄底，缺的对比度由壁纸整层压暗补（见 [ImmersivePanels]）；
 * - 有壁纸 + 关了沉浸：回到 r9 的“必要时加厚到读得清”。
 */
@Composable
fun appearanceSurface(color: Color, strong: Boolean = false): Color {
    if (!LocalWallpaperActive.current) return color
    val foreground = contentColorFor(color.copy(alpha = 1f)).let {
        if (it == Color.Unspecified) MaterialTheme.colorScheme.onSurface else it
    }
    val plan = LocalImmersiveContrast.current
    if (LocalImmersiveChat.current && plan != null) {
        val requested = if (strong) plan.glass else plan.surface
        val ceiling = if (strong) ImmersiveContrastPlan.MAX_GLASS else ImmersivePanels.MAX_SURFACE
        // 调用方自己带了更薄的 alpha（bubbleOpacity）就别替它加厚。
        val upper = if (color.alpha < 1f) minOf(ceiling, color.alpha) else ceiling
        val alpha = immersiveSurfaceAlpha(color, foreground, plan, minOf(requested, upper), upper)
        return color.copy(alpha = alpha)
    }
    val requestedOpacity = LocalAppearance.current.surfaceOpacity
    val desired = if (strong) (requestedOpacity + 0.06f).coerceAtMost(1f) else requestedOpacity
    val alpha = remember(color, foreground, desired) { safeSurfaceOpacity(color, foreground, desired) }
    return color.copy(alpha = alpha)
}

/**
 * 消息外框（包住一整条消息的那块大圆角卡片）。
 *
 * 沉浸模式下直接返回透明：壁纸可以一路铺到消息之间，只剩正文自己的薄底。
 * 没壁纸或关了沉浸时还是 r9 的面板色，没有任何变化。
 */
@Composable
fun appearanceFrame(color: Color): Color =
    if (LocalImmersiveChat.current) Color.Transparent else appearanceSurface(color)
