package me.rerere.rikkahub.ui.components.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.knowledge.KnowledgeCapabilities
import me.rerere.rikkahub.data.knowledge.KnowledgeDocSummary
import me.rerere.rikkahub.data.knowledge.KnowledgeFormat
import me.rerere.rikkahub.data.knowledge.KnowledgeFormats
import me.rerere.rikkahub.data.knowledge.KnowledgeImportResult
import me.rerere.rikkahub.data.knowledge.KnowledgeLimits
import me.rerere.rikkahub.data.knowledge.KnowledgeReadResult
import me.rerere.rikkahub.data.knowledge.KnowledgeRejectReason
import me.rerere.rikkahub.data.knowledge.KnowledgeSearchHit
import me.rerere.rikkahub.data.knowledge.KnowledgeSearchMode
import me.rerere.rikkahub.data.knowledge.LocalKnowledgeRepository
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * 本地知识库管理面板：新增/粘贴、SAF 导入、查看、编辑、删除、检索引用。
 *
 * 由对话 runtime controls 以 bottomsheet 内容的形式打开（[onDismiss] 关闭）。
 * 所有仓库调用都在协程里，仓库自己切到 Dispatchers.IO，主线程不做解析/数据库活。
 */
@Composable
fun LocalKnowledgePanel(
    assistantId: Uuid,
    onDismiss: () -> Unit,
    repository: LocalKnowledgeRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { TIME_FORMAT }

    var docs by remember(assistantId) { mutableStateOf<List<KnowledgeDocSummary>?>(null) }
    var capabilities by remember(assistantId) { mutableStateOf<KnowledgeCapabilities?>(null) }
    var loadError by remember(assistantId) { mutableStateOf<String?>(null) }
    var reloadKey by remember(assistantId) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var query by remember(assistantId) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<KnowledgeSearchHit>>(emptyList()) }

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<KnowledgeDocSummary, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<KnowledgeDocSummary?>(null) }
    var detail by remember { mutableStateOf<KnowledgeReadResult?>(null) }

    fun refresh() {
        reloadKey++
    }

    fun handleResult(result: KnowledgeImportResult) {
        notice = when (result) {
            is KnowledgeImportResult.Imported ->
                context.getString(R.string.knowledge_import_ok, result.doc.chunkCount)

            is KnowledgeImportResult.Duplicate ->
                context.getString(R.string.knowledge_import_duplicate)

            is KnowledgeImportResult.Rejected -> when (val reason = result.reason) {
                KnowledgeRejectReason.EmptyText, KnowledgeRejectReason.EmptyDocument ->
                    context.getString(R.string.knowledge_import_empty)

                KnowledgeRejectReason.TooLarge ->
                    context.getString(R.string.knowledge_import_too_large)

                KnowledgeRejectReason.Cancelled ->
                    context.getString(R.string.knowledge_import_cancelled)

                is KnowledgeRejectReason.UnsupportedFormat ->
                    context.getString(
                        R.string.knowledge_import_unsupported,
                        reason.supportedExtensions.joinToString(", "),
                    )

                is KnowledgeRejectReason.Failed ->
                    context.getString(R.string.knowledge_import_failed, reason.message)
            }
        }
        if (result is KnowledgeImportResult.Imported) refresh()
    }

    LaunchedEffect(assistantId, reloadKey) {
        if (docs == null) loadError = null
        loadError = try {
            if (capabilities == null) capabilities = runCatching { repository.capabilities() }.getOrNull()
            docs = repository.list(assistantId)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: context.getString(R.string.knowledge_error_generic)
        }
    }

    // 检索防抖：输入变化就取消上一次，结果只属于当前助手
    LaunchedEffect(assistantId, query, reloadKey) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = emptyList()
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        try {
            hits = repository.search(assistantId, q, limit = KnowledgeLimits.SEARCH_DEFAULT_LIMIT)
            searchError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: context.getString(R.string.knowledge_error_generic)
        } finally {
            searching = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = context.getString(R.string.knowledge_indexing)
            scope.launch {
                val result = try {
                    repository.importUri(assistantId, uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    KnowledgeImportResult.Rejected(KnowledgeRejectReason.Failed(e.message ?: ""))
                }
                busy = null
                handleResult(result)
            }
        }
    }

    fun saveText(docId: String?, title: String, text: String) {
        busy = context.getString(R.string.knowledge_indexing)
        scope.launch {
            val result = try {
                if (docId == null) {
                    repository.importText(assistantId, title, text)
                } else {
                    repository.updateText(assistantId, docId, title, text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KnowledgeImportResult.Rejected(KnowledgeRejectReason.Failed(e.message ?: ""))
            }
            busy = null
            handleResult(result)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.Brain02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.knowledge_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.knowledge_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.knowledge_close),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { showAdd = true },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_paste))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(KnowledgeFormats.pickerMimeTypes) },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_import))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = docs?.isNotEmpty() == true,
                placeholder = {
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_search_hint))
                },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
            )

            busy?.let { label ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            notice?.let { text ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()

            when {
                loadError != null -> ErrorState(
                    message = loadError!!,
                    onRetry = {
                        docs = null
                        loadError = null
                        refresh()
                    },
                )

                docs == null -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.knowledge_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                query.isNotBlank() -> SearchResults(
                    modifier = Modifier.weight(1f),
                    query = query,
                    searching = searching,
                    error = searchError,
                    hits = hits,
                    timeFormat = timeFormat,
                    onOpen = { hit ->
                        busy = null
                        scope.launch {
                            detail = runCatching {
                                repository.read(assistantId, hit.docId, hit.chunkId, 1_600)
                            }.getOrNull()
                        }
                    },
                )

                docs!!.isEmpty() -> EmptyState()

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(docs!!, key = { it.id }) { doc ->
                        KnowledgeDocCard(
                            doc = doc,
                            timeFormat = timeFormat,
                            onView = {
                                scope.launch {
                                    detail = runCatching {
                                        repository.read(assistantId, doc.id, null, 4_000)
                                    }.getOrNull()
                                }
                            },
                            onEdit = {
                                busy = context.getString(R.string.knowledge_loading_doc)
                                scope.launch {
                                    val text = runCatching {
                                        repository.read(
                                            assistantId,
                                            doc.id,
                                            null,
                                            KnowledgeLimits.MAX_TEXT_CHARS,
                                        )
                                    }.getOrNull()?.text.orEmpty()
                                    busy = null
                                    editTarget = doc to text
                                }
                            },
                            onDelete = { deleteTarget = doc },
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    R.string.knowledge_count,
                                    docs!!.size,
                                    docs!!.sumOf { it.chunkCount },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            capabilities?.let { caps ->
                                Text(
                                    text = androidx.compose.ui.res.stringResource(
                                        if (caps.mode == KnowledgeSearchMode.FTS4) {
                                            R.string.knowledge_search_mode_fts4
                                        } else {
                                            R.string.knowledge_search_mode_scan
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.knowledge_privacy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        KnowledgeTextDialog(
            isEdit = false,
            initialTitle = "",
            initialText = "",
            saving = busy != null,
            onDismiss = { showAdd = false },
            onSave = { title, text ->
                showAdd = false
                saveText(null, title, text)
            },
        )
    }

    editTarget?.let { (doc, text) ->
        KnowledgeTextDialog(
            isEdit = true,
            initialTitle = doc.title,
            initialText = text,
            saving = busy != null,
            onDismiss = { editTarget = null },
            onSave = { title, body ->
                editTarget = null
                saveText(doc.id, title, body)
            },
        )
    }

    deleteTarget?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.knowledge_delete_confirm_title)) },
            text = {
                Text(androidx.compose.ui.res.stringResource(R.string.knowledge_delete_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val deleted = runCatching { repository.delete(assistantId, doc.id) }
                                .getOrDefault(false)
                            notice = context.getString(
                                if (deleted) R.string.knowledge_deleted else R.string.knowledge_delete_failed
                            )
                            refresh()
                        }
                    }
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_cancel))
                }
            },
        )
    }

    detail?.let { result ->
        KnowledgeDetailDialog(result = result, timeFormat = timeFormat, onDismiss = { detail = null })
    }
}

