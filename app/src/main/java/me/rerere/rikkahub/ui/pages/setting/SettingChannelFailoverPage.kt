package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.data.ai.channel.ChannelCandidateGroup
import me.rerere.rikkahub.data.ai.channel.ChannelTarget
import me.rerere.rikkahub.data.ai.channel.channelCandidateGroups
import me.rerere.rikkahub.data.ai.channel.channelCandidatesFor
import me.rerere.rikkahub.data.ai.channel.channelProtocolFamilyLabel
import me.rerere.rikkahub.data.ai.channel.channelRouteFamily
import me.rerere.rikkahub.data.ai.channel.channelTarget
import me.rerere.rikkahub.data.datastore.ChannelFailoverSettings
import me.rerere.rikkahub.data.datastore.ModelChannelRoute
import me.rerere.rikkahub.data.datastore.ModelChannelSlot
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 同模型多渠道优先级（fork）。
 *
 * 一个模型组 = 同一个模型（modelId 相同）在多个 provider 实例里的渠道，顺序即优先级。
 * 主聊天在这个模型上一旦遇到"这个渠道不行"的错误，就按顺序换下一个渠道重发这一次请求，
 * 工具步骤不重跑。开关默认关闭，关闭时主聊天完全走原来的单渠道路径。
 */
@Composable
fun SettingChannelFailoverPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val failover = settings.channelFailover
    val providers = settings.providers
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 正在为哪个模型组挑渠道（null = 没打开），以及新建组的选择框
    var pickerRoute by remember { mutableStateOf<String?>(null) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var deleteRoute by remember { mutableStateOf<String?>(null) }

    fun update(fn: (ChannelFailoverSettings) -> ChannelFailoverSettings) {
        vm.updateSettings { latest ->
            latest.copy(channelFailover = fn(latest.channelFailover).normalized())
        }
    }

    val groups = remember(providers) { channelCandidateGroups(providers) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("渠道优先级") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.pageContainerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("同模型多渠道") }) {
                    switchItem(
                        checked = failover.enabled,
                        onCheckedChange = { on -> update { it.copy(enabled = on) } },
                        headlineContent = { Text("按列表顺序自动切换渠道") },
                        supportingContent = {
                            Text("只使用下面这些你自己排过序的渠道。只在还没产出内容时切换；已经出过内容就报错，不重发。")
                        },
                    )
                    formItem(
                        headlineContent = { Text("排序规则") },
                        supportingContent = {
                            Text(
                                "严格按列表从上到下调用，排第一的就是首选，会话里选中的渠道不参与排序。" +
                                    "鉴权失败、额度不足、服务端 5xx/429、连不上、空响应会切下一个；" +
                                    "请求格式错误、你主动取消、网络整体不可用时不会切换。"
                            )
                        },
                    ) {
                        Text(
                            text = if (failover.routes.isEmpty()) {
                                "还没有配置渠道组。同一个模型在多个提供商里都添加过时，才能排优先级。"
                            } else {
                                "已配置 ${failover.routes.size} 个模型组。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    formItem(
                        content = {},
                        headlineContent = { Text("需要注意") },
                        supportingContent = {
                            Text(
                                "换渠道会用另一个提供商的凭据重新请求，可能额外计费。" +
                                    "不同协议族（例如 OpenAI 兼容与 Claude）的消息格式不一样，" +
                                    "跨协议切换无法保证请求正确，所以只在同一协议族里切换，添加渠道时也只列出同族的提供商。"
                            )
                        },
                    )
                }
            }

            items(failover.routes, key = { it.modelId }) { route ->
                ChannelRouteCard(
                    route = route,
                    providers = providers,
                    onMove = { from, to -> update { it.withChannelMoved(route.modelId, from, to) } },
                    onRemoveChannel = { slot -> update { it.withChannelRemoved(route.modelId, slot) } },
                    onAddChannel = { pickerRoute = route.modelId },
                    onDelete = { deleteRoute = route.modelId },
                )
            }

            item {
                CardGroup {
                    item(
                        onClick = { newGroupOpen = true },
                        headlineContent = { Text("新建模型组") },
                        supportingContent = { Text("同一个模型、同一协议族的渠道一次全部加入，再自己排序") },
                        leadingContent = {
                            CardGroupIcon(
                                HugeIcons.Add01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                }
            }
        }
    }

    if (newGroupOpen) {
        NewGroupDialog(
            groups = groups.filterNot { group ->
                failover.routes.any { it.matches(group.modelId) }
            },
            onDismiss = { newGroupOpen = false },
            onPick = { group ->
                newGroupOpen = false
                update {
                    it.withRoute(
                        ModelChannelRoute(
                            modelId = group.modelId,
                            channels = group.channels.map { channel ->
                                ModelChannelSlot(channel.providerId, channel.model.modelId)
                            },
                        ),
                    )
                }
            },
        )
    }

    pickerRoute?.let { modelId ->
        val route = failover.routeFor(modelId)
        // 跨协议族的渠道不能互换，挑候选时就把它们排除，免得用户排了一条永远用不上的渠道
        val family = channelRouteFamily(providers, modelId, route?.channels.orEmpty())
        val candidates = channelCandidatesFor(providers, modelId, family).filterNot { candidate ->
            route?.channels.orEmpty().any { slot ->
                slot.providerId.equals(candidate.providerId, ignoreCase = true) &&
                    slot.modelId.equals(candidate.model.modelId, ignoreCase = true)
            }
        }
        ModalBottomSheet(
            onDismissRequest = { pickerRoute = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    "为 ${route?.modelId ?: modelId} 添加渠道",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (family.isNotBlank()) {
                    Text(
                        "只列出同一协议族的提供商：${channelProtocolFamilyLabel(family)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(
                        "没有可添加的渠道了。需要先在同一个协议族的别的提供商里添加同名模型（modelId 相同）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        candidates.forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        candidate.providerSetting.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        candidate.model.displayName.ifBlank { candidate.model.modelId },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    update {
                                        it.withChannelAdded(
                                            modelId,
                                            ModelChannelSlot(candidate.providerId, candidate.model.modelId),
                                        )
                                    }
                                    pickerRoute = null
                                }) { Text("添加") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteRoute?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteRoute = null },
            title = { Text("删除模型组") },
            text = { Text("删除后这个模型不再自动切换渠道，回到原来的单渠道路径：$modelId") },
            confirmButton = {
                TextButton(onClick = {
                    update { it.withoutRoute(modelId) }
                    deleteRoute = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRoute = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ChannelRouteCard(
    route: ModelChannelRoute,
    providers: List<ProviderSetting>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemoveChannel: (ModelChannelSlot) -> Unit,
    onAddChannel: () -> Unit,
    onDelete: () -> Unit,
) {
    // 组的协议族由第一个能解析出来的渠道决定，和 resolveChannelPlan 的口径一致
    val family = channelRouteFamily(providers, route.modelId, route.channels)
    val resolved: List<ChannelTarget?> = route.channels.map { slot ->
        channelTarget(providers, slot.providerId, slot.modelId)
    }
    CardGroup(title = { Text("模型 · ${route.modelId}") }) {
        route.channels.forEachIndexed { index, slot ->
            val target = resolved.getOrNull(index)
            // 跨协议族的槽位不会被调用；这里只算出提示用的标签（非空 = 跨族）
            val crossFamilyLabel = target?.takeIf { it.protocolFamily != family }?.protocolFamilyLabel
            item(
                headlineContent = {
                    Text(
                        target?.providerSetting?.name ?: "已失效的渠道",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (target == null || crossFamilyLabel != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Text(
                        when {
                            target == null -> "这个提供商或模型已被删除/禁用，调用时会被跳过"
                            crossFamilyLabel != null -> "跨协议族（$crossFamilyLabel），和本组不互通，不会参与切换"
                            else -> "${target.model.displayName.ifBlank { target.model.modelId }} · 第 ${index + 1} 顺位"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    CardGroupIcon(
                        HugeIcons.ServerStack01,
                        containerColor = if (index == 0) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (index == 0) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                        ) { Icon(HugeIcons.ArrowUp01, contentDescription = "上移") }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < route.channels.lastIndex,
                        ) { Icon(HugeIcons.ArrowDown01, contentDescription = "下移") }
                        IconButton(onClick = { onRemoveChannel(slot) }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "移出这个渠道")
                        }
                    }
                },
            )
        }
        item(
            onClick = onAddChannel,
            headlineContent = { Text("添加渠道") },
            supportingContent = { Text("只列出同一模型、同一协议族的其它提供商") },
            leadingContent = {
                CardGroupIcon(
                    HugeIcons.Add01,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            },
        )
        item(
            onClick = onDelete,
            headlineContent = { Text("删除这个模型组") },
            leadingContent = {
                CardGroupIcon(
                    HugeIcons.Cancel01,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
        )
    }
}

@Composable
private fun NewGroupDialog(
    groups: List<ChannelCandidateGroup>,
    onDismiss: () -> Unit,
    onPick: (ChannelCandidateGroup) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建模型组") },
        text = {
            if (groups.isEmpty()) {
                Text("没有可用的候选。同一个模型需要在至少一个启用的提供商里存在；跨协议族的模型不会混在一组。")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${group.modelId} · ${channelProtocolFamilyLabel(group.protocolFamily)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    group.channels.joinToString("、") { it.providerSetting.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onPick(group) }) { Text("添加") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = null,
    )
}
