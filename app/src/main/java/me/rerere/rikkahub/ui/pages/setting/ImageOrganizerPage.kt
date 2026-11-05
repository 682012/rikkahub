package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.organization.ImageCategory
import me.rerere.rikkahub.data.organization.ImageOrganizerItem
import me.rerere.rikkahub.data.organization.ImageSource
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

/**
 * 图片整理页：App 自己目录里的上传图 / 生成图 / 媒体创作图。
 *
 * 设计取舍：
 * - 只读本地文件，进入页面扫一次；识别必须显式点按钮并且先确认发送范围与计费。
 * - 识别结果只是建议，保留 / 删除都由人决定，删除一定要过确认弹窗。
 * - 顶部保持两条：搜索 + 识别模型，来源与类别芯片合并成一行横向滚动。
 * - 选择只作用在当前筛选结果上，切筛选时自动丢掉被隐藏的勾选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageOrganizerPage(
    modifier: Modifier = Modifier,
    vm: ImageOrganizerVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<ImageSource?>(null) }
    var categoryFilter by remember { mutableStateOf<ImageCategory?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingClassifyIds by remember { mutableStateOf<Set<String>?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var showNoModelDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 只有支持图片输入 + CHAT 的模型才列出来，且这是独立选择，不写聊天模型。
    val visionProviders = remember(settings.providers) {
        settings.providers.filter { it.enabled }.mapNotNull { provider ->
            val models = provider.models.filter {
                it.type == ModelType.CHAT && it.inputModalities.contains(Modality.IMAGE)
            }
            if (models.isEmpty()) null else provider.copyProvider(models = models)
        }
    }
    val selectedModelInfo = remember(settings.providers, state.selectedModelId) {
        val id = state.selectedModelId ?: return@remember null
        settings.providers.firstNotNullOfOrNull { provider ->
            provider.models.firstOrNull { it.id == id }?.let { provider to it }
        }
    }

    // 来源 + 类别 + 搜索名/描述
    val filtered = remember(state.items, query, sourceFilter, categoryFilter) {
        val keyword = query.trim()
        state.items.filter { item ->
            val sourceOk = sourceFilter == null || item.source == sourceFilter
            val categoryOk = when (categoryFilter) {
                null -> true
                ImageCategory.UNRECOGNIZED -> item.category == null || item.category == ImageCategory.UNRECOGNIZED
                else -> item.category == categoryFilter
            }
            val keywordOk = keyword.isEmpty() ||
                item.name.contains(keyword, ignoreCase = true) ||
                item.description?.contains(keyword, ignoreCase = true) == true
            sourceOk && categoryOk && keywordOk
        }
    }
    val filteredIds = remember(filtered) { filtered.mapTo(LinkedHashSet()) { it.id } }
    val itemById = remember(state.items) { state.items.associateBy { it.id } }
    val selectedItems = remember(selectedIds, itemById) { selectedIds.mapNotNull { itemById[it] } }
    val deletableIds = remember(selectedItems) {
        selectedItems.filter { it.deletable && !it.protected }.mapTo(LinkedHashSet()) { it.id }
    }
    val selectedBytes = selectedItems.sumOf { it.bytes }
    val allSelectedProtected = selectedItems.isNotEmpty() && selectedItems.all { it.protected }

    // 进页面只扫本地，不发请求
    LaunchedEffect(Unit) { vm.refresh() }

    // 筛选变了就丢掉被隐藏的勾选，避免删到看不见的图
    LaunchedEffect(filteredIds) { selectedIds = selectedIds intersect filteredIds }

    LaunchedEffect(state.error) {
        state.error?.let {
            toaster.show(message = it, type = ToastType.Error)
            vm.dismissError()
        }
    }

    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    fun toggle(id: String) {
        if (deleting) return
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun requestClassify(ids: Set<String>) {
        if (ids.isEmpty()) return
        if (state.selectedModelId == null) {
            showNoModelDialog = true
            return
        }
        pendingClassifyIds = ids
    }

    val progress = state.progress

    // 删除确认：明确张数 / 大小 / 影响，不自动删
    pendingDeleteIds?.let { ids ->
        val targets = state.items.filter { it.id in ids }
        val bytes = targets.sumOf { it.bytes }
        val protectedCount = targets.count { it.protected }
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDeleteIds = null },
            title = { Text(stringResource(R.string.r16_images_delete_title, targets.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.r16_images_delete_message,
                            bytes.fileSizeToString(),
                        )
                    )
                    if (protectedCount > 0) {
                        Text(
                            text = stringResource(R.string.r16_images_delete_skipped_note, protectedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (targets.none { it.deletable && !it.protected }) {
                        Text(
                            text = stringResource(R.string.r16_images_nothing_deletable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting && targets.any { it.deletable && !it.protected },
                    onClick = {
                        pendingDeleteIds = null
                        deleting = true
                        scope.launch {
                            try {
                                val result = vm.delete(ids)
                                // 失败项保留勾选，方便再试
                                selectedIds = result.failed.mapTo(LinkedHashSet()) { it.id }
                                toaster.show(
                                    message = context.getString(
                                        R.string.r16_images_delete_result,
                                        result.deleted.size,
                                        result.failed.size,
                                        result.skipped.size,
                                    ),
                                    type = if (result.failed.isEmpty()) ToastType.Success else ToastType.Warning,
                                )
                                if (result.failed.isNotEmpty()) {
                                    toaster.show(
                                        message = context.getString(R.string.r16_images_delete_failed_kept),
                                        type = ToastType.Warning,
                                    )
                                }
                                result.warnings.forEach { warning ->
                                    toaster.show(message = warning, type = ToastType.Info)
                                }
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.r16_images_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIds = null }, enabled = !deleting) {
                    Text(stringResource(R.string.r16_images_cancel))
                }
            },
        )
    }

    // 识别确认：先讲清楚发给谁、按谁计费、发多少张
    pendingClassifyIds?.let { ids ->
        val count = ids.size
        val providerName = selectedModelInfo?.first?.name ?: "?"
        val modelName = selectedModelInfo?.second?.displayName ?: "?"
        AlertDialog(
            onDismissRequest = { pendingClassifyIds = null },
            title = { Text(stringResource(R.string.r16_images_classify_title, count)) },
            text = {
                Text(stringResource(R.string.r16_images_classify_message, providerName, modelName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClassifyIds = null
                    selectedIds = emptySet()
                    vm.classify(ids)
                }) { Text(stringResource(R.string.r16_images_classify_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClassifyIds = null }) {
                    Text(stringResource(R.string.r16_images_cancel))
                }
            },
        )
    }

    if (showNoModelDialog) {
        AlertDialog(
            onDismissRequest = { showNoModelDialog = false },
            title = { Text(stringResource(R.string.r16_images_no_model_title)) },
            text = { Text(stringResource(R.string.r16_images_no_model_message)) },
            confirmButton = {
                TextButton(onClick = { showNoModelDialog = false }) {
                    Text(stringResource(R.string.r16_images_classify_confirm))
                }
            },
        )
    }

    // 大图预览后再决定保留 / 删除
    val previewItem = previewId?.let { itemById[it] }
    if (previewItem != null) {
        ImagePreviewDialog(
            images = listOf("file://${previewItem.path}"),
            onDismissRequest = { previewId = null },
            actions = {
                IconButton(onClick = {
                    vm.setProtected(setOf(previewItem.id), !previewItem.protected)
                }) {
                    Icon(
                        imageVector = HugeIcons.Favourite,
                        contentDescription = stringResource(
                            if (previewItem.protected) R.string.r16_images_unkeep else R.string.r16_images_keep
                        ),
                        tint = if (previewItem.protected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (previewItem.deletable && !previewItem.protected) {
                    IconButton(onClick = {
                        previewId = null
                        pendingDeleteIds = setOf(previewItem.id)
                    }) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.r16_images_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.r16_images_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { vm.refresh() },
                        enabled = !state.scanning,
                    ) {
                        Icon(HugeIcons.Refresh01, stringResource(R.string.r16_images_refresh))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(HugeIcons.MoreVertical, stringResource(R.string.r16_images_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.r16_images_select_all)) },
                                enabled = filteredIds.isNotEmpty(),
                                leadingIcon = { Icon(HugeIcons.Tick02, null) },
                                onClick = {
                                    showMenu = false
                                    selectedIds = filteredIds
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.r16_images_classify_filtered, filteredIds.size)
                                    )
                                },
                                enabled = filteredIds.isNotEmpty() && progress == null && !state.busy,
                                leadingIcon = { Icon(HugeIcons.MagicWand01, null) },
                                onClick = {
                                    showMenu = false
                                    requestClassify(filteredIds)
                                },
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                SelectionBar(
                    keepLabel = stringResource(
                        if (allSelectedProtected) R.string.r16_images_unkeep else R.string.r16_images_keep
                    ),
                    classifyEnabled = progress == null && !state.busy,
                    deleteEnabled = deletableIds.isNotEmpty() && progress == null && !deleting,
                    onKeep = { vm.setProtected(selectedIds, !allSelectedProtected) },
                    onClassify = { requestClassify(selectedIds) },
                    onDelete = { pendingDeleteIds = selectedIds },
                    onClear = { selectedIds = emptySet() },
                )
            }
        },
        containerColor = CustomColors.pageContainerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = CircleShape,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.r16_images_search_hint),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Search01, null, modifier = Modifier.size(18.dp))
                    },
                )
                // 识别模型：和聊天模型互不影响
                ModelSelector(
                    modelId = state.selectedModelId,
                    providers = visionProviders,
                    type = ModelType.CHAT,
                    modifier = Modifier.widthIn(max = 132.dp),
                    onSelect = { vm.setModel(it.id) },
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.r19_images_workers), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.r19_images_workers_hint), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.setWorkers(state.workers - 1) }, enabled = !state.busy && state.workers > 1) { Text("−") }
                Text(state.workers.toString(), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.setWorkers(state.workers + 1) }, enabled = !state.busy && state.workers < 16) { Text("+") }
            }
            FilterChipsRow(
                sourceFilter = sourceFilter,
                categoryFilter = categoryFilter,
                onSource = { sourceFilter = it },
                onCategory = { categoryFilter = it },
            )

            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.r16_images_selected,
                            selectedIds.size,
                            selectedBytes.fileSizeToString(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { selectedIds = filteredIds }) {
                        Text(stringResource(R.string.r16_images_select_all))
                    }
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text(stringResource(R.string.r16_images_clear_selection))
                    }
                }
            }

            // 识别进度 + 取消，不在渲染里自动发请求
            progress?.let { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.r16_images_progress, p.done, p.total, p.failed),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { vm.cancelClassification() }) {
                            Text(stringResource(R.string.r16_images_progress_stop))
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.items.isEmpty()) {
                EmptyState(
                    text = stringResource(
                        if (state.scanning) R.string.r16_images_scanning else R.string.r16_images_empty
                    ),
                    modifier = Modifier.weight(1f),
                )
            } else if (filtered.isEmpty()) {
                EmptyState(
                    text = stringResource(R.string.r16_images_empty_filtered),
                    actionLabel = stringResource(R.string.r16_images_filter_clear),
                    onAction = {
                        query = ""
                        sourceFilter = null
                        categoryFilter = null
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(items = filtered, key = { it.id }) { item ->
                        ImageTile(
                            item = item,
                            selectionMode = selectedIds.isNotEmpty(),
                            selected = item.id in selectedIds,
                            onToggle = { toggle(item.id) },
                            onPreview = { previewId = item.id },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    sourceFilter: ImageSource?,
    categoryFilter: ImageCategory?,
    onSource: (ImageSource?) -> Unit,
    onCategory: (ImageCategory?) -> Unit,
) {
    val sourceOptions = listOf(
        null to stringResource(R.string.r16_images_all_sources),
        ImageSource.UPLOAD to stringResource(R.string.r16_images_source_upload),
        ImageSource.GEN_MEDIA to stringResource(R.string.r16_images_source_generated),
        ImageSource.MEDIA_CREATION to stringResource(R.string.r16_images_source_media),
    )
    val categoryOptions = listOf(
        null to stringResource(R.string.r16_images_all_categories),
        ImageCategory.PORTRAIT to stringResource(R.string.r16_images_cat_portrait),
        ImageCategory.SCREENSHOT to stringResource(R.string.r16_images_cat_screenshot),
        ImageCategory.SCENERY to stringResource(R.string.r16_images_cat_scenery),
        ImageCategory.DESIGN to stringResource(R.string.r16_images_cat_design),
        ImageCategory.OTHER to stringResource(R.string.r16_images_cat_other),
        ImageCategory.UNRECOGNIZED to stringResource(R.string.r16_images_cat_unrecognized),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sourceOptions.forEach { (value, label) ->
            FilterChip(
                selected = sourceFilter == value,
                onClick = { onSource(value) },
                label = { Text(label, maxLines = 1) },
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        categoryOptions.forEach { (value, label) ->
            FilterChip(
                selected = categoryFilter == value,
                onClick = { onCategory(value) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageTile(
    item: ImageOrganizerItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else onPreview() },
                onLongClick = { if (!selectionMode) onToggle() },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            AsyncImage(
                model = item.file,
                contentDescription = stringResource(R.string.r16_images_preview_desc, item.name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (item.protected) {
                    TileBadge(
                        text = stringResource(R.string.r16_images_kept_badge),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (item.failed) {
                    TileBadge(
                        text = stringResource(R.string.r16_images_failed_badge),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        icon = true,
                    )
                }
            }

            if (item.category != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = item.category.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun TileBadge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: Boolean = false,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (icon) {
                Icon(
                    imageVector = HugeIcons.Alert01,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
private fun SelectionBar(
    keepLabel: String,
    classifyEnabled: Boolean,
    deleteEnabled: Boolean,
    onKeep: () -> Unit,
    onClassify: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = CustomColors.topBarColors.containerColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(HugeIcons.Favourite, keepLabel, true, onKeep, Modifier.weight(1f))
            BarAction(
                HugeIcons.MagicWand01,
                stringResource(R.string.r16_images_classify),
                classifyEnabled,
                onClassify,
                Modifier.weight(1f),
            )
            BarAction(
                HugeIcons.Delete01,
                stringResource(R.string.r16_images_delete),
                deleteEnabled,
                onDelete,
                Modifier.weight(1f),
            )
            BarAction(
                HugeIcons.Cancel01,
                stringResource(R.string.r16_images_clear_selection),
                true,
                onClear,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Image03,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ImageCategory?.label(): String = when (this) {
    ImageCategory.PORTRAIT -> stringResource(R.string.r16_images_cat_portrait)
    ImageCategory.SCREENSHOT -> stringResource(R.string.r16_images_cat_screenshot)
    ImageCategory.SCENERY -> stringResource(R.string.r16_images_cat_scenery)
    ImageCategory.DESIGN -> stringResource(R.string.r16_images_cat_design)
    ImageCategory.OTHER -> stringResource(R.string.r16_images_cat_other)
    ImageCategory.UNRECOGNIZED, null -> stringResource(R.string.r16_images_cat_unrecognized)
}
