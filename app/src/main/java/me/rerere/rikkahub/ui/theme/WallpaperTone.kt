package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** A small dominant-hue sample, never a retained full-resolution bitmap. */
internal fun wallpaperTone(pixels: IntArray): Color? {
    val weight = FloatArray(12)
    val red = FloatArray(12); val green = FloatArray(12); val blue = FloatArray(12)
    for (pixel in pixels) {
        val a = ((pixel ushr 24) and 255) / 255f
        if (a < .2f) continue
        val r = ((pixel ushr 16) and 255) / 255f
        val g = ((pixel ushr 8) and 255) / 255f
        val b = (pixel and 255) / 255f
        val high = max(r, max(g, b)); val low = min(r, min(g, b)); val delta = high-low
        val light = (high+low)/2
        if (delta < .045f || light < .055f || light > .95f) continue
        val hue = when(high) { r -> ((g-b)/delta + if(g<b) 6 else 0); g -> (b-r)/delta+2; else -> (r-g)/delta+4 } / 6
        val bucket = (hue*12).toInt().coerceIn(0,11)
        val w = a * (.15f + delta)
        weight[bucket]+=w; red[bucket]+=r*w; green[bucket]+=g*w; blue[bucket]+=b*w
    }
    val index = weight.indices.maxByOrNull { weight[it] } ?: return null
    val w = weight[index]; if(w==0f) return null
    return Color(red[index]/w, green[index]/w, blue[index]/w)
}

/** Keep the original luminance budget so dark/light/AMOLED stay the user's choice. */
internal fun harmonizeSurface(base: Color, tone: Color, amount: Float = .28f): Color {
    val target = base.copy(alpha=1f).luminance()
    if (target < .0001f) return base
    var tinted = lerp(base.copy(alpha=1f), tone.copy(alpha=1f), amount.coerceIn(0f,.4f))
    val actual = tinted.luminance()
    val end = if (actual > target) Color.Black else Color.White
    var low=0f; var high=1f
    repeat(18) {
        val mid=(low+high)/2
        val value=lerp(tinted,end,mid).luminance()
        if ((actual>target && value>target) || (actual<target && value<target)) low=mid else high=mid
    }
    tinted=lerp(tinted,end,high)
    return tinted.copy(alpha=base.alpha)
}

internal fun ColorScheme.withWallpaperTone(tone: Color?, enabled: Boolean): ColorScheme {
    if (!enabled || tone==null) return this
    fun match(c: Color)=harmonizeSurface(c,tone)
    return copy(
        background=match(background), surface=match(surface), surfaceDim=match(surfaceDim), surfaceBright=match(surfaceBright),
        surfaceContainerLowest=match(surfaceContainerLowest), surfaceContainerLow=match(surfaceContainerLow),
        surfaceContainer=match(surfaceContainer), surfaceContainerHigh=match(surfaceContainerHigh), surfaceContainerHighest=match(surfaceContainerHighest),
        primaryContainer=match(primaryContainer), secondaryContainer=match(secondaryContainer), tertiaryContainer=match(tertiaryContainer),
        outline=match(outline), outlineVariant=match(outlineVariant),
    )
}
