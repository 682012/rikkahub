package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.db.dao.UsageByModel
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 单会话用量明细。
 *
 * 数据来自 usage_record；主对话与子代理分开统计，这里一次性展示并按模型拆分。
 */
@Composable
fun UsageSheet(
    conversationId: String,
    onDismiss: () -> Unit,
) {
    val dao = remember { runCatching { getKoin().get<UsageRecordDAO>() }.getOrNull() }
    var totals by remember { mutableStateOf<UsageTotals?>(null) }
    var byModel by remember { mutableStateOf<List<UsageByModel>>(emptyList()) }

    LaunchedEffect(conversationId, dao) {
        if (dao == null) return@LaunchedEffect
        runCatching {
            totals = dao.totals(conversationId, includeSubagents = true)
            byModel = dao.byModel(conversationId, includeSubagents = true)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("本会话用量", style = MaterialTheme.typography.titleMedium)
            val t = totals
            if (dao == null) {
                Text("统计服务未初始化", style = MaterialTheme.typography.bodySmall)
            } else if (t == null || t.requests == 0L) {
                Text(
                    "还没有记录。这版之前产生的消息不会补记，之后的新请求会开始统计。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                UsageRow("生成记录数", t.requests.toString())
                UsageRow("输入 tokens", t.promptTokens.toString())
                UsageRow("输出 tokens", t.completionTokens.toString())
                UsageRow("缓存命中", t.cachedTokens.toString())
                UsageRow("合计 tokens", t.totalTokens.toString())
                if (t.promptTokens > 0) {
                    val rate = t.cachedTokens * 100.0 / t.promptTokens
                    UsageRow("缓存命中率", "%.1f%%".format(rate))
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    byModel.forEach { model ->
                        Column {
                            Text(model.modelId, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "记录 ${model.requests} · 输入 ${model.promptTokens} · 输出 ${model.completionTokens} · 缓存 ${model.cachedTokens}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
