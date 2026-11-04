package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.ui.floating.FloatingCompanionFiles
import me.rerere.rikkahub.ui.floating.portraitAsset
import me.rerere.rikkahub.ui.floating.portraitName
import me.rerere.rikkahub.ui.floating.portraitPresets

/** 缩略卡尺寸：竖图按 3:4 左右留白，脸不用被裁。 */
private val TileWidth = 132.dp
private val TileArtHeight = 176.dp

/** 立绘选择器的测试标签，设备测试按角色名定位。 */
internal object PortraitSelectorTestTags {
    const val ROOT = "portrait-selector"

    fun tile(preset: FloatingCompanionPreset): String = "portrait-selector-" + presetTagName(preset)
}

/** 存储键风格的小写名，CAT/DRAGON 跟它们现在实际显示的角色同名。 */
internal fun presetTagName(preset: FloatingCompanionPreset): String = when (preset) {
    FloatingCompanionPreset.WHALE_MAID, FloatingCompanionPreset.CAT -> "whale_maid"
    FloatingCompanionPreset.DRAGON_GIRL, FloatingCompanionPreset.DRAGON -> "dragon_girl"
    FloatingCompanionPreset.SUN_READER -> "sun_reader"
    FloatingCompanionPreset.GEMINI_CAT -> "gemini_cat"
    FloatingCompanionPreset.GLM_FOX -> "glm_fox"
    FloatingCompanionPreset.QWEN_GIRL -> "qwen_girl"
    FloatingCompanionPreset.CUSTOM -> "custom"
    FloatingCompanionPreset.LOGO -> "logo"
}

/** 旧 CAT/DRAGON 存档仍在，高亮落到它们现在映射的角色上；新三只各自独立。 */
internal fun highlightPreset(preset: FloatingCompanionPreset): FloatingCompanionPreset = when (preset) {
    FloatingCompanionPreset.CAT -> FloatingCompanionPreset.WHALE_MAID
    FloatingCompanionPreset.DRAGON -> FloatingCompanionPreset.DRAGON_GIRL
    FloatingCompanionPreset.DRAGON_GIRL,
    FloatingCompanionPreset.SUN_READER,
    FloatingCompanionPreset.WHALE_MAID,
    FloatingCompanionPreset.GEMINI_CAT,
    FloatingCompanionPreset.GLM_FOX,
    FloatingCompanionPreset.QWEN_GIRL,
    FloatingCompanionPreset.CUSTOM,
    FloatingCompanionPreset.LOGO,
        -> preset
}

/**
 * 大比例透明立绘的横向缩略卡：三个角色 + 自定义图片 + 应用图标。
 *
 * 立绘保持原图比例、不裁成圆头像，选中用主题色描边。
 */
@Composable
internal fun PortraitCompanionSelector(
    companion: FloatingCompanionSettings,
    onSelect: (FloatingCompanionPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val customImage = remember(companion.customImagePath) {
        FloatingCompanionFiles.resolve(context, companion.customImagePath)
    }
    val current = highlightPreset(companion.preset)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(PortraitSelectorTestTags.ROOT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        portraitPresets.forEach { preset ->
            PortraitPresetTile(
                preset = preset,
                selected = preset == current,
                customImage = customImage,
                onClick = { onSelect(preset) },
            )
        }
    }
}

@Composable
private fun PortraitPresetTile(
    preset: FloatingCompanionPreset,
    selected: Boolean,
    customImage: String?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(TileWidth)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainerHigh)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag(PortraitSelectorTestTags.tile(preset)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TileArtHeight),
            contentAlignment = Alignment.Center,
        ) {
            PortraitTileArt(preset = preset, customImage = customImage)
        }
        Text(
            text = portraitLabel(preset),
            style = MaterialTheme.typography.labelLarge,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun PortraitTileArt(preset: FloatingCompanionPreset, customImage: String?) {
    when (preset) {
        FloatingCompanionPreset.LOGO -> androidx.compose.foundation.Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )

        FloatingCompanionPreset.CUSTOM -> if (customImage != null) {
            PortraitArtImage(model = customImage)
        } else {
            PortraitGlyph(stringResource(R.string.floating_companion_image_none))
        }

        else -> PortraitArtImage(model = portraitAsset(preset))
    }
}

/**
 * 立绘原图直接按 Fit 缩进来画，保留透明边缘，不裁脸。
 * asset 还没生成时 Coil 会报错，这里退化成普通占位图标，不会白屏、也不会画回旧猫。
 */
@Composable
private fun PortraitArtImage(model: Any) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            onSuccess = { failed = false },
        )
        if (failed) PortraitGlyph(null)
    }
}

@Composable
private fun PortraitGlyph(hint: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = HugeIcons.Image02,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun portraitLabel(preset: FloatingCompanionPreset): String = when (preset) {
    FloatingCompanionPreset.LOGO -> stringResource(R.string.floating_companion_preset_logo)
    FloatingCompanionPreset.CUSTOM -> stringResource(R.string.floating_companion_preset_custom)
    else -> portraitName(preset)
}
