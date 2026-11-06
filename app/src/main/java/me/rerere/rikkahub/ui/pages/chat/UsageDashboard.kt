package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R
import java.text.NumberFormat
import java.util.Locale

@Composable
fun UsageDashboard(
    totals: DashboardTotals,
    models: List<DashboardModel>,
    context: DashboardContext,
    scope: UsageScope,
    onScopeChange: (UsageScope) -> Unit,
    onClassic: () -> Unit,
    modifier: Modifier = Modifier,
    /** 全部来源逐项用量；子代理/标题/摘要各自单列，默认的"主对话"口径不受它影响。 */
    sources: List<ConversationSourceUsage> = emptyList(),
) {
    val totalTokens = totals.prompt + totals.output
    var exact by remember { mutableStateOf(false) }
    if (exact) AlertDialog(
        onDismissRequest = { exact = false },
        confirmButton = { TextButton(onClick = { exact = false }) { Text("关闭") } },
        title = { Text("精确用量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入  ${formatNumber(totals.prompt)} tokens")
                Text("输出  ${formatNumber(totals.output)} tokens")
                Text("缓存  ${formatNumber(totals.cached)} tokens" + if (totals.invalidCacheReport) "（上游异常报告）" else "（已含在输入中）")
                Text("合计  ${formatNumber(totalTokens)} tokens")
                Text("每次请求都会重新计入输入。这里是累计消耗，不是当前上下文大小。未报告用量的请求不补造数据。", style = MaterialTheme.typography.bodySmall)
            }
        },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("usage-dashboard")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本对话",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = onClassic,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("usage-classic"),
            ) {
                Text("经典明细")
            }
        }

        // Scope filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UsageScope.entries.forEach { item ->
                val label = stringResource(
                    when (item) {
                        UsageScope.ALL -> R.string.r22_cache_scope_all
                        UsageScope.CHAT -> R.string.r22_cache_scope_chat
                        UsageScope.SUBAGENT -> R.string.r22_cache_scope_subagent
                        UsageScope.OTHER -> R.string.r22_cache_scope_aux
                    }
                )
                FilterChip(
                    selected = scope == item,
                    onClick = { onScopeChange(item) },
                    label = { Text(label) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("usage-scope-${item.name}"),
                )
            }
        }

        // Primary hero: 累计用量
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "累计用量",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatCompactTokens(totalTokens),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.testTag("usage-total").clickable { exact = true },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "tokens · ${totals.requests} 次已报告请求",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Two side-by-side tonal stat cells (input / output)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCell(
                title = "输入",
                value = formatCompactTokens(totals.prompt),
                note = "含缓存读取",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                title = "输出",
                value = formatCompactTokens(totals.output),
                note = "模型生成",
                modifier = Modifier.weight(1f),
            )
        }

        // Context card: 上次上下文
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("usage-context"),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "上次上下文",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (context.approximate && context.used != null) {
                        Text(
                            text = "估算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (context.used == null) {
                    Text(
                        text = "暂无上游报告",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (context.capacity != null && context.capacity > 0) {
                    val usedVal = context.used ?: 0
                    val ratio = (usedVal.toFloat() / context.capacity).coerceIn(0f, 1f)
                    Text(
                        text = "${formatNumber(usedVal.toLong())} / ${formatNumber(context.capacity.toLong())} tokens",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "${formatNumber(context.used?.toLong() ?: 0L)} tokens",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "上次输入＋输出；窗口来自模型设置，不是下次请求预算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Cache section: 已报告缓存 (subset of prompt)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "已报告缓存",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                // 命中率只认纯计算模型的结论：没报过缓存的模型不进分母，也绝不显示成 0%。
                val cache = totals.cache
                when {
                    totals.invalidCacheReport || cache.state == CacheHitRateState.INVALID -> Text(
                        stringResource(
                            R.string.r22_cache_invalid_report,
                            formatNumber(cache.cachedTokens.takeIf { it != 0L } ?: totals.cached),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    cache.hasRatio -> Text(
                        stringResource(
                            R.string.r22_cache_rate,
                            formatNumber(cache.cachedTokens),
                            cache.percentText.orEmpty(),
                            formatNumber(cache.eligiblePromptTokens),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("usage-cache-rate"),
                    )
                    else -> Text(
                        stringResource(R.string.r22_cache_unreported, formatNumber(totals.cached)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (cache.excludedPromptTokens > 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.r22_cache_excluded,
                            formatNumber(cache.excludedPromptTokens),
                            formatNumber(cache.excludedRequests),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.r22_cache_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Model breakdown
        if (models.isNotEmpty()) {
            Text(
                text = "模型分布",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val maxTokens = models.sumOf { it.tokens }.coerceAtLeast(1L)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                models.forEach { model ->
                    ModelItemRow(model = model, maxTokens = maxTokens)
                }
            }
        } else if (totals.requests == 0L) {
            Text(
                text = "暂无此范围下的用量记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 按来源单列：子代理和标题/摘要/快速模型用的是同一个 conversation_id，只能按 source 分开列。
        if (sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.r22_cache_sources_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { row -> SourceUsageRow(row) }
            }
            Text(
                text = stringResource(R.string.r22_cache_sources_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceUsageRow(row: ConversationSourceUsage) {
    val label = when (row.role) {
        UsageSourceRole.CHAT -> stringResource(R.string.r22_cache_source_chat)
        UsageSourceRole.SUBAGENT -> stringResource(R.string.r22_cache_source_subagent)
        UsageSourceRole.AUXILIARY -> stringResource(R.string.r22_cache_source_aux, row.source)
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("usage-source-${row.source}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = stringResource(
                R.string.r22_cache_source_row,
                row.requests,
                formatCompactTokens(row.prompt),
                formatCompactTokens(row.output),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCell(
    title: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelItemRow(model: DashboardModel, maxTokens: Long) {
    val ratio = (model.tokens.toFloat() / maxTokens).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.name.ifBlank { "未知模型" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "${formatNumber(model.tokens)} · ${model.requests} 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatCompactTokens(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

private fun formatNumber(count: Long): String {
    return NumberFormat.getIntegerInstance(Locale.US).format(count)
}
