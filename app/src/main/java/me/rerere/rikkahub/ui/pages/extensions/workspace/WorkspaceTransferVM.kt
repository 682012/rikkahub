package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceImportResult
import me.rerere.rikkahub.utils.fileSizeToString

/** A page ViewModel owns transfers, not a recycled LazyColumn item or its composition. */
class WorkspaceTransferVM(
    private val repository: WorkspaceRepository,
    context: Context,
) : ViewModel() {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(WorkspaceTransferState())
    private val openStream = AtomicReference<Closeable?>(null)
    private var job: Job? = null
    val state = mutableState.asStateFlow()
    val workspaces = repository.listFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun importZip(uri: Uri, name: String, wrapper: String?) {
        if (job?.isActive == true) return
        mutableState.value = WorkspaceTransferState(running = true, message = app.getString(R.string.workspace_transfer_preparing))
        val committed = AtomicReference<WorkspaceImportResult?>(null)
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = requireNotNull(app.contentResolver.openInputStream(uri)) {
                        app.getString(R.string.workspace_transfer_cannot_read)
                    }
                    openStream.set(input)
                    input.use {
                        currentCoroutineContext().ensureActive()
                        repository.importWorkspaceFiles(
                            name = name,
                            input = it,
                            stripWrapper = wrapper,
                            onCommitted = { committed.set(it) },
                            onProgress = { bytes, files, current ->
                                mutableState.value = WorkspaceTransferState(
                                    running = true,
                                    message = app.getString(R.string.workspace_transfer_import_progress, files, bytes.fileSizeToString()),
                                    current = current,
                                )
                            },
                        )
                    }
                }
                showImported(result)
            } catch (e: CancellationException) {
                committed.get()?.let(::showImported) ?: run {
                    mutableState.value = WorkspaceTransferState(message = app.getString(R.string.workspace_transfer_cancelled))
                }
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) {
                    committed.get()?.let(::showImported) ?: run {
                        mutableState.value = WorkspaceTransferState(message = app.getString(R.string.workspace_transfer_cancelled))
                    }
                    currentCoroutineContext().ensureActive()
                }
                mutableState.value = WorkspaceTransferState(error = true, message = app.getString(R.string.workspace_transfer_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                openStream.set(null)
            }
        }
    }

    fun exportZip(uri: Uri, workspaceId: String, skipRebuildable: Boolean = true) {
        if (job?.isActive == true) return
        mutableState.value = WorkspaceTransferState(running = true, message = app.getString(R.string.workspace_transfer_preparing))
        job = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val workspace = requireNotNull(repository.getById(workspaceId)) { app.getString(R.string.workspace_transfer_missing) }
                    require(workspace.capabilities().localFiles) { app.getString(R.string.workspace_transfer_local_only) }
                    val output = requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) {
                        app.getString(R.string.workspace_transfer_cannot_write)
                    }
                    openStream.set(output)
                    output.use {
                        currentCoroutineContext().ensureActive()
                        repository.exportWorkspaceFiles(workspaceId, it, skipRebuildable) { done, total, current ->
                            mutableState.value = WorkspaceTransferState(
                                running = true,
                                progress = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null,
                                message = "${done.fileSizeToString()} / ${total.fileSizeToString()}",
                                current = current,
                            )
                        }
                    }
                }
                mutableState.value = WorkspaceTransferState(message = buildString {
                    append(app.getString(R.string.workspace_transfer_export_stats, summary.entryCount, summary.byteCount.fileSizeToString()))
                    if (summary.prunedDirCount + summary.skippedTotal > 0) {
                        append("\n")
                        append(app.getString(R.string.workspace_transfer_export_skips, summary.prunedDirCount, summary.skippedSymlinks, summary.skippedSpecialFiles))
                        if (summary.prunedDirExamples.isNotEmpty()) append("\n" + summary.prunedDirExamples.joinToString(", "))
                    }
                })
            } catch (e: CancellationException) {
                mutableState.value = WorkspaceTransferState(message = app.getString(R.string.workspace_transfer_export_cancelled))
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) {
                    mutableState.value = WorkspaceTransferState(message = app.getString(R.string.workspace_transfer_export_cancelled))
                    currentCoroutineContext().ensureActive()
                }
                mutableState.value = WorkspaceTransferState(error = true, message = app.getString(R.string.workspace_transfer_export_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                openStream.set(null)
            }
        }
    }

    private fun showImported(result: WorkspaceImportResult) {
        val summary = result.summary
        mutableState.value = WorkspaceTransferState(
            message = app.getString(R.string.workspace_transfer_import_done, result.workspace.name) + "\n" +
                app.getString(R.string.workspace_transfer_import_stats, summary.fileCount, summary.byteCount.fileSizeToString(), summary.entriesWithoutUnixMode),
            importedId = result.workspace.id,
        )
    }

    fun cancel() {
        job?.cancel()
        // Content providers may block read/write: close the owned descriptor off the main thread.
        val stream = openStream.getAndSet(null) ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { stream.close() } }
    }

    override fun onCleared() {
        val stream = openStream.getAndSet(null)
        if (stream != null) {
            kotlin.concurrent.thread(isDaemon = true, name = "workspace-transfer-close") {
                runCatching { stream.close() }
            }
        }
        super.onCleared()
    }
}

data class WorkspaceTransferState(
    val running: Boolean = false,
    val progress: Float? = null,
    val message: String = "",
    val current: String = "",
    val error: Boolean = false,
    val importedId: String? = null,
)
