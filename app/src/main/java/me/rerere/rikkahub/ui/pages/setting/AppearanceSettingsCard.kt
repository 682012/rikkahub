package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.data.datastore.WallpaperScale
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.switchItem

/**
 * 外观设置卡片：聊天界面风格（经典/工作台/专注）+ 壁纸（选择、沉浸、亮度遮罩、模糊、适配、焦点）+ 面板强度。
 *
 * 只负责"长什么样"的选项，数据通过 [onChange] 交回调用方（SettingVM），自己不存任何设置。
 * 从相册选到的图片会先拷进应用私有目录再把路径写进设置：系统相册给的临时 URI 一过期就没了，
 * 直接存下来下次启动就是空白壁纸。
 *
 * 用法（放在设置页的 LazyColumn 里就不需要自己滚动）：
 * ```
 * item {
 *     AppearanceSettingsCard(
 *         appearance = settings.displaySetting.appearance,
 *         onChange = { vm.updateSettings { s -> s.copy(displaySetting = s.displaySetting.copy(appearance = it)) } },
 *     )
 * }
 * ```
 * 单独占一页时传 `scrollable = true`，内容自己滚。
 */
@Composable
fun AppearanceSettingsCard(
    appearance: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    persistChange: (suspend (AppearanceSettings) -> Unit)? = null,
) {
    val current = remember(appearance) { appearance.normalized() }
    val latestAppearance by androidx.compose.runtime.rememberUpdatedState(current)
    fun update(block: (AppearanceSettings) -> AppearanceSettings) {
        onChange(block(latestAppearance).normalized())
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showPreview by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null || saving) return@rememberLauncherForActivityResult
        saving = true
        saveError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { copyWallpaperIntoAppStorage(context, uri) }
            }.onSuccess { storedPath ->
                val previous = latestAppearance.wallpaper
                update { it.copy(wallpaper = storedPath) }
                // Cleanup is gated on the caller's durable write; never delete the referenced image first.
                persistChange?.invoke(latestAppearance.copy(wallpaper = storedPath).normalized())
                if (persistChange != null && previous != null && previous != storedPath) {
                    withContext(Dispatchers.IO) { releaseOwnedWallpaper(context, previous) }
                }
            }.onFailure { error ->
                saveError = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            }
            saving = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(title = { Text(stringResource(R.string.appearance_style_section)) }) {
            formItem(
                supportingContent = { Text(stringResource(current.chatUiStyle.descRes())) },
            ) {
                ChatUiStyleSelector(
                    selected = current.chatUiStyle,
                    onSelect = { style -> update { it.copy(chatUiStyle = style) } },
                )
            }
        }

        CardGroup(title = { Text(stringResource(R.string.appearance_wallpaper_pick_title)) }) {
            formItem {
                WallpaperBanner(
                    appearance = current,
                    onClick = {
                        if (!saving) { if (current.hasWallpaper()) showPreview = true else showSourceDialog = true }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { showSourceDialog = true },
                        enabled = !saving,
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
                        Text(
                            text = stringResource(R.string.appearance_wallpaper_pick),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            urlInput = current.wallpaper.orEmpty().takeIf { it.startsWith("http") }.orEmpty()
                            showUrlDialog = true
                        },
                        enabled = !saving,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Link01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(
                            text = stringResource(R.string.appearance_wallpaper_pick_url),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (current.hasWallpaper()) {
                    TextButton(
                        onClick = {
                            val previous = current.wallpaper
                            update { it.copy(wallpaper = null) }
                            if (persistChange != null && previous != null) {
                                scope.launch {
                                    persistChange.invoke(latestAppearance.copy(wallpaper = null).normalized())
                                    withContext(Dispatchers.IO) { releaseOwnedWallpaper(context, previous) }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete02,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.appearance_wallpaper_remove))
                    }
                }
                val error = saveError
                if (error != null) {
                    Text(
                        text = stringResource(R.string.appearance_wallpaper_save_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            switchItem(
                checked = current.wallpaperAdaptiveTint,
                onCheckedChange = { enabled -> update { it.copy(wallpaperAdaptiveTint = enabled) } },
                headlineContent = { Text(stringResource(R.string.wallpaper_adaptive_tint_title)) },
                supportingContent = { Text(stringResource(R.string.wallpaper_adaptive_tint_desc)) },
            )
            switchItem(
                checked = current.immersiveWallpaper,
                onCheckedChange = { immersive -> update { it.copy(immersiveWallpaper = immersive) } },
                supportingContent = { Text(stringResource(R.string.appearance_wallpaper_immersive_desc)) },
                headlineContent = { Text(stringResource(R.string.appearance_wallpaper_immersive_title)) },
            )
            // r10 沉浸聊天：默认开，只在真的有壁纸时生效；关掉就是“恢复卡片”。
            // 强度不另开滑块，沿用下面“面板强度”那一项，避免两套透明度互相打架。
            switchItem(
                checked = current.immersiveChat,
                onCheckedChange = { immersive -> update { it.copy(immersiveChat = immersive) } },
                headlineContent = { Text(stringResource(R.string.r10_immersive_chat_title)) },
                supportingContent = { Text(stringResource(R.string.r10_immersive_chat_desc)) },
            )
        }

        CardGroup(title = { Text(stringResource(R.string.appearance_wallpaper_display_section)) }) {
            AppearanceSliderItem(
                title = R.string.appearance_wallpaper_visibility,
                value = current.wallpaperOpacity,
                valueRange = 0f..1f,
                steps = 9,
                onCommit = { value -> update { it.copy(wallpaperOpacity = value) } },
            )
            AppearanceSliderItem(
                title = R.string.appearance_wallpaper_scrim,
                value = current.wallpaperScrim,
                valueRange = 0f..1f,
                steps = 9,
                supportingContent = R.string.appearance_wallpaper_scrim_desc,
                onCommit = { value -> update { it.copy(wallpaperScrim = value) } },
            )
            AppearanceSliderItem(
                title = R.string.appearance_wallpaper_blur,
                value = current.wallpaperBlur,
                valueRange = 0f..AppearanceSettings.MAX_BLUR_DP,
                steps = 15,
                showPercent = false,
                onCommit = { value -> update { it.copy(wallpaperBlur = value) } },
            )
            formItem(
                headlineContent = { Text(stringResource(R.string.appearance_wallpaper_scale)) },
            ) {
                ConnectedChoiceButtons(
                    options = WallpaperScale.entries.toList(),
                    selected = current.wallpaperScale,
                    onSelect = { scale -> update { it.copy(wallpaperScale = scale) } },
                    minHeight = 48.dp,
                ) { scale, _ ->
                    Text(stringResource(scale.labelRes()))
                }
            }
            if (current.wallpaperScale == WallpaperScale.CROP) formItem(
                headlineContent = { Text(stringResource(R.string.appearance_wallpaper_focus)) },
                supportingContent = { Text(stringResource(R.string.appearance_wallpaper_focus_desc)) },
            ) {
                FocusPointGrid(
                    alignmentX = current.wallpaperAlignmentX,
                    alignmentY = current.wallpaperAlignmentY,
                    onSelect = { x, y -> update { it.copy(wallpaperAlignmentX = x, wallpaperAlignmentY = y) } },
                )
            }
        }

        CardGroup(title = { Text(stringResource(R.string.appearance_surface_section)) }) {
            AppearanceSliderItem(
                title = R.string.appearance_surface_opacity,
                value = current.surfaceOpacity,
                valueRange = AppearanceSettings.MIN_SURFACE_OPACITY..1f,
                steps = 3,
                // 沉浸模式下同一个滑块表示“文字底多厚”，语义变了，说明也跟着换。
                supportingContent = if (current.immersiveChat) {
                    R.string.r10_immersive_panel_desc
                } else {
                    R.string.appearance_surface_opacity_desc
                },
                onCommit = { value -> update { it.copy(surfaceOpacity = value) } },
            )
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text(stringResource(R.string.appearance_wallpaper_pick_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (saving) return@FilledTonalButton
                            showSourceDialog = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.appearance_wallpaper_pick_gallery))
                    }
                    OutlinedButton(
                        onClick = {
                            showSourceDialog = false
                            urlInput = ""
                            showUrlDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.appearance_wallpaper_pick_url))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text(stringResource(R.string.appearance_cancel))
                }
            },
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.appearance_wallpaper_pick_url)) },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text(stringResource(R.string.appearance_wallpaper_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val link = urlInput.trim()
                        val parsed = runCatching { Uri.parse(link) }.getOrNull()
                        if (parsed?.scheme in listOf("http", "https") && !parsed?.host.isNullOrBlank()) {
                            val previous = latestAppearance.wallpaper
                            update { it.copy(wallpaper = link) }
                            if (persistChange != null && previous != null && previous != link) scope.launch {
                                persistChange.invoke(latestAppearance.copy(wallpaper = link).normalized())
                                withContext(Dispatchers.IO) { releaseOwnedWallpaper(context, previous) }
                            }
                            showUrlDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.appearance_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(stringResource(R.string.appearance_cancel))
                }
            },
        )
    }

    if (showPreview && current.hasWallpaper()) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text(stringResource(R.string.appearance_wallpaper_preview_desc)) },
            text = {
                AsyncImage(
                    model = current.wallpaper,
                    contentDescription = stringResource(R.string.appearance_wallpaper_preview_desc),
                    contentScale = if (current.wallpaperScale == WallpaperScale.CROP) {
                        ContentScale.Crop
                    } else {
                        ContentScale.Fit
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .clip(MaterialTheme.shapes.large),
                )
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) {
                    Text(stringResource(R.string.appearance_confirm))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 聊天界面风格选择：三个缩略图，点哪个用哪个
// ---------------------------------------------------------------------------

@Composable
private fun ChatUiStyleSelector(
    selected: ChatUiStyle,
    onSelect: (ChatUiStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChatUiStyle.entries.forEach { style ->
            ChatUiStyleOption(
                style = style,
                selected = style == selected,
                onClick = { if (style != selected) onSelect(style) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChatUiStyleOption(
    style: ChatUiStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) {
            scheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            scheme.surfaceContainerHigh
        },
    )
    val border = if (selected) {
        BorderStroke(2.dp, scheme.primary)
    } else {
        BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    }

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.16f),
                                scheme.tertiary.copy(alpha = 0.10f),
                                scheme.surface.copy(alpha = 0.9f),
                            )
                        )
                    )
                    .padding(8.dp),
            ) {
                when (style) {
                    ChatUiStyle.CLASSIC -> ClassicLayoutSketch()
                    ChatUiStyle.STUDIO -> StudioLayoutSketch()
                    ChatUiStyle.FOCUS -> FocusLayoutSketch()
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(style.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Icon(
                        imageVector = HugeIcons.Tick02,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// 经典：左边助手气泡、右边用户气泡
@Composable
private fun ClassicLayoutSketch() {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurface.copy(alpha = 0.28f)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.35f))
            )
            SketchBubble(
                bubbleColor = scheme.surface,
                lineColor = line,
                lines = 2,
                modifier = Modifier.fillMaxWidth(0.62f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SketchBubble(
                bubbleColor = scheme.primary.copy(alpha = 0.85f),
                lineColor = scheme.onPrimary.copy(alpha = 0.6f),
                lines = 2,
                modifier = Modifier.fillMaxWidth(0.58f),
            )
        }
    }
}

// 工作台：顶部状态条 + 两侧卡片，信息更密
@Composable
private fun StudioLayoutSketch() {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurface.copy(alpha = 0.28f)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.primary.copy(alpha = 0.28f))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SketchCard(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp),
                color = scheme.surface,
            )
            SketchCard(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp),
                color = scheme.surface,
            )
        }
        SketchBubble(
            bubbleColor = scheme.surface,
            lineColor = line,
            lines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// 专注：居中窄栏，留白多，装饰少
@Composable
private fun FocusLayoutSketch() {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurface.copy(alpha = 0.22f)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SketchLine(
            color = line,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(3.dp)
        )
        Spacer(Modifier.height(5.dp))
        SketchLine(
            color = line,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(3.dp)
        )
        Spacer(Modifier.height(5.dp))
        SketchLine(
            color = scheme.primary.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .height(3.dp)
        )
    }
}

