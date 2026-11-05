package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ToolInputState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.CodeHighlightText
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.ui.components.DotLoading

/** 内联摘要只留最近几行 */
private const val SUMMARY_LINES = 6
private const val SUMMARY_MAX_CHARS = 600

/** 只有很短的内容才走语法高亮：长流每帧重新高亮整段代码会卡住滚动 */
private const val HIGHLIGHT_MAX_CHARS = 900

/** 详情页一次最多渲染这么多字符，避免几十万字符的 Text 布局卡住主线程；复制/导出不受影响 */
private const val SHEET_MAX_CHARS = 40_000

/**
 * 流式工具入参草稿：模型还在生成 `workspace_write_file` / `workspace_edit_file` 的入参时，
 * 就在思考链里把已经生成的代码显示出来，断流/取消也能接着看、复制、导出。
 *
 * 这里展示的一切都只是**草稿**：没有写工作区、也没有执行任何工具；
 * 完整 JSON 到达后这个分支会自动消失，交回原来的文件操作卡片与审批流程。
 */
@Composable
fun ChainOfThoughtScope.StreamingToolDraftStep(
    tool: UIMessagePart.Tool,
    draft: ToolInputDraft,
    loading: Boolean,
) {
    var expanded by remember(tool.toolCallId) { mutableStateOf(true) }
    var showSheet by remember(tool.toolCallId) { mutableStateOf(false) }
    val receiving = loading && tool.inputState == ToolInputState.STREAMING

    val path = draft.path
    val title = when {
        draft.kind == ToolInputDraftKind.WRITE_FILE && path != null ->
            stringResource(R.string.r21_stream_draft_title_write, path)

        draft.kind == ToolInputDraftKind.EDIT_FILE && path != null ->
            stringResource(R.string.r21_stream_draft_title_edit, path)

        else -> stringResource(R.string.r21_stream_draft_title_unknown)
    }
    val stepIcon: ImageVector = when (draft.kind) {
        ToolInputDraftKind.WRITE_FILE -> HugeIcons.FileAdd
        ToolInputDraftKind.EDIT_FILE -> HugeIcons.FileEdit
        ToolInputDraftKind.OTHER -> HugeIcons.Tools
    }
    val status = stringResource(
        when {
            // ai 模块已经判定"参数被截断/断流"：不再假装还在生成
            tool.inputState == ToolInputState.INTERRUPTED -> R.string.r21_stream_draft_stopped
            loading -> R.string.r21_stream_draft_streaming
            else -> R.string.r21_stream_draft_stopped
        }
    )
    // agent 循环可能已经给这条半截调用补写过"未执行"结果，别把它丢掉
    val outputText = remember(tool.output) {
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (receiving) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = stepIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = receiving),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = {
            Text(
                text = stringResource(R.string.r21_stream_draft_chars, draft.receivedChars),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        onClick = { showSheet = true },
        content = {
            StreamingDraftSummary(
                draft = draft,
                raw = tool.input,
                status = status,
                outputText = outputText,
            )
        },
    )

    if (showSheet) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            onDismissRequest = { showSheet = false },
            content = {
                StreamingDraftSheet(
                    draft = draft,
                    raw = tool.input,
                    status = status,
                    outputText = outputText,
                )
            },
        )
    }
}

