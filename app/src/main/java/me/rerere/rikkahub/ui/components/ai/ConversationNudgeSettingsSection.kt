package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.agent.ConversationNudge
import me.rerere.ai.agent.NudgeTone
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ConversationNudgeSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.formItem
import me.rerere.rikkahub.ui.components.ui.switchItem

/**
 * m3「会话鞭策 / 推进提醒」的会话内设置块（纯展示，读写都交给调用方）。
 *
 * 单独成一个组，不塞进输入辅助那一组：它不改用户原话，也不调第二个模型做评审，
 * 只在一轮对话正常结束前追加一条内部提醒。文案上必须说清楚三件事，缺一件就是误导：
 * 1. 这是**启发式**判断，会误判（所以列了不触发的场景）；
 * 2. 会**多看几次模型**，但只在原本的步数预算里，不会突破上限；
 * 3. 目标与授权不变：不插"继续"消息、不另开会话、不重放工具、不动权限。
 *
 * 没有状态：所有改动都通过 [onChange] 写回当前会话的 runtime 配置。
 */
@Composable
fun ConversationNudgeSettingsSection(
    settings: ConversationNudgeSettings,
    enabled: Boolean,
    onChange: (ConversationNudgeSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val commit: (ConversationNudgeSettings) -> Unit = { next -> if (enabled) onChange(next.normalized()) }
    CardGroup(modifier = modifier, title = { Text(stringResource(R.string.m3_nudge_group)) }) {
        switchItem(
            checked = settings.enabled,
            onCheckedChange = { checked -> commit(settings.copy(enabled = checked)) },
            enabled = enabled,
            headlineContent = { Text(stringResource(R.string.m3_nudge_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.m3_nudge_desc))
                    Text(
                        text = stringResource(R.string.m3_nudge_limits),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        // 关闭时下面两项置灰而不是隐藏：用户能看见范围与语气，知道打开后会多什么
        formItem(
            headlineContent = { Text(stringResource(R.string.m3_nudge_attempts_title)) },
            supportingContent = { Text(stringResource(R.string.m3_nudge_attempts_desc)) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (ConversationNudge.ATTEMPT_RANGE).forEach { attempts ->
                    FilterChip(
                        selected = ConversationNudge.clampAttempts(settings.maxAttempts) == attempts,
                        onClick = { commit(settings.copy(maxAttempts = attempts)) },
                        enabled = enabled && settings.enabled,
                        label = { Text(attempts.toString()) },
                    )
                }
            }
        }
        formItem(
            headlineContent = { Text(stringResource(R.string.m3_nudge_tone_title)) },
            supportingContent = {
                Text(
                    when (settings.tone) {
                        NudgeTone.ENCOURAGING -> stringResource(R.string.m3_nudge_tone_encouraging_desc)
                        NudgeTone.NEUTRAL -> stringResource(R.string.m3_nudge_tone_neutral_desc)
                    }
                )
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.tone == NudgeTone.ENCOURAGING,
                    onClick = { commit(settings.copy(tone = NudgeTone.ENCOURAGING)) },
                    enabled = enabled && settings.enabled,
                    label = { Text(stringResource(R.string.m3_nudge_tone_encouraging)) },
                )
                FilterChip(
                    selected = settings.tone == NudgeTone.NEUTRAL,
                    onClick = { commit(settings.copy(tone = NudgeTone.NEUTRAL)) },
                    enabled = enabled && settings.enabled,
                    label = { Text(stringResource(R.string.m3_nudge_tone_neutral)) },
                )
            }
        }
        NudgeCustomInstructionItem(
            settings = settings,
            enabled = enabled && settings.enabled,
            onCommit = commit,
        )
    }
}

/**
 * 自定义附加语。输入框保留本地草稿，失焦或点「保存」才提交，
 * 避免每敲一个字就写一次会话配置（每次写都要走一遍落库）。
 */
@Composable
private fun NudgeCustomInstructionItem(
    settings: ConversationNudgeSettings,
    enabled: Boolean,
    onCommit: (ConversationNudgeSettings) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(settings.customInstruction.orEmpty()) }
    formItem(
        headlineContent = { Text(stringResource(R.string.m3_nudge_custom_title)) },
        supportingContent = { Text(stringResource(R.string.m3_nudge_custom_desc)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!editing) {
                TextButton(
                    onClick = {
                        draft = settings.customInstruction.orEmpty()
                        editing = true
                    },
                    enabled = enabled,
                ) {
                    Text(
                        text = settings.customInstruction?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.m3_nudge_custom_title),
                    )
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    enabled = enabled,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onCommit(settings.copy(customInstruction = draft.trim().takeIf { it.isNotEmpty() }))
                            editing = false
                        },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.m3_nudge_custom_save))
                    }
                    TextButton(
                        onClick = {
                            onCommit(settings.copy(customInstruction = null))
                            draft = ""
                            editing = false
                        },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.m3_nudge_custom_clear))
                    }
                }
            }
        }
    }
}