@Composable
private fun SketchBubble(
    bubbleColor: Color,
    lineColor: Color,
    lines: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bubbleColor)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(lines) { index ->
            SketchLine(
                color = lineColor,
                modifier = Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.55f else 1f)
                    .height(3.dp),
            )
        }
    }
}

@Composable
private fun SketchCard(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}

@Composable
private fun SketchLine(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

// ---------------------------------------------------------------------------
// 壁纸预览条
// ---------------------------------------------------------------------------

@Composable
private fun WallpaperBanner(
    appearance: AppearanceSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hasWallpaper = appearance.hasWallpaper()
    val previewDescription = stringResource(R.string.appearance_wallpaper_preview_desc)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primary.copy(alpha = 0.20f),
                        scheme.tertiary.copy(alpha = 0.14f),
                        scheme.surfaceContainerHigh,
                    )
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (appearance.wallpaper != null) {
            me.rerere.rikkahub.ui.theme.WallpaperBackdrop(
                source = requireNotNull(appearance.wallpaper),
                appearance = appearance,
                modifier = Modifier.semantics { contentDescription = previewDescription },
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = stringResource(R.string.appearance_wallpaper_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 焦点：3x3 九宫格，选裁剪时优先保留的位置
// ---------------------------------------------------------------------------

private val FocusAlignments = listOf(
    0f to 0f, 0.5f to 0f, 1f to 0f,
    0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f,
    0f to 1f, 0.5f to 1f, 1f to 1f,
)

@Composable
private fun FocusPointGrid(
    alignmentX: Float,
    alignmentY: Float,
    onSelect: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FocusAlignments.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { (x, y) ->
                    val isSelected = kotlin.math.abs(x - alignmentX) < 0.01f &&
                        kotlin.math.abs(y - alignmentY) < 0.01f
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(x, y) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 20.dp else 12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 滑块：拖动过程只改本地状态，松手才写设置，避免每帧落盘
// ---------------------------------------------------------------------------

private fun CardGroupScope.AppearanceSliderItem(
    title: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    supportingContent: Int? = null,
    showPercent: Boolean = true,
) {
    formItem {
        val safeValue = if (value.isFinite()) value.coerceIn(valueRange) else valueRange.start
        var local by remember(safeValue) { mutableStateOf(safeValue) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                if (supportingContent != null) Text(
                    stringResource(supportingContent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardGroupValue(if (showPercent)
                stringResource(R.string.appearance_value_percent, (local * 100).roundToInt())
                else stringResource(R.string.appearance_value_blur, local.roundToInt()))
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 壁纸文件：拷进应用私有目录，只存能长期用的路径
// ---------------------------------------------------------------------------

private const val WALLPAPER_DIR = "appearance_wallpapers"
private const val MAX_WALLPAPER_BYTES = 64L * 1024 * 1024
private const val WALLPAPER_COPY_BUFFER = 64 * 1024

private fun wallpaperDirectory(context: Context): File =
    File(context.filesDir, WALLPAPER_DIR).apply { if (!exists()) mkdirs() }

/**
 * 把系统相册/文件选择器给的 URI 拷到 `filesDir/appearance_wallpapers/`，返回 `file://` 地址。
 * 拷贝有大小上限，选到超大图时直接报错而不是把进程撑爆。
 */
private fun copyWallpaperIntoAppStorage(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty())
        ?: uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
        ?: "jpg"
    val target = File(wallpaperDirectory(context), "wallpaper-${java.util.UUID.randomUUID()}.$extension")
    try {
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(WALLPAPER_COPY_BUFFER)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_WALLPAPER_BYTES) {
                        error("image too large (${total / 1024 / 1024}MB)")
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }
        if (target.length() == 0L) error("empty image")
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
    return target.toUri().toString()
}

/** 只删我们自己目录里的旧壁纸，网络地址和别的目录文件一律不碰。 */
private fun releaseOwnedWallpaper(context: Context, path: String) {
    val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return
    if (uri.scheme != "file") return
    val file = uri.path?.let(::File)?.canonicalFile ?: return
    val ownedDirectory = wallpaperDirectory(context).canonicalFile
    if (file.parentFile != ownedDirectory) return
    runCatching { file.delete() }
}

private fun ChatUiStyle.labelRes(): Int = when (this) {
    ChatUiStyle.CLASSIC -> R.string.appearance_style_classic
    ChatUiStyle.STUDIO -> R.string.appearance_style_studio
    ChatUiStyle.FOCUS -> R.string.appearance_style_focus
}

private fun ChatUiStyle.descRes(): Int = when (this) {
    ChatUiStyle.CLASSIC -> R.string.appearance_style_classic_desc
    ChatUiStyle.STUDIO -> R.string.appearance_style_studio_desc
    ChatUiStyle.FOCUS -> R.string.appearance_style_focus_desc
}

private fun WallpaperScale.labelRes(): Int = when (this) {
    WallpaperScale.CROP -> R.string.appearance_wallpaper_scale_crop
    WallpaperScale.FIT -> R.string.appearance_wallpaper_scale_fit
}

// 只画缩略图选择器，截个图就能看三种风格长什么样（不依赖 Activity 结果回调）
@Preview(showBackground = true)
@Composable
private fun ChatUiStyleSelectorPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatUiStyleSelector(selected = ChatUiStyle.STUDIO, onSelect = {})
                ChatUiStyleSelector(selected = ChatUiStyle.CLASSIC, onSelect = {})
                ChatUiStyleSelector(selected = ChatUiStyle.FOCUS, onSelect = {})
                FocusPointGrid(alignmentX = 0.5f, alignmentY = 0f, onSelect = { _, _ -> })
            }
        }
    }
}
