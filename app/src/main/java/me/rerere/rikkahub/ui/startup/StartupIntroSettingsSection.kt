package me.rerere.rikkahub.ui.startup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.startup.StartupFit
import me.rerere.rikkahub.startup.StartupImportFailure
import me.rerere.rikkahub.startup.StartupImportResult
import me.rerere.rikkahub.startup.StartupIntroMaterialStore
import me.rerere.rikkahub.startup.StartupIntroPolicy
import me.rerere.rikkahub.startup.StartupIntroRules
import me.rerere.rikkahub.startup.StartupIntroScene
import me.rerere.rikkahub.startup.StartupIntroSceneResolver
import me.rerere.rikkahub.startup.StartupMediaKind
import me.rerere.rikkahub.startup.StartupMotion
import me.rerere.rikkahub.startup.StartupPreferences
import me.rerere.rikkahub.startup.animatorDurationScale
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

/**
 * 主题设置里的"启动动效"一段（r22）。
 *
 * 三件事在同一个地方：内建风格、本地素材（导入/预览/清除）、时长与缩放。
 * 所有写入都是一次 SharedPreferences 提交；素材拷贝走 IO 线程，界面只用一个 busy 位。
 * 每改一次就重播一次预览，所以"改完才知道什么样"这种来回不存在。
 */