@Composable
private fun SearchResults(
    modifier: Modifier = Modifier,
    query: String,
    searching: Boolean,
    error: String?,
    hits: List<KnowledgeSearchHit>,
    timeFormat: DateTimeFormatter,
    onOpen: (KnowledgeSearchHit) -> Unit,
) {
    when {
        searching -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.knowledge_searching),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        error != null -> Text(
            text = androidx.compose.ui.res.stringResource(R.string.knowledge_search_failed, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        hits.isEmpty() -> Text(
            text = androidx.compose.ui.res.stringResource(R.string.knowledge_search_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hits, key = { it.chunkId }) { hit ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = hit.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${formatLabel(hit.format)} · ${hit.sourceName ?: "PASTED"} · " +
                                "${timeFormat.format(hit.updatedAt)} · " +
                                String.format(Locale.US, "%.2f", hit.score),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = hit.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onOpen(hit) }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_cite))
                        }
                    }
                }
            }
            item {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.knowledge_search_scope_note, query),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KnowledgeDocCard(
    doc: KnowledgeDocSummary,
    timeFormat: DateTimeFormatter,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = doc.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatLabel(doc.format)} · ${doc.sourceName ?: "PASTED"} · " +
                    doc.chunkCount + " " + androidx.compose.ui.res.stringResource(R.string.knowledge_unit_chunks) +
                    " · " + doc.charCount + " " +
                    androidx.compose.ui.res.stringResource(R.string.knowledge_unit_chars) +
                    " · " + timeFormat.format(doc.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (doc.truncated) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.knowledge_doc_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onView) {
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_view))
                }
                TextButton(onClick = onEdit) {
                    Text(androidx.compose.ui.res.stringResource(R.string.knowledge_action_edit))
                }
                TextButton(onClick = onDelete) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.knowledge_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            HugeIcons.File02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.knowledge_empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.knowledge_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(androidx.compose.ui.res.stringResource(R.string.knowledge_retry))
            }
        }
    }
}

