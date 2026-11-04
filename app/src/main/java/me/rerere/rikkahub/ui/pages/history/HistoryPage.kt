package me.rerere.rikkahub.ui.pages.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.ConversationAgeFilter
import me.rerere.rikkahub.data.organization.ConversationFilterState
import me.rerere.rikkahub.data.organization.ConversationScope
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.SwipeToDelete
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val deleteReport by vm.deleteReport.collectAsStateWithLifecycle()

    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)
    val snackDeleteFailed = stringResource(R.string.r16_history_delete_failed)

    // 返回键先退多选，而不是直接离开历史页
    BackHandler(enabled = state.selectionMode) {
        vm.exitSelectionMode()
    }

    // 批量删除结果：删了多少照实说，失败的还留在选择里等用户再决定
    LaunchedEffect(deleteReport) {
        val report = deleteReport ?: return@LaunchedEffect
        val message = if (report.isComplete) {
            context.getString(R.string.r16_history_delete_complete, report.deleted)
        } else {
            context.getString(
                R.string.r16_history_delete_partial,
                report.deleted,
                report.total,
                report.failed.size,
            )
        }
        vm.clearDeleteReport()
        snackbarHostState.showSnackbar(message = message, withDismissAction = true)
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.r16_history_selected_count, state.selectedCount))
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.exitSelectionMode() }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.r16_history_exit_selection),
                            )
                        }
                    },
                    actions = {
                        if (state.selectedPinnedCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.r16_history_selected_pinned,
                                    state.selectedPinnedCount,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.history_page_title))
                    },
                    navigationIcon = {
                        BackButton()
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        IconButton(
                            onClick = { vm.enterSelectionMode() },
                            enabled = !state.busy,
                        ) {
                            Icon(
                                HugeIcons.CheckList,
                                contentDescription = stringResource(R.string.r16_history_enter_selection),
                            )
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            },
                            enabled = !state.busy && state.filter.scope == ConversationScope.CURRENT_ASSISTANT,
                        ) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.history_page_delete_all),
                            )
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
            }
        },
        bottomBar = {
            if (state.selectionMode) {
                HistorySelectionBar(
                    selectedCount = state.selectedCount,
                    pinnedCount = state.selectedPinnedCount,
                    canSelectAll = state.canSelectAll,
                    busy = state.busy,
                    onSelectAll = { vm.selectAllVisible() },
                    onClear = { vm.clearSelection() },
                    onDelete = { showDeleteSelectedDialog = true },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = CustomColors.pageContainerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize(),
        ) {
            HistoryFilterBar(
                filter = state.filter,
                enabled = !state.busy,
                onQueryChange = { vm.setQuery(it) },
                onAgeChange = { vm.setAgeFilter(it) },
                onScopeChange = { vm.setScope(it) },
            )

            if (state.conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.r16_history_empty),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        if (state.selectionMode) {
                            SelectableConversationItem(
                                conversation = conversation,
                                selected = conversation.id in state.selectedIds,
                                enabled = !state.busy,
                                onToggle = { vm.toggleSelection(conversation.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(),
                            )
                        } else {
                            SwipeableConversationItem(
                                conversation = conversation,
                                onClick = {
                                    navigateToChatPage(navController, conversation.id)
                                },
                                onDelete = {
                                    if (state.busy) return@SwipeableConversationItem
                                    scope.launch {
                                        // 先取完整会话用于撤销；删除要 await 完再提示，避免撤销和删除打架
                                        val fullConversation =
                                            vm.getFullConversation(conversation.id) ?: conversation
                                        if (!vm.deleteConversation(conversation)) {
                                            snackbarHostState.showSnackbar(snackDeleteFailed)
                                            return@launch
                                        }
                                        val result = snackbarHostState.showSnackbar(
                                            message = snackMessageDeleted,
                                            actionLabel = snackMessageUndo,
                                            withDismissAction = true,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            vm.restoreConversation(fullConversation)
                                        }
                                    }
                                },
                                onTogglePin = { vm.togglePinStatus(conversation.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        scope.launch { vm.deleteAllConversations() }
                    },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        val scopeLabel = stringResource(
            when (state.filter.scope) {
                ConversationScope.CURRENT_ASSISTANT -> R.string.r16_history_filter_current
                ConversationScope.ALL_ASSISTANTS -> R.string.r16_history_filter_all
            }
        )
        val ageLabel = stringResource(
            when (state.filter.age) {
                ConversationAgeFilter.ALL -> R.string.r16_history_age_all
                ConversationAgeFilter.UNUSED_7_DAYS -> R.string.r16_history_age_7
                ConversationAgeFilter.UNUSED_30_DAYS -> R.string.r16_history_age_30
                ConversationAgeFilter.UNUSED_90_DAYS -> R.string.r16_history_age_90
            }
        )
        AlertDialog(
            onDismissRequest = { if (!state.busy) showDeleteSelectedDialog = false },
            title = { Text(stringResource(R.string.r16_history_delete_selected_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.r16_history_delete_selected_message,
                        state.selectedCount,
                        scopeLabel,
                        ageLabel,
                        state.selectedPinnedCount,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSelectedDialog = false
                        scope.launch { vm.deleteSelected() }
                    },
                    enabled = !state.busy && state.selectedCount > 0,
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteSelectedDialog = false },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

/**
 * 搜索 + 筛选。标题搜索单独一行，筛选 chips 横向滚动：
 * 小屏一行塞六个 chip 会挤成一团，滚一滚比缩字好。
 */
@Composable
private fun HistoryFilterBar(
    filter: ConversationFilterState,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onAgeChange: (ConversationAgeFilter) -> Unit,
    onScopeChange: (ConversationScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopes = listOf(
        ConversationScope.CURRENT_ASSISTANT to R.string.r16_history_filter_current,
        ConversationScope.ALL_ASSISTANTS to R.string.r16_history_filter_all,
    )
    val ages = listOf(
        ConversationAgeFilter.ALL to R.string.r16_history_age_all,
        ConversationAgeFilter.UNUSED_7_DAYS to R.string.r16_history_age_7,
        ConversationAgeFilter.UNUSED_30_DAYS to R.string.r16_history_age_30,
        ConversationAgeFilter.UNUSED_90_DAYS to R.string.r16_history_age_90,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text(stringResource(R.string.r16_history_search_hint)) },
            leadingIcon = {
                Icon(HugeIcons.Search01, contentDescription = null)
            },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        enabled = enabled,
                    ) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.r16_history_clear_search),
                        )
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            scopes.forEach { (scope, label) ->
                FilterChip(
                    selected = filter.scope == scope,
                    onClick = { onScopeChange(scope) },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            ages.forEach { (age, label) ->
                FilterChip(
                    selected = filter.age == age,
                    onClick = { onAgeChange(age) },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

/** 多选时贴在底部的操作条：左边是已选数量，右边是最多三个动作。 */
@Composable
private fun HistorySelectionBar(
    selectedCount: Int,
    pinnedCount: Int,
    canSelectAll: Boolean,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.r16_history_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pinnedCount > 0) {
                    Text(
                        text = stringResource(R.string.r16_history_selected_pinned, pinnedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = onSelectAll, enabled = canSelectAll) {
                    Text(stringResource(R.string.r16_history_select_all))
                }
                IconButton(onClick = onClear, enabled = selectedCount > 0) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.r16_history_clear_selection),
                    )
                }
            }

            Button(onClick = onDelete, enabled = selectedCount > 0 && !busy) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.history_page_delete))
            }
        }
    }
}

/** 多选模式下的行：整行点击切换选择，置顶只显示标记，不能在这里顺手取消置顶。 */
@Composable
private fun SelectableConversationItem(
    conversation: Conversation,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        enabled = enabled,
        tonalElevation = if (selected) 6.dp else 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier,
    ) {
        ListItem(
            leadingContent = {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                )
            },
            headlineContent = {
                ConversationTitle(conversation)
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    SwipeToDelete(
        onDelete = onDelete,
        modifier = modifier,
        shape = RoundedCornerShape(25),
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier
    ) {
        ListItem(
            headlineContent = {
                ConversationTitle(conversation)
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun ConversationTitle(conversation: Conversation) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (conversation.isPinned) {
            Icon(
                imageVector = HugeIcons.Pin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                .trim(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
