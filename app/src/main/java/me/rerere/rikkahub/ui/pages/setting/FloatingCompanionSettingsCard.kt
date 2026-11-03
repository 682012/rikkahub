package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.floating.ChatFloatingBubble
import me.rerere.rikkahub.ui.floating.FloatingCompanionFiles
import me.rerere.rikkahub.ui.floating.floatingCompanionDefaultLines
import me.rerere.rikkahub.ui.floating.floatingCompanionDefaultName
import me.rerere.rikkahub.ui.floating.previewCompanionState
import kotlin.math.roundToInt

/**
 * 悬浮窗角色设置卡片：预设角色（应用图标/猫咪/小龙/自定义图片）、相册图片、名字、点击台词、大小、动效。
 *
 * 和 [AppearanceSettingsCard] 一样只负责界面，数据通过 [onChange] 交回调用方，自己不存设置。
 * 相册选到的图片先拷进应用私有目录再把路径写进设置；旧图要等新的值持久化完成后再删，
 * 半路失败不会把当前还在用的图删掉。
 *
 * 放进设置页 LazyColumn 时不用传 [scrollable]：
 * ```
 * item {
 *     FloatingCompanionSettingsCard(
 *         companion = settings.displaySetting.floatingCompanion,
 *         onChange = { vm.updateSettings { s -> s.copy(displaySetting = s.displaySetting.copy(floatingCompanion = it)) } },
 *     )
 * }
 * ```
 * 如果调用方能提供真正的落盘动作，传给 [persistChange]，删旧图会等它返回。
 *
 * 预览用一个假的空闲状态，不需要 Koin、不需要真实会话，点一下就能看到抬手和台词。
 */