@Composable
private fun KnowledgeTextDialog(
    isEdit: Boolean,
    initialTitle: String,
    initialText: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, text: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(
                    if (isEdit) R.string.knowledge_edit_title else R.string.knowledge_add_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.knowledge_field_title)) },
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.knowledge_field_text)) },
                    minLines = 6,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.knowledge_text_limit,
                        KnowledgeLimits.MAX_TEXT_CHARS,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), text) },
                enabled = !saving && text.isNotBlank(),
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.knowledge_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.knowledge_cancel))
            }
        },
    )
}

@Composable
private fun KnowledgeDetailDialog(
    result: KnowledgeReadResult,
    timeFormat: DateTimeFormatter,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.doc.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${formatLabel(result.doc.format)} · " +
                        (result.doc.sourceName ?: "PASTED") +
                        " · " + timeFormat.format(result.doc.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.knowledge_detail_ids,
                        result.doc.id,
                        result.chunkId ?: "-",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.knowledge_detail_offsets,
                        result.startOffset,
                        result.endOffset,
                        result.doc.contentHash.take(12),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = result.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (result.truncated) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            R.string.knowledge_detail_truncated,
                            result.text.length,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.knowledge_close))
            }
        },
    )
}

private fun formatLabel(format: KnowledgeFormat): String = when (format) {
    KnowledgeFormat.TEXT -> "TXT"
    KnowledgeFormat.MARKDOWN -> "MD"
    KnowledgeFormat.CSV -> "CSV"
    KnowledgeFormat.JSON -> "JSON"
    KnowledgeFormat.HTML -> "HTML"
    KnowledgeFormat.PDF -> "PDF"
    KnowledgeFormat.DOCX -> "DOCX"
    KnowledgeFormat.PPTX -> "PPTX"
    KnowledgeFormat.EPUB -> "EPUB"
}