/** 展开后的内联摘要：草稿状态 + 尾部几行（只读预览，点标题看全部） */
@Composable
private fun StreamingDraftSummary(
    draft: ToolInputDraft,
    raw: String,
    status: String,
    outputText: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DraftStatusRow(draft = draft, status = status)

        val previous = draft.previousBody
        if (!previous.isNullOrEmpty()) {
            DraftTailBox(
                text = previous,
                path = draft.path,
                label = stringResource(R.string.r21_stream_draft_previous),
            )
        }

        val body = draft.body
        if (!body.isNullOrEmpty()) {
            DraftTailBox(
                text = body,
                path = draft.path,
                label = stringResource(R.string.r21_stream_draft_body),
            )
        } else {
            // 正文还没开始（或工具名没流全）：给原始入参的尾部，至少能看到 JSON 在长
            DraftTailBox(
                text = raw,
                path = null,
                label = stringResource(R.string.r21_stream_draft_raw),
                language = "json",
            )
        }

        if (outputText.isNotBlank()) {
            DraftTailBox(
                text = outputText,
                path = null,
                label = stringResource(R.string.r21_stream_draft_result),
                language = "plaintext",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.r21_stream_draft_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.r21_stream_draft_expand),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 状态行：未提交草稿 + 正在生成/已中断 + 已解出多少正文 */
@Composable
private fun DraftStatusRow(draft: ToolInputDraft, status: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DraftChip(text = stringResource(R.string.r21_stream_draft_badge), emphasize = true)
        DraftChip(text = status, emphasize = false)
        Text(
            text = stringResource(R.string.r21_stream_draft_body_chars, draft.bodyChars),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DraftChip(text: String, emphasize: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasize) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(
                if (emphasize) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 尾部若干行的小窗。
 *
 * 高亮只对很短的内容做（[HIGHLIGHT_MAX_CHARS]）；`remember` 的 key 用长度而不是整段字符串，
 * 避免每帧做一次 O(n) 的字符串比较。
 */
@Composable
private fun DraftTailBox(
    text: String,
    path: String?,
    label: String,
    language: String? = null,
) {
    val tail = remember(text) { text.draftTailLines(SUMMARY_LINES, SUMMARY_MAX_CHARS) }
    val resolvedLanguage = language ?: languageOf(path)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (tail.length <= HIGHLIGHT_MAX_CHARS) {
                CodeHighlightText(
                    code = tail,
                    language = resolvedLanguage,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = tail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 详情：可滚动的完整草稿 + 复制 + 导出（导出的是草稿内容，不是写入工作区） */
@Composable
private fun StreamingDraftSheet(
    draft: ToolInputDraft,
    raw: String,
    status: String,
    outputText: String,
) {
    val text = draftCopyOrExportText(draft, raw)
    val fileName = remember(draft.path, draft.kind, draft.receivedChars, draft.bodyTruncated) { draftExportFileName(draft, raw) }
    val shown = remember(text) {
        if (text.length > SHEET_MAX_CHARS) text.substring(0, SHEET_MAX_CHARS) else text
    }

    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = draft.path ?: stringResource(R.string.r21_stream_draft_title_unknown),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        DraftStatusRow(draft = draft, status = status)
        if (draft.bodyTruncated) Text(
            stringResource(R.string.r21_stream_draft_raw_fallback),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        DraftActions(text = text, fileName = fileName)
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        if (text.length > SHEET_MAX_CHARS) {
            Text(
                text = stringResource(R.string.r21_stream_draft_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (outputText.isNotBlank()) {
            // 半截调用可能已经被补写过"未执行"结果，放在草稿下面一起看
            HorizontalDivider()
            Text(
                text = stringResource(R.string.r21_stream_draft_result),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = outputText.take(SHEET_MAX_CHARS),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.r21_stream_draft_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 复制 / 导出当前草稿内容 */
@Composable
private fun DraftActions(text: String, fileName: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val destination = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("No writable document stream")
                    destination.use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.widget.Toast.makeText(context,
                    context.getString(R.string.r21_stream_draft_export_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("tool-input-draft", text)))
                }
            },
        ) {
            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.r21_stream_draft_copy),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        FilledTonalButton(onClick = { exportLauncher.launch(fileName) }) {
            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.r21_stream_draft_export),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 导出文件名：优先跟正在生成的 path 对齐，让草稿一眼能和目标文件对上 */
private fun draftExportFileName(draft: ToolInputDraft, raw: String): String {
    val base = draft.path
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    if (draft.bodyTruncated) return "${base ?: "tool"}.draft-arguments.json"
    if (base != null) return base
    val suffix = when (draft.kind) {
        ToolInputDraftKind.WRITE_FILE -> "write"
        ToolInputDraftKind.EDIT_FILE -> "edit"
        ToolInputDraftKind.OTHER -> "tool"
    }
    return "draft-$suffix-${raw.length}.txt"
}
