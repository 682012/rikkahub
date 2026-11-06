package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.grouping.GroupSummary
import me.rerere.rikkahub.data.organization.grouping.groupCountVisible
import me.rerere.rikkahub.data.organization.grouping.groupHasMoreThanInline
import me.rerere.rikkahub.data.organization.grouping.rowTapTogglesSelection
import me.rerere.rikkahub.utils.mirrorForRtl

// 抽屉列表里的「可展开组」。
//
// 组 = r21 已有的会话文件夹，组里放的是各自独立的会话（各自的上下文、各自的 lineage），
// 这里只是把它们在列表里收在一起：点组头展开/收起，组内每条仍然可以单独打开、单独移出组。
// 组件本身不读数据，全部由 ChatDrawer / ViewModel 传入。

private val GroupChildIndent = 20.dp

/** 组头：展开箭头 + 组名 + 条数 + 更多菜单（查看全部/重命名/解散分组）。 */
@Composable
fun ConversationGroupHeader(
    summary: GroupSummary,
    onToggle: () -> Unit,
    onOpenAll: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onToggle,
                    onLongClick = { menuExpanded = true },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (summary.expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = if (summary.expanded) {
                    stringResource(R.string.chat_page_group_collapse, summary.name)
                } else {
                    stringResource(R.string.chat_page_group_expand, summary.name)
                },
                modifier = Modifier
                    .size(16.dp)
                    .mirrorForRtl(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (groupCountVisible(summary.count)) {
                GroupCountBadge(count = summary.count)
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.chat_page_group_more, summary.name),
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_group_open_all, summary.count)) },
                        leadingIcon = { Icon(HugeIcons.Layers01, null) },
                        onClick = {
                            menuExpanded = false
                            onOpenAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_group_dissolve)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 组内条数角标。 */
@Composable
private fun GroupCountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * 组内的一条会话。
 *
 * 多选模式下点一下是选中/取消（不再打开会话），非多选模式点一下就是普通打开 —— 和列表里其它行一致；
 * 长按进多选，这样组里的单条也能被选中后「移出分组」，不必先跑到别的视图里找它。
 */
@Composable
fun GroupedConversationRow(
    conversation: Conversation,
    isCurrent: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPressSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surface
    }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = GroupChildIndent)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { if (rowTapTogglesSelection(selectionMode)) onToggleSelect() else onClick() },
                onLongClick = { if (!selectionMode) onLongPressSelect() },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_message) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (conversation.isPinned) {
            Icon(
                imageVector = HugeIcons.Pin,
                contentDescription = stringResource(R.string.pinned_chats),
                modifier = Modifier
                    .size(12.dp)
                    .alpha(0.8f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 列表里的一个组：组头 + 展开后的成员。
 *
 * 成员按「当前助手 + 这个组」查出来，是**彼此独立**的会话：点开哪条就进哪条自己的上下文，
 * 组只影响列表排布。成员数超过内联上限时组头菜单里还有「查看全部」进分页视图。
 */
@Composable
fun ConversationGroupSection(
    summary: GroupSummary,
    conversations: (Uuid) -> Flow<List<Conversation>>,
    current: Conversation,
    selectionMode: Boolean,
    selectedIds: Set<Uuid>,
    onToggleGroup: (Uuid) -> Unit,
    onOpenGroup: (Uuid) -> Unit,
    onRenameGroup: (Uuid) -> Unit,
    onDissolveGroup: (Uuid) -> Unit,
    onClick: (Conversation) -> Unit,
    onToggleSelection: (Conversation) -> Unit,
    onStartSelection: (Conversation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConversationGroupHeader(
            summary = summary,
            onToggle = { onToggleGroup(summary.id) },
            onOpenAll = { onOpenGroup(summary.id) },
            onRename = { onRenameGroup(summary.id) },
            onDelete = { onDissolveGroup(summary.id) },
        )

        if (summary.expanded) {
            val children by remember(summary.id) { conversations(summary.id) }
                .collectAsStateWithLifecycle(initialValue = emptyList())
            if (children.isEmpty()) {
                EmptyGroupHint()
            } else {
                children.forEach { child ->
                    GroupedConversationRow(
                        conversation = child,
                        isCurrent = child.id == current.id,
                        selectionMode = selectionMode,
                        selected = child.id in selectedIds,
                        onClick = { onClick(child) },
                        onToggleSelect = { onToggleSelection(child) },
                        onLongPressSelect = { onStartSelection(child) },
                    )
                }
                // 内联只渲染前 GROUP_INLINE_LIMIT 条，更大的组给一条进分页视图的入口
                if (groupHasMoreThanInline(summary.count)) {
                    TextButton(
                        onClick = { onOpenGroup(summary.id) },
                        modifier = Modifier.padding(start = GroupChildIndent),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_group_open_all, summary.count),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/** 组内容为空时的占位（新建但还没归入会话，或组里的会话都被移走了）。 */
@Composable
fun EmptyGroupHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_page_group_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = GroupChildIndent + 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

/**
 * 多选操作条：显示已选条数，提供「移入分组」「移出分组」「取消」。
 * 只做展示，具体动作由 ChatDrawer 接线到 ViewModel / ChatService。
 */
@Composable
fun GroupSelectionBar(
    selectedCount: Int,
    onMoveToGroup: () -> Unit,
    onRemoveFromGroup: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.chat_page_group_selection_cancel),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(R.string.chat_page_group_selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onMoveToGroup, enabled = selectedCount > 0) {
                Text(stringResource(R.string.chat_page_group_move_in), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onRemoveFromGroup, enabled = selectedCount > 0) {
                Text(stringResource(R.string.chat_page_group_move_out), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
