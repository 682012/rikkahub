package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.agent.ConversationNudge
import me.rerere.ai.agent.NudgeTone
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ConversationNudgeSettings

@Composable
fun ConversationNudgeSettingsSection(
    settings: ConversationNudgeSettings,
    enabled: Boolean,
    onChange: (ConversationNudgeSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(settings.customInstruction) {
        mutableStateOf(settings.customInstruction.orEmpty())
    }
    val commit: (ConversationNudgeSettings) -> Unit = { if (enabled) onChange(it.normalized()) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.m3_nudge_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                Switch(checked = settings.enabled, enabled = enabled,
                    onCheckedChange = { commit(settings.copy(enabled = it)) })
            }
            Text(stringResource(R.string.m3_nudge_desc), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.m3_nudge_limits), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.m3_nudge_attempts_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationNudge.ATTEMPT_RANGE.forEach { attempts ->
                    FilterChip(selected = settings.maxAttempts == attempts,
                        enabled = enabled && settings.enabled,
                        onClick = { commit(settings.copy(maxAttempts = attempts)) },
                        label = { Text(attempts.toString()) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NudgeTone.entries.forEach { tone ->
                    FilterChip(selected = settings.tone == tone,
                        enabled = enabled && settings.enabled,
                        onClick = { commit(settings.copy(tone = tone)) },
                        label = { Text(stringResource(if (tone == NudgeTone.ENCOURAGING)
                            R.string.m3_nudge_tone_encouraging else R.string.m3_nudge_tone_neutral)) })
                }
            }
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) },
                modifier = Modifier.fillMaxWidth(), enabled = enabled && settings.enabled,
                label = { Text(stringResource(R.string.m3_nudge_custom_title)) }, maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = enabled && settings.enabled,
                    onClick = { commit(settings.copy(customInstruction = draft.trim().ifEmpty { null })) }) {
                    Text(stringResource(R.string.m3_nudge_custom_save))
                }
                TextButton(enabled = enabled && settings.enabled,
                    onClick = { draft = ""; commit(settings.copy(customInstruction = null)) }) {
                    Text(stringResource(R.string.m3_nudge_custom_clear))
                }
            }
        }
    }
}
