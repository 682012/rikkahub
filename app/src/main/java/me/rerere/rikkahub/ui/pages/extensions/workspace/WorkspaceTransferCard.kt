package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
fun WorkspaceTransferCard(vm: WorkspaceTransferVM, onOpenWorkspace: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val local = workspaces.filter { it.capabilities().localFiles }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var skipRebuildable by rememberSaveable { mutableStateOf(true) }
    var pendingSkip by rememberSaveable { mutableStateOf(true) }
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingWrapper by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = pendingName
        val wrapper = pendingWrapper
        pendingName = null
        pendingWrapper = null
        if (uri != null && name != null) vm.importZip(uri, name, wrapper)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExport
        pendingExport = null
        if (uri != null && id != null) vm.exportZip(uri, id, pendingSkip)
    }
    val enabled = !state.running && pendingName == null && pendingExport == null
    CardGroup(title = { Text(stringResource(R.string.workspace_transfer_title)) }) {
        item(
            onClick = if (enabled) ({ showImport = true }) else null,
            headlineContent = { Text(stringResource(R.string.workspace_transfer_import)) },
            supportingContent = { Text(stringResource(R.string.workspace_transfer_import_desc)) },
        )
        item(
            onClick = if (enabled && local.isNotEmpty()) ({ showExport = true }) else null,
            headlineContent = { Text(stringResource(R.string.workspace_transfer_export)) },
            supportingContent = {
                Text(stringResource(if (local.isEmpty()) R.string.workspace_transfer_no_local else R.string.workspace_transfer_export_desc))
            },
        )
        if (state.message.isNotEmpty()) {
            formItem {
                if (state.running) {
                    val progress = state.progress
                    if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Text(state.message, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.current.isNotEmpty()) {
                    Text(state.current, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.running) TextButton(onClick = vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                state.importedId?.let { id ->
                    TextButton(onClick = { onOpenWorkspace(id) }) { Text(stringResource(R.string.workspace_transfer_open)) }
                }
            }
        }
    }
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.workspace_transfer_pick_export)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.workspace_transfer_export_desc), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().clickable { skipRebuildable = !skipRebuildable }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = skipRebuildable, onCheckedChange = null)
                        Text(stringResource(R.string.workspace_transfer_skip_rebuildable), modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.workspace_transfer_full_desc), style = MaterialTheme.typography.bodySmall)
                    local.forEach { workspace ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showExport = false
                                pendingExport = workspace.id
                                pendingSkip = skipRebuildable
                                exportLauncher.launch("rikkahub-workspace-${System.currentTimeMillis()}.zip")
                            },
                        ) { Text(workspace.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (showImport) {
        ImportWorkspaceDialog(
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showImport = false },
            onConfirm = { name, wrapper ->
                showImport = false
                pendingName = name
                pendingWrapper = wrapper
                // Some document providers identify ZIP files as application/octet-stream.
                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
        )
    }
}

@Composable
private fun ImportWorkspaceDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    val defaultName = stringResource(R.string.workspace_transfer_name_default)
    var name by rememberSaveable {
        mutableStateOf(generateSequence(defaultName) { previous ->
            val suffix = previous.removePrefix(defaultName).trim().toIntOrNull() ?: 1
            "$defaultName ${suffix + 1}"
        }.first { it !in existingNames })
    }
    var wrapper by rememberSaveable { mutableStateOf<String?>(null) }
    val duplicate = name.trim() in existingNames
    val valid = name.trim().isNotEmpty() && name.length <= 120 && !duplicate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_transfer_import)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.workspace_transfer_no_overwrite), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 120) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_page_name)) },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) ({ Text(stringResource(R.string.workspace_page_name_duplicate)) }) else null,
                )
                Text(stringResource(R.string.workspace_transfer_structure), style = MaterialTheme.typography.labelLarge)
                listOf(
                    null to R.string.workspace_transfer_bare,
                    "workspace" to R.string.workspace_transfer_wrapper_workspace,
                    "files" to R.string.workspace_transfer_wrapper_files,
                ).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { wrapper = value }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = wrapper == value, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.workspace_transfer_boundary), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name.trim(), wrapper) }) {
                Text(stringResource(R.string.workspace_transfer_pick_zip))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
