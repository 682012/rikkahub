package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.WorkspaceAccessManager
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** A small pipe-based command console, not a second bundled terminal runtime or a PTY claim. */
@Composable
internal fun WorkspaceAndroidConsole(root: String, access: WorkspaceAccessManager, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var command by remember(root) { mutableStateOf("id") }
    var cwd by remember(root) { mutableStateOf("/") }
    var output by remember(root) { mutableStateOf("") }
    var job by remember(root) { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true
    CardGroup(title = { Text(stringResource(R.string.workspace_access_native_console)) }) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = cwd, onValueChange = { cwd = it }, enabled = enabled && !running,
                    label = { Text(stringResource(R.string.workspace_access_native_cwd)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-android-cwd"))
                OutlinedTextField(value = command, onValueChange = { command = it }, enabled = enabled && !running,
                    label = { Text(stringResource(R.string.workspace_access_native_command)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-android-command"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled && !running && command.isNotBlank(), onClick = {
                        job = scope.launch {
                            output = ""
                            try {
                                val execution = access.executeAndroid(root, command, cwd, 30_000)
                                val result = execution.result
                                output = "${execution.identity.mode.name} · UID ${execution.identity.uid} · exit ${result.exitCode}\n" +
                                    result.stdout + result.stderr +
                                    (if (result.timedOut) "\n[timeout]" else "") +
                                    (if (result.truncated) "\n[output truncated]" else "")
                            } catch (error: CancellationException) { output = "[cancelled]"; throw error }
                            catch (error: Exception) { output = error.message.orEmpty() }
                            finally { job = null }
                        }
                    }, modifier = Modifier.testTag("workspace-android-run")) { Text(stringResource(R.string.workspace_access_native_run)) }
                    if (running) TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.workspace_access_native_cancel)) }
                }
                if (output.isNotEmpty()) SelectionContainer {
                    Text(output, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("workspace-android-output"))
                }
            }
        }
    }
}