@Composable
fun StartupIntroSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var motion by remember { mutableStateOf(StartupPreferences.readMotion(context)) }
    var lastBuiltIn by remember {
        mutableStateOf(
            if (motion.isBuiltIn && motion != StartupMotion.OFF) motion else StartupMotion.DEFAULT
        )
    }
    var custom by remember { mutableStateOf(StartupPreferences.readCustom(context)) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<StartupImportFailure?>(null) }
    // 内建风格的时长单独一份：换/清素材不会改掉它，预览和冷启动都读这一个值。
    var builtinDurationMs by remember {
        mutableIntStateOf(StartupPreferences.readBuiltinDuration(context))
    }
    var durationMs by remember(custom) {
        mutableIntStateOf(custom?.durationMs ?: StartupIntroRules.CUSTOM_DURATION_DEFAULT_MS)
    }
    var scalePercent by remember(custom) {
        mutableIntStateOf(custom?.scalePercent ?: StartupIntroRules.DEFAULT_SCALE)
    }
    var fit by remember(custom) { mutableStateOf(custom?.fit ?: StartupFit.DEFAULT) }
    var previewTick by remember { mutableIntStateOf(0) }

    // 系统关掉动画时这里也照实说清楚：冷启动不会画任何东西
    val animatorScale = remember { context.animatorDurationScale() }
    val reduced = remember(animatorScale) { StartupIntroPolicy.isReducedMotion(animatorScale) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        failure = null
        busy = true
        scope.launch {
            val result = StartupIntroMaterialStore.import(context, uri)
            busy = false
            when (result) {
                is StartupImportResult.Imported -> {
                    custom = result.custom
                    motion = StartupMotion.CUSTOM
                    StartupPreferences.writeMotion(context, StartupMotion.CUSTOM)
                    previewTick++
                }

                is StartupImportResult.Rejected -> failure = result.reason
            }
        }
    }

    // 内建风格 + 素材一起收成"要画什么"，预览画的和冷启动画的是同一份场景
    val previewScene: StartupIntroScene = remember(motion, custom, durationMs, scalePercent, fit, builtinDurationMs) {
        StartupIntroSceneResolver.resolve(
            motion = motion,
            custom = custom,
            customDurationMs = durationMs,
            customScalePercent = scalePercent,
            customFit = fit,
            builtinDurationMs = builtinDurationMs,
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.r22_startup_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (reduced) R.string.r22_startup_reduced else R.string.r22_startup_desc
                        )
                    )
                },
            ) {
                val options = listOf(
                    StartupMotion.RIKKA to R.string.r22_startup_style_rikka,
                    StartupMotion.RING to R.string.r22_startup_style_ring,
                    StartupMotion.DOTS to R.string.r22_startup_style_dots,
                    StartupMotion.AURORA to R.string.r22_startup_style_aurora,
                    StartupMotion.OFF to R.string.r22_startup_style_off,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (style, label) ->
                        SegmentedButton(
                            selected = motion == style,
                            onClick = {
                                if (motion != style) {
                                    motion = style
                                    if (style != StartupMotion.OFF) lastBuiltIn = style
                                    StartupPreferences.writeMotion(context, style)
                                    previewTick++
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
            }

            // 时长对三种内建风格和自定义素材都生效：用户反馈"太快闪过看不到"，所以不再写死。
            if (motion != StartupMotion.OFF && motion != StartupMotion.CUSTOM) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.r22_startup_duration_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.r22_startup_builtin_duration_desc, builtinDurationMs))
                    },
                ) {
                    Slider(
                        value = builtinDurationMs.toFloat(),
                        onValueChange = { builtinDurationMs = it.toInt() },
                        onValueChangeFinished = {
                            StartupPreferences.writeBuiltinDuration(context, builtinDurationMs)
                            previewTick++
                        },
                        valueRange = StartupIntroRules.MIN_DURATION_MS.toFloat()..
                            StartupIntroRules.MAX_DURATION_MS.toFloat(),
                    )
                }
            }

            switchItem(
                checked = motion == StartupMotion.CUSTOM,
                onCheckedChange = { checked ->
                    val next = if (checked) StartupMotion.CUSTOM else lastBuiltIn
                    motion = next
                    StartupPreferences.writeMotion(context, next)
                    previewTick++
                },
                supportingContent = {
                    Text(
                        stringResource(
                            when {
                                busy -> R.string.r22_startup_media_busy
                                custom == null -> R.string.r22_startup_media_desc_empty
                                custom?.kind == StartupMediaKind.VIDEO -> R.string.r22_startup_media_desc_video
                                else -> R.string.r22_startup_media_desc_image
                            }
                        )
                    )
                },
                headlineContent = { Text(stringResource(R.string.r22_startup_media_title)) },
            )

            formItem(content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            // 只收图片/动图和小视频；类型不认识导入时会被拒，并给一句原因
                            runCatching { picker.launch(arrayOf("image/*", "video/*")) }
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.r22_startup_media_import))
                    }
                    TextButton(
                        onClick = { previewTick++ },
                        enabled = !previewScene.isEmpty,
                    ) {
                        Text(stringResource(R.string.r22_startup_media_replay))
                    }
                    if (custom != null) {
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    StartupIntroMaterialStore.clear(context)
                                    busy = false
                                    custom = null
                                    failure = null
                                    motion = lastBuiltIn
                                    previewTick++
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.r22_startup_media_clear))
                        }
                    }
                }
                failure?.let { reason ->
                    Text(
                        text = stringResource(reason.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            })

            if (custom != null) {
                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.r22_startup_duration_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.r22_startup_duration_desc, durationMs))
                    },
                ) {
                    Slider(
                        value = durationMs.toFloat(),
                        onValueChange = { durationMs = it.toInt() },
                        onValueChangeFinished = {
                            StartupIntroMaterialStore.updateTuning(context, durationMs, scalePercent, fit)
                            // 改完立刻重播：预览看到的就是刚存下的时长
                            previewTick++
                        },
                        valueRange = StartupIntroRules.MIN_DURATION_MS.toFloat()..
                            StartupIntroRules.MAX_DURATION_MS.toFloat(),
                    )
                }
                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.r22_startup_scale_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.r22_startup_scale_desc, scalePercent))
                    },
                ) {
                    Slider(
                        value = scalePercent.toFloat(),
                        onValueChange = { scalePercent = it.toInt() },
                        onValueChangeFinished = {
                            StartupIntroMaterialStore.updateTuning(context, durationMs, scalePercent, fit)
                            previewTick++
                        },
                        valueRange = StartupIntroRules.MIN_SCALE.toFloat()..
                            StartupIntroRules.MAX_SCALE.toFloat(),
                    )
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.r22_startup_fit_title)) },
                ) {
                    val fits = listOf(
                        StartupFit.CONTAIN to R.string.r22_startup_fit_contain,
                        StartupFit.COVER to R.string.r22_startup_fit_cover,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        fits.forEachIndexed { index, (option, label) ->
                            SegmentedButton(
                                selected = fit == option,
                                onClick = {
                                    fit = option
                                    StartupIntroMaterialStore.updateTuning(
                                        context, durationMs, scalePercent, option
                                    )
                                    previewTick++
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, fits.size),
                            ) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
            }
        }

        // 预览：和冷启动用同一个 composable、同一份场景，点一下重播
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.r22_startup_preview_title)) },
                supportingContent = { Text(stringResource(R.string.r22_startup_preview_desc)) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { previewTick++ },
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewScene.isEmpty) {
                        Text(
                            text = stringResource(R.string.r22_startup_preview_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        key(previewTick) {
                            StartupIntroOverlay(
                                scene = previewScene,
                                onFinished = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.r22_startup_preview_tap),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

private fun StartupImportFailure.labelRes(): Int = when (this) {
    StartupImportFailure.UNSUPPORTED_TYPE -> R.string.r22_startup_fail_unsupported
    StartupImportFailure.TOO_LARGE -> R.string.r22_startup_fail_too_large
    StartupImportFailure.EMPTY_FILE -> R.string.r22_startup_fail_empty
    StartupImportFailure.IO_ERROR -> R.string.r22_startup_fail_io
}
