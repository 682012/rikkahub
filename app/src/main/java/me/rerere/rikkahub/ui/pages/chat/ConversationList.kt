package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.grouping.GroupSummary
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.mirrorForRtl
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String,
        val sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()

    /**
     * 手动分组组头，插在**原列表**里组内第一条会话的位置上（不是另开一个区）。
     *
     * 组内成员不复制一份：展开后由组自己渲染成员行，主列表里对应的那几行让位（见
     * [visibleListRows]），所以「原列表折成组」和「关掉组回到原列表」是同一份顺序。
     */
    data class GroupHeader(
        val summary: GroupSummary,
        /** 组里成员的排序键最大的一条，用来在锚点消失时决定插在哪 */
        val anchorAt: Long,
        /** 组里最靠前那条是置顶的：置顶区排在所有未置顶会话之前 */
        val pinned: Boolean = false,
        val sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    // 折叠后同族还有别的会话时，行尾的数字角标点进来；null 表示这个场景不折叠（比如搜索结果）
    onOpenFamily: ((Conversation) -> Unit)? = null,
    // ---- 手动分组：原列表里的可展开组（就地折叠，不另开区）----
    // 为空表示不显示组（组内视角、家族视角、搜索结果都不显示）
    groups: List<GroupSummary> = emptyList(),
    /** 行内出现的组头 key（由 ViewModel 按成员锚点算出），点一下就地展开/收起 */
    inlineGroups: List<ConversationListItem.GroupHeader> = emptyList(),
    /** 某个组展开后要渲染的成员；纯展示层收集，不占种子的实时查询 */
    groupConversations: (Uuid) -> Flow<List<Conversation>> = { flowOf(emptyList()) },
    onToggleGroup: (Uuid) -> Unit = {},
    onOpenGroup: (Uuid) -> Unit = {},
    onRenameGroup: (Uuid) -> Unit = {},
    onDissolveGroup: (Uuid) -> Unit = {},
    // ---- 多选 ----
    selectionMode: Boolean = false,
    selectedIds: Set<Uuid> = emptySet(),
    onStartSelection: (Conversation) -> Unit = {},
    onToggleSelection: (Conversation) -> Unit = {},
) {
    // 组头现在插在列表内部（inlineGroups），列表顶部只有 header，位置计算不再跟着组数漂
    val headerCount = if (header != null) 1 else 0

    // 只在「换了会话」或「第一次拿到这条会话的行」时定位一次。
    // 以前把 itemCount 也算进 key，每次分页刷新都会重新跑一遍并可能硬拽列表，点一下就是一次卡顿。
    var scrollKey by remember { mutableStateOf<Uuid?>(null) }
    val rows = conversations.itemSnapshotList.items
    LaunchedEffect(current.id, rows.size) {
        if (scrollKey == current.id) return@LaunchedEffect
        val target = scrollTargetForKey(rows, headerCount, current.id, listState) ?: return@LaunchedEffect
        listState.scrollToItem(target)
        scrollKey = current.id
    }

    // 组头就地插在「组里最靠前那条会话」的位置上：先算好每个 index 上面该画哪个组
    // （index == 行数表示画在末尾），渲染时仍按分页的 index 走，不重建查询、不闪空
    val snapshotRows = conversations.itemSnapshotList.items
    // 分组头和列表用同一份排序依据：按创建时间排序时就按 create_at 定位，否则按 update_at
    val listSortOrder = inlineGroups.firstOrNull()?.sortOrder ?: ConversationSortOrder.UPDATE_TIME
    val groupPlacement = remember(snapshotRows, inlineGroups, listSortOrder) {
        inlineGroupPlacement(snapshotRows, inlineGroups) { conversation ->
            when (listSortOrder) {
                ConversationSortOrder.CREATE_TIME -> conversation.createAt.toEpochMilli()
                ConversationSortOrder.UPDATE_TIME -> conversation.updateAt.toEpochMilli()
            }
        }
    }
    val groupSection: @Composable (ConversationListItem.GroupHeader, Modifier) -> Unit = { header, sectionModifier ->
        ConversationGroupSection(
            summary = header.summary,
            conversations = groupConversations,
            current = current,
            selectionMode = selectionMode,
            selectedIds = selectedIds,
            onToggleGroup = onToggleGroup,
            onOpenGroup = onOpenGroup,
            onRenameGroup = onRenameGroup,
            onDissolveGroup = onDissolveGroup,
            onClick = onClick,
            onToggleSelection = onToggleSelection,
            onStartSelection = onStartSelection,
            modifier = sectionModifier,
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item(key = "header", contentType = "header") {
                header()
            }
        }

        if (conversations.itemCount == 0 &&
            inlineGroups.isEmpty() &&
            conversations.loadState.refresh is LoadState.NotLoading
        ) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    // key 带上排序方式，避免切换排序后列表锚定到另一种排序下的同日期分组而发生跳动
                    is ConversationListItem.DateHeader -> "date_${item.sortOrder}_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                    is ConversationListItem.GroupHeader -> "inline_group_${item.summary.id}"
                }
            }
        ) { index ->
            // 组头画在它锚点那行的上面，跟这一行同属一个 lazy item：
            // 组的位置永远守住「原列表里的位置」，展开/收起只改这一项的高度
            val rowGroups = groupPlacement[index].orEmpty()
            Column {
                rowGroups.forEach { header -> groupSection(header, Modifier.animateItem()) }
                when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.GroupHeader -> {
                    ConversationGroupSection(
                        summary = item.summary,
                        conversations = groupConversations,
                        current = current,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onToggleGroup = onToggleGroup,
                        onOpenGroup = onOpenGroup,
                        onRenameGroup = onRenameGroup,
                        onDissolveGroup = onDissolveGroup,
                        onClick = onClick,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                        modifier = Modifier.animateItem(),
                    )
                }

                is ConversationListItem.Item -> {
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        onOpenFamily = onOpenFamily?.takeIf { familyBadgeVisible(item.conversation.familySize) },
                        selectionMode = selectionMode,
                        isSelected = item.conversation.id in selectedIds,
                        onStartSelection = onStartSelection,
                        onToggleSelection = onToggleSelection,
                        modifier = Modifier.animateItem()
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
                }
            }
        }

        // 组里最新的一条比已加载的会话都旧：组头落到列表末尾，仍然在原列表里，不开新区
        val tailGroups = groupPlacement[conversations.itemCount].orEmpty()
        if (tailGroups.isNotEmpty()) {
            item(key = "inline_group_tail") {
                Column {
                    tailGroups.forEach { header -> groupSection(header, Modifier.animateItem()) }
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// 同族折叠后的数量角标：叠卡图标 + 条数，只当入口，不加文字标签
@Composable
private fun FamilyBadge(
    count: Int,
    onClick: () -> Unit
) {
    val description = stringResource(R.string.r21_autofile_family_badge, count)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Layers01,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onOpenFamily: ((Conversation) -> Unit)? = null,
    // 多选：进入多选模式后点一行是选中/取消，长按菜单里也能主动进多选
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onStartSelection: (Conversation) -> Unit = {},
    onToggleSelection: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        selected -> MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
        else -> Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50f))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    if (selectionMode) onToggleSelection(conversation) else onClick(conversation)
                },
                onLongClick = {
                    // Also clear chat input focus when the drawer is permanently visible.
                    focusManager.clearFocus(force = true)
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection(conversation) },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(4.dp))
            }
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))

            // 折叠后同族还有别的会话：行尾数字角标点开这一族
            if (onOpenFamily != null) {
                FamilyBadge(
                    count = conversation.familySize,
                    onClick = { onOpenFamily(conversation) },
                )
                Spacer(Modifier.size(6.dp))
            }

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = HugeIcons.Pin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics {
                            contentDescription = "Loading"
                        }
                )
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                        )
                    },
                    onClick = {
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            null
                        )
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Refresh01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_assistant))
                    },
                    onClick = {
                        onMoveToAssistant(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Forward02, null, modifier = Modifier.mirrorForRtl())
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_folder))
                    },
                    onClick = {
                        onMoveToFolder(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_group_select))
                    },
                    onClick = {
                        onStartSelection(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.CheckList, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, null)
                    }
                )
            }
        }
    }
}
