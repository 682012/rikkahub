package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionPromptBuilder
import me.rerere.rikkahub.data.companion.PersonaSource
import me.rerere.rikkahub.data.companion.ResolvedCompanionPersona

/**
 * 一次人设解析结果的只读查看器（r16）。
 *
 * - 展示的是 [resolved] 里的完整原文，不做任何 `take`、不复用列表里的省略字符串；
 * - 正文可选中、可滚动；"复制全文"只复制角色正文，另可复制拼好固定边界的完整 system；
 * - 固定边界由 [CompanionPromptBuilder.buildSystemPrompt] 现场生成——和真正发请求用的是同一个函数；
 * - 全程不联网、不调模型，AI 回应关着也能打开。
 */
@Composable
fun CompanionPersonaViewer(
    resolved: ResolvedCompanionPersona,
    title: String,
    languageTag: String,
    useBuiltInStyle: Boolean,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showSystem by remember { mutableStateOf(false) }
    // 只在点的那一下写剪贴板；写完给个"已复制"就够，不弹系统提示。
    var copied by remember { mutableStateOf<String?>(null) }
    var copyFailed by remember { mutableStateOf(false) }

    fun copy(text: String, label: String) {
        scope.launch {
            val result = runCatching {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
            }
            copied = if (result.isSuccess) label else null
            copyFailed = result.isFailure
        }
    }

    fun fullSystemPrompt(): String = CompanionPromptBuilder.buildSystemPrompt(
        persona = resolved.prompt,
        name = resolved.name,
        languageTag = languageTag,
        useBuiltInStyle = useBuiltInStyle,
    )

    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2_000)
            copied = null
        }
    }

    val bodyStyle = MaterialTheme.typography.bodyMedium
    val metaStyle = MaterialTheme.typography.bodySmall
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            // 竖屏够看、窄屏也收得住；内容高就滚，按钮始终在下面够得着。
            val maxContentHeight = (maxHeight * 0.9f).coerceAtMost(640.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.r16_persona_local_only),
                        style = metaStyle,
                        color = metaColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 整块正文都能长按选中，滚动不裁内容。
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.r16_persona_label_source,
                                        personaSourceLabel(resolved.source),
                                    ),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.r16_persona_label_card,
                                        resolved.id ?: "—",
                                        if (resolved.version.isBlank()) {
                                            stringResource(R.string.r16_persona_version_unknown)
                                        } else {
                                            resolved.version
                                        },
                                    ),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                                Text(
                                    text = stringResource(R.string.r16_persona_label_name, resolved.name),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                                Text(
                                    text = stringResource(R.string.r16_persona_label_length, resolved.prompt.length),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                                // 完整 hash 不省略：它就是"这一份有没有被改过"的依据。
                                Text(
                                    text = stringResource(R.string.r16_persona_label_hash, resolved.hash),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                                Text(
                                    text = resolved.prompt.ifEmpty {
                                        stringResource(R.string.r16_persona_empty)
                                    },
                                    style = bodyStyle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                                if (showSystem) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        text = stringResource(R.string.r16_persona_system_toggle),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.r16_persona_system_desc, languageTag),
                                        style = metaStyle,
                                        color = metaColor,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                    Text(
                                        text = fullSystemPrompt(),
                                        style = metaStyle,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (copyFailed) {
                        Text(
                            text = stringResource(R.string.r16_persona_copy_failed),
                            style = metaStyle,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { copy(resolved.prompt, "persona") },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (copied == "persona") {
                                    stringResource(R.string.r16_persona_copied)
                                } else {
                                    stringResource(R.string.r16_persona_copy)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                // 展开的同时复制一次，用户点的是"复制完整 system"，不是"展开"。
                                showSystem = true
                                copy(fullSystemPrompt(), "system")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (copied == "system") {
                                    stringResource(R.string.r16_persona_copied)
                                } else {
                                    stringResource(R.string.r16_persona_copy_system)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { showSystem = !showSystem },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = (if (showSystem) "▾ " else "▸ ") +
                                    stringResource(R.string.r16_persona_system_toggle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                            )
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.r16_persona_close))
                        }
                    }
                    if (onEdit != null) {
                        FilledTonalButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.r16_persona_edit), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 来源只做说明，不参与判断"用哪份"——那由 resolver 决定。 */
@Composable
private fun personaSourceLabel(source: PersonaSource): String = stringResource(
    when (source) {
        PersonaSource.BUILTIN -> R.string.r16_persona_source_builtin
        PersonaSource.USER_OVERRIDE -> R.string.r16_persona_source_override
        PersonaSource.FALLBACK -> R.string.r16_persona_source_fallback
    }
)
