package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.ConversationUsageGroup
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.java.KoinJavaComponent.getKoin

@Composable
fun UsageSheet(conversation: Conversation, onDismiss: () -> Unit) {
    val settings = LocalSettings.current
    val dao = remember { getKoin().get<UsageRecordDAO>() }
    val store = remember { getKoin().get<SettingsStore>() }
    val coroutineScope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(UsageScope.ALL) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember(conversation.id, retry) { mutableStateOf(false) }
    val groups by produceState<List<ConversationUsageGroup>?>(null, conversation.id, retry) {
        value = null
        dao.observeConversationGroups(conversation.id.toString())
            .catch { error = true }
            .collect { value = it }
    }
    val names = remember(settings.providers) {
        settings.providers.flatMap { it.models }.associate { it.id.toString() to it.displayName.ifBlank { it.modelId } }
    }
    val summary = remember(groups, filter, names) { summarizeConversationUsage(groups.orEmpty(), filter, names) }
    val last = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val context = DashboardContext(
        used = conversation.lastContextTokens(),
        capacity = settings.findModelById(last?.modelId)?.contextLength?.takeIf { it > 0 },
        approximate = last?.lastUsage == null,
    )
    fun setStudio(enabled: Boolean) {
        coroutineScope.launch { store.update { it.copy(displaySetting = it.displaySetting.copy(useStudioDisplay = enabled)) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when {
            error -> Column(Modifier.padding(24.dp)) {
                Text("用量暂时读取失败")
                TextButton(onClick = { retry++ }) { Text("重试") }
            }
            groups == null -> Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
            settings.displaySetting.useStudioDisplay -> UsageDashboard(
                summary.totals, summary.models, context, filter, { filter = it }, { setStudio(false) },
                modifier = Modifier.fillMaxWidth(),
            )
            else -> ClassicUsage(summarizeConversationUsage(groups.orEmpty(), UsageScope.ALL, names), onStudio = { setStudio(true) })
        }
    }
}

@Composable
private fun ClassicUsage(summary: ConversationUsageSummary, onStudio: () -> Unit) {
    val t = summary.totals
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本会话用量", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onStudio) { Text("工作台") }
        }
        Text("全部已报告请求（含子代理及辅助请求）", style = MaterialTheme.typography.bodySmall)
        listOf("生成记录数" to t.requests, "输入 tokens" to t.prompt, "输出 tokens" to t.output,
            "已报告缓存" to t.cached, "合计 tokens" to t.prompt + t.output).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text("$value") }
        }
        HorizontalDivider()
        if (t.requests == 0L) Text("暂无上游用量记录；未报告与历史未记录的请求不计入。")
        summary.models.forEach { model ->
            Text(model.name, style = MaterialTheme.typography.labelLarge)
            Text("${model.requests} 次请求 · ${model.tokens} tokens", style = MaterialTheme.typography.bodySmall)
        }
    }
}
