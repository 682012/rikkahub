package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R

/**
 * 人设编辑器（r16）。
 *
 * - 草稿是独立的 [rememberSaveable] 状态，起点是打开那一刻的生效全文；
 *   所有者 [CompanionPersonaEditorRequest.ownerCharacterKey] 也是那一刻记下的，之后切角色不会改这一份；
 * - 超 [CompanionPersonaDraft.limit] 只明确报错、按钮不可用，**不 take、不丢草稿**；
 * - 有未保存修改时关闭要确认，取消确认就回到编辑器，草稿还在；
 * - 全屏、可随键盘缩、按钮都是 48dp。
 */
@Composable
internal fun CompanionPersonaEditor(
    request: CompanionPersonaEditorRequest,
    save: suspend (draft: String) -> Unit,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    // 打开时记下 owner：换角色不会重建这份草稿，也不会写到别的格。
    var draft by rememberSaveable(request.ownerCharacterKey) { mutableStateOf(request.initialText) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val baseline = request.initialText
    val length = CompanionPersonaDraft.length(draft)
    val limit = CompanionPersonaDraft.limit
    val overBy = CompanionPersonaDraft.overBy(draft)
    val canSave = CompanionPersonaDraft.canSave(draft)

    fun requestClose() {
        if (saving) return
        if (CompanionPersonaDraft.isDirty(baseline, draft)) askDiscard = true else onClose()
    }

    fun submit() {
        if (!canSave || saving) return
        saving = true
        failure = null
        scope.launch {
            val result = runCatching { save(draft) }
            saving = false
            result.onSuccess { onSaved() }
                .onFailure { failure = it.message ?: it::class.simpleName }
        }
    }

    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val metaStyle = MaterialTheme.typography.bodySmall

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.r16_persona_editor_title, request.ownerLabel),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { requestClose() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.r16_persona_close))
                    }
                }
                Text(
                    text = stringResource(R.string.r16_persona_editor_owner, request.ownerLabel),
                    style = metaStyle,
                    color = metaColor,
                )
                Text(
                    text = stringResource(R.string.r16_persona_editor_origin),
                    style = metaStyle,
                    color = metaColor,
                )
                OutlinedTextField(
                    value = draft,
                    enabled = !saving,
                    // 不做任何 take：超限时由下面的提示和禁用的保存按钮处理。
                    onValueChange = {
                        draft = it
                        failure = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    isError = overBy > 0,
                    supportingText = {
                        Column {
                            Text(
                                text = stringResource(R.string.r16_persona_editor_counter, length, limit),
                                style = metaStyle,
                                color = if (overBy > 0) MaterialTheme.colorScheme.error else metaColor,
                            )
                            Text(
                                text = stringResource(R.string.r16_persona_editor_counter_note),
                                style = metaStyle,
                                color = metaColor,
                            )
                        }
                    },
                )
                if (overBy > 0) {
                    Text(
                        text = stringResource(R.string.r16_persona_editor_over_limit, overBy, limit),
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (draft.isBlank()) {
                    Text(
                        text = stringResource(R.string.r16_persona_editor_blank),
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (failure != null) {
                    Text(
                        text = stringResource(R.string.r16_persona_save_failed, failure.orEmpty()),
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { requestClose() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.r16_persona_editor_cancel))
                    }
                    Button(
                        onClick = { submit() },
                        enabled = canSave && !saving,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (saving) R.string.r16_persona_editor_saving else R.string.r16_persona_editor_save
                            )
                        )
                    }
                }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.r16_persona_editor_discard_title)) },
            text = { Text(stringResource(R.string.r16_persona_editor_discard_text, request.ownerLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    // 明确放弃：这次才真的把草稿丢掉。
                    askDiscard = false
                    onClose()
                }) {
                    Text(stringResource(R.string.r16_persona_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) {
                    Text(stringResource(R.string.r16_persona_editor_keep))
                }
            },
        )
    }
}
