package me.rerere.rikkahub.ui.floating

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionCatalog
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset

/** 横滑选择器的顺序：三只优先角色在前，另外三只随后，最后是"我的图片"和图标。 */
internal val portraitPresets = listOf(
    FloatingCompanionPreset.DRAGON_GIRL,
    FloatingCompanionPreset.SUN_READER,
    FloatingCompanionPreset.WHALE_MAID,
    FloatingCompanionPreset.GEMINI_CAT,
    FloatingCompanionPreset.GLM_FOX,
    FloatingCompanionPreset.QWEN_GIRL,
    FloatingCompanionPreset.CUSTOM,
    FloatingCompanionPreset.LOGO,
)

/**
 * 待机立绘：六只都走 assets 里的原字节 PNG（`companions/six/<id>/idle.png`）。
 *
 * `CUSTOM`/`LOGO` 没有内置角色，返回小鲸的图当兜底，和 r10 的 `else -> whale-maid.webp` 一致。
 * 旧 webp 仍在包内，只是不再被内置角色引用。
 */
internal fun portraitAsset(preset: FloatingCompanionPreset): String =
    CompanionCatalog.portraitAsset(preset)

/**
 * 点击动作图；没有内置角色（`CUSTOM`/`LOGO`）时为 null，浮窗不会拿上一只的图顶上。
 * 用户自己的 `customTapImagePath` 仍然优先，见浮窗里的解析顺序。
 */
internal fun portraitTapAsset(preset: FloatingCompanionPreset): String? =
    CompanionCatalog.portraitTapAsset(preset)

/** 每只角色自己的 8 句固定台词（JSON 原文）；`CUSTOM`/`LOGO` 为空，交给旧资源台词。 */
internal fun portraitTapLines(preset: FloatingCompanionPreset): List<String> =
    CompanionCatalog.portraitTapLines(preset)

/** 角色名：六只的名字来自 JSON 原文，`CUSTOM`/`LOGO` 沿用旧资源。 */
@Composable
internal fun portraitName(preset: FloatingCompanionPreset): String = stringResource(
    when (preset) {
        FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.DRAGON -> R.string.r12_name_gpt
        FloatingCompanionPreset.SUN_READER -> R.string.r12_name_claude
        FloatingCompanionPreset.WHALE_MAID, FloatingCompanionPreset.CAT -> R.string.r12_name_deepseek
        FloatingCompanionPreset.GEMINI_CAT -> R.string.r12_name_gemini
        FloatingCompanionPreset.GLM_FOX -> R.string.r12_name_glm
        FloatingCompanionPreset.QWEN_GIRL -> R.string.r12_name_qwen
        FloatingCompanionPreset.CUSTOM -> R.string.floating_companion_preset_custom
        FloatingCompanionPreset.LOGO -> R.string.floating_companion_preset_logo
    }
)
