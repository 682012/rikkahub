package me.rerere.rikkahub.ui.floating

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset

internal val portraitPresets = listOf(FloatingCompanionPreset.WHALE_MAID, FloatingCompanionPreset.DRAGON_GIRL,
    FloatingCompanionPreset.SUN_READER, FloatingCompanionPreset.CUSTOM, FloatingCompanionPreset.LOGO)

internal fun portraitAsset(preset: FloatingCompanionPreset): String = "file:///android_asset/companions/" + when (preset) {
    FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.DRAGON -> "dragon-girl.webp"
    FloatingCompanionPreset.SUN_READER -> "sun-reader.webp"
    else -> "whale-maid.webp"
}

@Composable
internal fun portraitName(preset: FloatingCompanionPreset): String = stringResource(when (preset) {
    FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.DRAGON -> R.string.portrait_dragon_girl
    FloatingCompanionPreset.SUN_READER -> R.string.portrait_sun_reader
    else -> R.string.portrait_whale_maid
})