@Composable
fun FloatingCompanionSettingsCard(
    companion: FloatingCompanionSettings,
    onChange: (FloatingCompanionSettings) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    persistChange: (suspend (FloatingCompanionSettings) -> Unit)? = null,
) {
    val current = remember(companion) { companion.normalized() }
    val latest by rememberUpdatedState(current)
    val latestPersist by rememberUpdatedState(persistChange)
    val changeScope = rememberCoroutineScope()
    fun update(block: (FloatingCompanionSettings) -> FloatingCompanionSettings) {
        val next = block(latest).normalized()
        if (latestPersist != null) changeScope.launch { latestPersist?.invoke(next) }
        else onChange(next)
    }

    // 资源要在可组合上下文里取：CardGroup 的 content lambda 不是 @Composable
    val defaultName = floatingCompanionDefaultName(current.effectivePreset)
    val defaultLines = floatingCompanionDefaultLines(current.effectivePreset)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    /**
     * 拷图 → 立即更新界面 → 落盘 → 才删旧图。
     * 顺序反过来的话，写盘失败时旧图已经被删，设置里留下一个打不开的路径。
     */
    fun importImage(uri: Uri?, tapImage: Boolean) {
        if (uri == null || saving) return
        saving = true
        saveError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FloatingCompanionFiles.copyFromUri(context, uri) }
            }.onSuccess { storedPath ->
                val previous = if (tapImage) latest.customTapImagePath else latest.customImagePath
                val updated = latest.copy(
                    // 选了自定义图片就切到"我的图片"，不然选了图还停在 logo 上会让人以为没生效
                    preset = if (!tapImage) FloatingCompanionPreset.CUSTOM else latest.preset,
                    customImagePath = if (tapImage) latest.customImagePath else storedPath,
                    customTapImagePath = if (tapImage) storedPath else latest.customTapImagePath,
                ).normalized()
                onChange(updated)
                if (persistChange != null) {
                    runCatching { persistChange.invoke(updated) }
                        .onSuccess {
                            if (previous != null && previous != storedPath && previous !in listOf(updated.customImagePath, updated.customTapImagePath)) {
                                withContext(Dispatchers.IO) { FloatingCompanionFiles.release(context, previous) }
                            }
                        }
                        .onFailure { saveError = it.message ?: it::class.simpleName }
                }
            }.onFailure { error ->
                saveError = error.message ?: error::class.simpleName ?: "unknown"
            }
            saving = false
        }
    }

    /** 移除图片同样先改设置、再删文件。 */
    fun removeImage(tapImage: Boolean) {
        val previous = if (tapImage) latest.customTapImagePath else latest.customImagePath
        val updated = latest.copy(
            customImagePath = if (tapImage) latest.customImagePath else null,
            customTapImagePath = if (tapImage) null else latest.customTapImagePath,
        ).normalized()
        onChange(updated)
        scope.launch {
            if (persistChange != null) {
                runCatching { persistChange.invoke(updated) }
                    .onSuccess {
                        if (previous != null && previous !in listOf(updated.customImagePath, updated.customTapImagePath)) {
                            withContext(Dispatchers.IO) { FloatingCompanionFiles.release(context, previous) }
                        }
                    }
                    .onFailure { saveError = it.message ?: it::class.simpleName }
            }
        }
    }

    val idlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        importImage(uri, tapImage = false)
    }
    val tapPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        importImage(uri, tapImage = true)
    }

    val content: @Composable ColumnScope.() -> Unit = {
        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_preview)) }) {
            formItem(
                supportingContent = { Text(stringResource(R.string.floating_companion_preview_hint)) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 和系统悬浮窗里是同一个 composable，所以预览就等于是真实效果
                    ChatFloatingBubble(
                        state = remember { previewCompanionState() },
                        onClick = {},
                        companion = current,
                    )
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_preset)) }) {
            formItem(
                supportingContent = { Text(stringResource(current.preset.descRes())) },
            ) {
                ConnectedChoiceButtons(
                    options = FloatingCompanionPreset.entries,
                    selected = current.preset,
                    onSelect = { preset -> update { it.copy(preset = preset) } },
                ) { option, selected ->
                    ConnectedChoiceLabel(
                        text = stringResource(option.labelRes()),
                        selected = selected,
                    )
                }
            }
        }

        if (current.preset == FloatingCompanionPreset.CUSTOM || current.customImagePath != null ||
            current.customTapImagePath != null
        ) {
            CardGroup(title = { Text(stringResource(R.string.floating_companion_section_image)) }) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.floating_companion_image_idle_title)) },
                    supportingContent = { Text(stringResource(R.string.floating_companion_image_idle_desc)) },
                ) {
                    CompanionImageRow(
                        path = current.customImagePath,
                        enabled = !saving,
                        pickLabel = stringResource(
                            if (current.customImagePath == null) {
                                R.string.floating_companion_image_pick
                            } else {
                                R.string.floating_companion_image_replace
                            }
                        ),
                        onPick = {
                            idlePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = if (current.customImagePath != null) {
                            { removeImage(tapImage = false) }
                        } else null,
                    )
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.floating_companion_image_tap_title)) },
                    supportingContent = { Text(stringResource(R.string.floating_companion_image_tap_desc)) },
                ) {
                    CompanionImageRow(
                        path = current.customTapImagePath,
                        enabled = !saving,
                        pickLabel = stringResource(R.string.floating_companion_image_tap_pick),
                        onPick = {
                            tapPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = if (current.customTapImagePath != null) {
                            { removeImage(tapImage = true) }
                        } else null,
                    )
                }
                if (saving || saveError != null) {
                    formItem(
                        supportingContent = {
                            Text(
                                text = saveError?.let {
                                    stringResource(R.string.floating_companion_image_failed, it)
                                } ?: stringResource(R.string.floating_companion_image_saving),
                                color = if (saveError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    ) { Spacer(Modifier.size(0.dp)) }
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_voice)) }) {
            formItem(
                headlineContent = { Text(stringResource(R.string.floating_companion_name_title)) },
                supportingContent = { Text(stringResource(R.string.floating_companion_name_desc)) },
            ) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { value ->
                        update { it.copy(name = value.take(FloatingCompanionLimits.MAX_NAME_LENGTH)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(defaultName) },
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.floating_companion_lines_title)) },
                supportingContent = { Text(stringResource(R.string.floating_companion_lines_desc)) },
            ) {
                // 输入框留一份原始草稿：设置里是过滤过的列表，直接拿它回填的话
                // 刚敲下的换行会被 normalized 吃掉，光标会乱跳
                var draft by remember { mutableStateOf(current.tapLines.joinToString("\n")) }
                fun sanitize(text: String) = text.split('\n')
                    .map { it.trim().take(FloatingCompanionLimits.MAX_TAP_LINE_LENGTH) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(FloatingCompanionLimits.MAX_TAP_LINES)
                LaunchedEffect(current.tapLines) {
                    // 只有外部真的改了内容（换预设、点"用默认台词"）才回填，自己打字时不动
                    if (sanitize(draft) != current.tapLines) {
                        draft = current.tapLines.joinToString("\n")
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { value ->
                        draft = value
                        update { it.copy(tapLines = sanitize(value)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = FloatingCompanionLimits.MAX_TAP_LINES + 1,
                    placeholder = {
                        Text(defaultLines.firstOrNull().orEmpty())
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { update { it.copy(tapLines = emptyList()) } },
                        enabled = current.tapLines.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.floating_companion_lines_reset))
                    }
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_behavior)) }) {
            formItem(
                headlineContent = { Text(stringResource(R.string.floating_companion_size_title)) },
                supportingContent = {
                    Text(stringResource(R.string.floating_companion_size_desc, current.sizeDp))
                },
                trailingContent = { CardGroupValue("${current.sizeDp} dp") },
            ) {
                var localSize by remember(current.sizeDp) { mutableStateOf(current.sizeDp.toFloat()) }
                Slider(
                    value = localSize,
                    onValueChange = { localSize = it },
                    onValueChangeFinished = {
                        update {
                            it.copy(sizeDp = localSize.roundToInt())
                        }
                    },
                    valueRange = FloatingCompanionLimits.SIZE_MIN_DP.toFloat()..FloatingCompanionLimits.SIZE_MAX_DP.toFloat(),
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            switchItem(
                checked = current.animationEnabled,
                onCheckedChange = { enabled -> update { it.copy(animationEnabled = enabled) } },
                headlineContent = { Text(stringResource(R.string.floating_companion_animation_title)) },
                supportingContent = { Text(stringResource(R.string.floating_companion_animation_desc)) },
            )
        }
    }

    if (scrollable) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
    } else {
        Column(modifier = modifier, content = content)
    }
}

