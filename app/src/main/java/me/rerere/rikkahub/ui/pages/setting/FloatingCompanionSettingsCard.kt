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
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionPersonas
import me.rerere.rikkahub.data.companion.CompanionCatalog
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
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
 * 悬浮窗角色设置卡片：立绘预设、相册图片、名字、台词、大小、动效、边缘收起。
 *
 * 只负责界面，状态通过 [onChange] 交给调用方；给了 [persistChange] 时删旧图等它落盘之后再做。
 */
@Composable
fun FloatingCompanionSettingsCard(
    companion: FloatingCompanionSettings,
    onChange: (FloatingCompanionSettings) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    persistChange: (suspend (FloatingCompanionSettings) -> Unit)? = null,
    providers: List<ProviderSetting> = emptyList(),
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
        val importOwner = latest.characterKey
        val targetPreset = if (!tapImage && latest.effectivePreset == FloatingCompanionPreset.LOGO) {
            FloatingCompanionPreset.CUSTOM
        } else latest.preset
        saving = true
        saveError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FloatingCompanionFiles.copyFromUri(context, uri) }
            }.onSuccess { storedPath ->
                // The copy may finish after a character switch. Save to the original owner,
                // then restore the current selection instead of putting its picture on another girl.
                val active = latest
                val target = active.selectPreset(targetPreset)
                val previous = if (tapImage) target.customTapImagePath else target.customImagePath
                val imported = target.copy(
                    customImagePath = if (tapImage) target.customImagePath else storedPath,
                    customTapImagePath = if (tapImage) storedPath else target.customTapImagePath,
                ).normalized()
                val updated = if (active.characterKey == importOwner) imported else imported.selectPreset(active.preset)
                onChange(updated)
                if (persistChange != null) {
                    runCatching { persistChange.invoke(updated) }
                        .onSuccess {
                            if (previous != null && previous != storedPath && !updated.referencesImage(previous)) {
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
                        if (previous != null && !updated.referencesImage(previous)) {
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
                supportingContent = { Text(stringResource(R.string.r12_preview_hint)) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 同一个 composable，但只画角色本身：这里按展开态显示，系统悬浮窗的
                    // 边缘收起、窗口拖拽等行为预览里不模拟。
                    ChatFloatingBubble(
                        state = remember { previewCompanionState() },
                        onClick = {},
                        companion = current.copy(minimized = false),
                    )
                }
            }
        }

        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_preset)) }) {
            formItem(
                supportingContent = { Text(stringResource(R.string.portrait_preset_desc)) },
            ) {
                PortraitCompanionSelector(
                    companion = current,
                    // 切角色统一走 selectPreset：先把当前角色的名字/台词/个性/图片存回自己的格子，
                    // 再载入下一只。模型、AI 和所有开关都不在这份数据里，原样保留。
                    onSelect = { preset -> update { settings -> settings.selectPreset(preset) } },
                )
                if (highlightPreset(current.preset) == FloatingCompanionPreset.CUSTOM) {
                    Text(
                        text = stringResource(R.string.portrait_custom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // r12：内置角色也能各自放一张自己的图当覆盖，所以图片区始终显示，不再只在"我的图片"时出现
        run {
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
                // 刚敲下的换行会被 normalized 吃掉，光标会乱跳。
                // r12：按角色分开记，切角色就重建草稿，避免把上一只的台词串写到新角色。
                var draft by remember(current.characterKey) { mutableStateOf(current.tapLines.joinToString("\n")) }
                fun sanitize(text: String) = text.split('\n')
                    .map { it.trim().take(FloatingCompanionLimits.MAX_TAP_LINE_LENGTH) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(FloatingCompanionLimits.MAX_TAP_LINES)
                LaunchedEffect(current.characterKey, current.tapLines) {
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

        CardGroup(title = { Text(stringResource(R.string.floating_companion_section_ai)) }) {
            switchItem(
                checked = current.aiEnabled,
                onCheckedChange = { enabled ->
                    update {
                        it.copy(
                            // 打开就用"跟随当前会话"，这是最省事的一种；想固定某个模型再切
                            aiModelMode = if (!enabled) {
                                CompanionModelMode.OFF
                            } else if (it.aiModelMode == CompanionModelMode.OFF) {
                                CompanionModelMode.FOLLOW_CONVERSATION
                            } else {
                                it.aiModelMode
                            },
                            autoEventsEnabled = if (enabled) it.autoEventsEnabled else false,
                        )
                    }
                },
                headlineContent = { Text(stringResource(R.string.companion_ai_title)) },
                supportingContent = { Text(stringResource(R.string.companion_ai_desc)) },
            )
            if (current.aiEnabled) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.companion_ai_mode_title)) },
                    supportingContent = { Text(stringResource(R.string.companion_ai_mode_desc)) },
                ) {
                    ConnectedChoiceButtons(
                        options = listOf(CompanionModelMode.FOLLOW_CONVERSATION, CompanionModelMode.FIXED),
                        selected = current.aiModelMode,
                        onSelect = { mode -> update { it.copy(aiModelMode = mode) } },
                    ) { option, selected ->
                        ConnectedChoiceLabel(
                            text = stringResource(
                                if (option == CompanionModelMode.FOLLOW_CONVERSATION) {
                                    R.string.companion_ai_mode_follow
                                } else {
                                    R.string.companion_ai_mode_fixed
                                }
                            ),
                            selected = selected,
                        )
                    }
                }
                when (current.aiModelMode) {
                    // 跟随会话只借用模型，不把聊天内容发出去，这一点写在界面上
                    CompanionModelMode.FOLLOW_CONVERSATION -> formItem(
                        headlineContent = { Text(stringResource(R.string.companion_ai_follow_title)) },
                        supportingContent = { Text(stringResource(R.string.companion_ai_follow_privacy)) },
                    ) {
                        Text(
                            text = stringResource(R.string.companion_ai_follow_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    CompanionModelMode.FIXED -> formItem(
                        headlineContent = { Text(stringResource(R.string.companion_ai_model_title)) },
                        supportingContent = { Text(stringResource(R.string.companion_ai_model_desc)) },
                    ) {
                        ModelSelector(
                            modelId = current.aiModelId,
                            providers = providers,
                            type = ModelType.CHAT,
                            onSelect = { model -> update { it.copy(aiModelId = model.id) } },
                        )
                        if (current.aiModelId == null) {
                            Text(
                                text = stringResource(R.string.companion_ai_model_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    CompanionModelMode.OFF -> Unit
                }
                switchItem(
                    checked = current.autoEventsEnabled,
                    onCheckedChange = { enabled -> update { it.copy(autoEventsEnabled = enabled) } },
                    headlineContent = { Text(stringResource(R.string.companion_auto_title)) },
                    supportingContent = { Text(stringResource(R.string.companion_auto_desc)) },
                )
                formItem(
                    headlineContent = { Text(stringResource(R.string.companion_persona_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.companion_persona_desc,
                                builtInPersonaSummary(current.effectivePreset),
                            )
                        )
                    },
                ) {
                    OutlinedTextField(
                        value = current.aiPersonaPrompt,
                        onValueChange = { value ->
                            update {
                                it.copy(aiPersonaPrompt = value.take(FloatingCompanionLimits.MAX_PERSONA_LENGTH))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        placeholder = { Text(builtInPersonaSummary(current.effectivePreset)) },
                    )
                    if (current.aiPersonaPrompt.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { update { it.copy(aiPersonaPrompt = "") } }) {
                                Text(stringResource(R.string.companion_persona_reset))
                            }
                        }
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
            // r12：把"动不动"说清楚，就是这一个开关——动态=轻呼吸/轻摆 + 点击切图，静态=不跑附加动画。
            // 两种都会在点击时切到动作图并说一句台词，语义沿用原来的 animationEnabled。
            formItem(
                headlineContent = { Text(stringResource(R.string.r12_style_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (current.animationEnabled) {
                                R.string.r12_style_dynamic_desc
                            } else {
                                R.string.r12_style_static_desc
                            }
                        )
                    )
                },
            ) {
                ConnectedChoiceButtons(
                    options = listOf(true, false),
                    selected = current.animationEnabled,
                    onSelect = { enabled -> update { settings -> settings.copy(animationEnabled = enabled) } },
                ) { isDynamic, isSelected ->
                    ConnectedChoiceLabel(
                        text = stringResource(
                            if (isDynamic) R.string.r12_style_dynamic else R.string.r12_style_static
                        ),
                        selected = isSelected,
                    )
                }
            }
            // 换角色、改大小都不动这两个值，收起状态是独立开关
            switchItem(
                checked = current.minimized,
                onCheckedChange = { minimized -> update { it.copy(minimized = minimized) } },
                headlineContent = { Text(stringResource(R.string.portrait_minimized_title)) },
                supportingContent = { Text(stringResource(R.string.portrait_minimized_desc)) },
            )
            switchItem(
                checked = current.panelPreviewExpanded,
                onCheckedChange = { expanded -> update { it.copy(panelPreviewExpanded = expanded) } },
                headlineContent = { Text(stringResource(R.string.portrait_preview_toggle)) },
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

/** 内置人设的一句话说明；个性输入框留空时就是它在起作用。 */
@Composable
private fun builtInPersonaSummary(preset: FloatingCompanionPreset): String =
    CompanionCatalog.profile(preset)?.shortDescription ?: stringResource(
    when (CompanionPersonas.summaryKey(preset)) {
        "dragon_girl" -> R.string.companion_persona_builtin_dragon_girl
        "sun_reader" -> R.string.companion_persona_builtin_sun_reader
        "whale_maid" -> R.string.companion_persona_builtin_whale_maid
        "cat" -> R.string.companion_persona_builtin_cat
        "gemini_cat" -> R.string.r12_persona_summary_gemini
        "glm_fox" -> R.string.r12_persona_summary_glm
        "qwen_girl" -> R.string.r12_persona_summary_qwen
        "logo" -> R.string.companion_persona_builtin_logo
        else -> R.string.companion_persona_builtin_custom
    }
)

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
    providers: List<ProviderSetting> = emptyList(),
) {
    item {
        FloatingCompanionSettingsCard(
            companion = companion,
            onChange = onChange,
            persistChange = persistChange,
            providers = providers,
        )
    }
}
