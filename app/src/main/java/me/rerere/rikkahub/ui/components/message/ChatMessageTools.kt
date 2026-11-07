package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.effectiveTarget
import me.rerere.rikkahub.ui.components.message.tools.StreamingToolDraftStep
import me.rerere.rikkahub.ui.components.message.tools.ToolApprovalDisplay
import me.rerere.rikkahub.ui.components.message.tools.ToolApprovalPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolInputDraftTracker
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.components.DotLoading
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 发给模型的运行时通知，例如后台命令结束；展开后显示发给模型的原文 */
@Composable
fun ChainOfThoughtScope.ChatMessageNoticeStep(notice: UIMessagePart.Notice) {
    ChainOfThoughtStep(
        icon = {
            Icon(
                imageVector = HugeIcons.Notification01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.7f),
            )
        },
        label = {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    assistant: Assistant? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    // 长入参（写文件 / 改文件）的 JSON 要等整段生成完才闭合。在此之前 input 只是一段未闭合的
    // 增量 JSON，这里把它解成草稿直接显示：随流实时看代码，断流/取消也有草稿可看、可复制、可导出。
    // 草稿只用于展示，绝不当执行参数；JSON 闭合后这里返回的就不再是草稿，流程照旧走下面的渲染器。
    // 解析器自带增量缓存（入参是同一个实例时直接命中上一次结果），所以每帧调用不会重复扫全量。
    //
    // 参数没收完（`hasIncompleteInput`：还在流式、被截断、断流取消）时一律走草稿卡片，
    // 这样即使 agent 循环补写了"未执行"结果，用户仍然看得到已经生成的那段代码。
    val draftTracker = remember(tool.toolCallId) { ToolInputDraftTracker() }
    val streamingDraft = draftTracker.draft(tool.toolName, tool.input)
    if (streamingDraft != null &&
        !tool.isPending &&
        (streamingDraft.isDraft || tool.hasIncompleteInput)
    ) {
        StreamingToolDraftStep(tool = tool, draft = streamingDraft, loading = loading)
        return
    }

    // 网关代理调用（`tool_call`）要按被代理的真实工具名解析渲染器、用内层 arguments 渲染，
    // 否则所有外部工具都会退化成 "Tool call: tool_call" + 一层嵌套 JSON，排查时只能靠猜
    val proxyTarget = remember(tool.toolName, tool.input) { tool.effectiveTarget() }
    val effectiveName = proxyTarget?.first ?: tool.toolName
    val renderer = remember(effectiveName) { ToolUIRegistry.resolve(effectiveName) }
    val outputJson = remember(tool) {
        if (tool.isExecuted) {
            runCatching {
                JsonInstant.parseToJsonElement(
                    tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                )
            }.getOrNull()
        } else {
            null
        }
    }
    // 输出不是 JSON (例如超长被截断后只剩文本预览) 时, 定制渲染器读不到任何字段, 详情改用默认渲染展示原文
    val outputUnparsable = tool.isExecuted && outputJson == null
    val context = remember(tool, loading, assistant) {
        ToolUIContext(
            tool = tool,
            arguments = proxyTarget?.second ?: tool.inputAsJson(),
            content = if (tool.isExecuted) outputJson ?: JsonObject(emptyMap()) else null,
            loading = loading,
            assistant = assistant,
            effectiveName = effectiveName,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val duration = rememberToolDuration(tool, loading)

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    // 待审批时必须额外把真实入参算进内容：注册渲染器没有 Summary 的工具（外部 MCP、批量脚本等）
    // 否则会出现"标题栏有批准按钮、展开后却没有任何参数"的盲签。
    //
    // m3: 不再原样贴整段 JSON。改用 ToolApprovalDisplay：首行是精简工具名，
    // 后面是脱敏后的关键参数（凭据类打码、超长截断、关键项优先）。
    // 这样既看得到"批准什么"，又不会把 token/密码刷在屏幕上。
    val pendingInput = remember(tool.toolName, tool.input, proxyTarget, tool.isPending) {
        if (!tool.isPending) {
            ""
        } else {
            val args = (proxyTarget?.second ?: tool.inputAsJson()) as? JsonObject ?: JsonObject(emptyMap())
            ToolApprovalDisplay.previewText(effectiveName, args)
        }
    }
    val showsApproval = isPending && onToolApproval != null
    val hasPendingInput = showsApproval && pendingInput.isNotBlank()
    // 批准动作在标题栏上，参数必须在下方可见；待审批期间不允许折叠起来藏掉载荷
    val forceExpanded = hasPendingInput
    val hasExtraContent =
        renderer.hasSummary(context) || isDenied || images.isNotEmpty() || hasPendingInput

    ControlledChainOfThoughtStep(
        expanded = expanded || forceExpanded,
        onExpandedChange = { if (!forceExpanded) expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else if (duration != null && duration >= 100.milliseconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        } else {
            null
        },
        onClick = { showResult = true },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasPendingInput) {
                        // 与悬浮窗审批卡共用同一个组件：完整、可滚动、等宽
                        ToolApprovalPreview(text = pendingInput)
                        me.rerere.rikkahub.ui.components.message.tools.BatchMemberApprovalList(tool) { index ->
                            onToolApproval?.invoke(tool.toolCallId, true,
                                me.rerere.ai.agent.BatchApprovalSelection.encode(setOf(index)))
                        }
                    }
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                if (outputUnparsable) {
                    DefaultToolPreview(context = context)
                } else {
                    renderer.Preview(
                        context = context,
                        onDismissRequest = { showResult = false },
                    )
                }
            },
        )
    }
}

/**
 * 工具执行耗时；执行中随时间刷新，没有计时（拒绝、问答、旧消息）时为 null
 */
@Composable
private fun rememberToolDuration(tool: UIMessagePart.Tool, loading: Boolean): Duration? {
    val startedAt = tool.startedAt
    val finishedAt = tool.finishedAt
    val running = loading && startedAt != null && finishedAt == null
    val now by produceState(Clock.System.now(), running) {
        while (running) {
            value = Clock.System.now()
            delay(100)
        }
    }
    return when {
        startedAt == null -> null
        finishedAt != null -> finishedAt - startedAt
        running -> now - startedAt
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            if (q.options.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    q.options.forEach { option ->
                                        val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                        FilterChip(
                                            selected = if (q.selectionType == "multi") {
                                                option in selectedOptions
                                            } else {
                                                answers[q.id] == option
                                            },
                                            onClick = {
                                                if (q.selectionType == "multi") {
                                                    multiAnswers[q.id] = if (option in selectedOptions) {
                                                        selectedOptions - option
                                                    } else {
                                                        selectedOptions + option
                                                    }
                                                } else {
                                                    answers[q.id] = option
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = option,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(
                                                (multiAnswers[q.id].orEmpty().toList() +
                                                    listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() }))
                                                    .joinToString(", ")
                                            ))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