/** 一行缩略图 + 选择/移除按钮，两个图片位共用。 */
@Composable
private fun CompanionImageRow(
    path: String?,
    enabled: Boolean,
    pickLabel: String,
    onPick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val context = LocalContext.current
    // 只认私有目录里的文件；路径坏了就当没有，绝不去加载网络地址
    val resolved = remember(path) { FloatingCompanionFiles.resolve(context, path) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (resolved != null) {
                // 缩略图按 56dp 解码，不把原图整张读进内存
                AsyncImage(
                    model = resolved,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(
            onClick = onPick,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Image02,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = pickLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
        if (onRemove != null) {
            OutlinedButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Delete02,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
        }
    }
    if (resolved == null) {
        Text(
            text = stringResource(R.string.floating_companion_image_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun FloatingCompanionPreset.labelRes(): Int = when (this) {
    FloatingCompanionPreset.LOGO -> R.string.floating_companion_preset_logo
    FloatingCompanionPreset.CAT -> R.string.floating_companion_preset_cat
    FloatingCompanionPreset.DRAGON -> R.string.floating_companion_preset_dragon
    FloatingCompanionPreset.CUSTOM -> R.string.floating_companion_preset_custom
}

private fun FloatingCompanionPreset.descRes(): Int = when (this) {
    FloatingCompanionPreset.LOGO -> R.string.floating_companion_preset_logo_desc
    FloatingCompanionPreset.CAT -> R.string.floating_companion_preset_cat_desc
    FloatingCompanionPreset.DRAGON -> R.string.floating_companion_preset_dragon_desc
    FloatingCompanionPreset.CUSTOM -> R.string.floating_companion_preset_custom_desc
}

/**
 * 放进设置页 LazyColumn 用：
 * ```
 * floatingCompanionSettingsItem(
 *     companion = displaySetting.floatingCompanion,
 *     onChange = { updateDisplaySetting(displaySetting.copy(floatingCompanion = it)) },
 * )
 * ```
 */
fun LazyListScope.floatingCompanionSettingsItem(
    companion: FloatingCompanionSettings,
    onChange: (FloatingCompanionSettings) -> Unit,
    persistChange: (suspend (FloatingCompanionSettings) -> Unit)? = null,
) {
    item {
        FloatingCompanionSettingsCard(
            companion = companion,
            onChange = onChange,
            persistChange = persistChange,
        )
    }
}
