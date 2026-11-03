package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/** Backgrounds stay opaque on image failure; only successful images unlock translucent pages. */
@Composable
fun AppAppearanceHost(settings: Settings, content: @Composable () -> Unit) {
    val appearance = settings.displaySetting.appearance.normalized()
    val source = appearance.wallpaper.takeIf { appearance.immersiveWallpaper && appearance.wallpaperOpacity > 0f }
    var loaded by remember(source) { mutableStateOf(false) }
    var tone by remember(source) { mutableStateOf<Color?>(null) }
    val active = source != null && loaded
    val scheme = MaterialTheme.colorScheme.withWallpaperTone(if (active) tone else null, appearance.wallpaperAdaptiveTint)
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        if (source != null) WallpaperBackdrop(source, appearance, onLoaded = { loaded = it }, onTone = { tone = it })
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

/** A restored conversation uses its own assistant, never the currently selected drawer assistant. */
@Composable
fun ChatAppearanceHost(settings: Settings, assistant: Assistant, content: @Composable () -> Unit) {
    val appearance = settings.displaySetting.appearance.normalized()
    val inheritedActive = LocalWallpaperActive.current
    val inheritedTone = LocalWallpaperTone.current
    val assistantImage = assistant.background?.takeIf { it.isNotBlank() }
    val image = if (assistant.useGradientBackground) null else assistantImage
        ?: appearance.wallpaper.takeIf { !appearance.immersiveWallpaper }
    val opacity = if (assistantImage != null) assistant.backgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        else appearance.wallpaperOpacity
    val visibleImage = image.takeIf { opacity > 0f }
    var loaded by remember(visibleImage) { mutableStateOf(false) }
    var localTone by remember(visibleImage) { mutableStateOf<Color?>(null) }
    val hasLocal = assistant.useGradientBackground || (visibleImage != null && loaded)
    val active = hasLocal || inheritedActive
    val tone = if (hasLocal) localTone else inheritedTone
    val scheme = if (hasLocal) MaterialTheme.colorScheme.withWallpaperTone(tone, appearance.wallpaperAdaptiveTint) else MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().then(if (hasLocal) Modifier.background(scheme.background) else Modifier)) {
        if (assistant.useGradientBackground) {
            MeshGradientBackground(Modifier.fillMaxSize())
            WallpaperProtection(appearance)
        } else if (visibleImage != null) {
            WallpaperBackdrop(visibleImage, appearance.copy(wallpaperOpacity = opacity), onLoaded = { loaded = it }, onTone = { localTone = it })
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

@Composable
fun appearanceSurface(color: Color, strong: Boolean = false): Color {
    if (!LocalWallpaperActive.current) return color
    val requested = LocalAppearance.current.surfaceOpacity
    val desired = if (strong) (requested + 0.06f).coerceAtMost(1f) else requested
    val foreground = contentColorFor(color.copy(alpha = 1f)).let {
        if (it == Color.Unspecified) MaterialTheme.colorScheme.onSurface else it
    }
    val alpha = remember(color, foreground, desired) { safeSurfaceOpacity(color, foreground, desired) }
    return color.copy(alpha = alpha)
}
