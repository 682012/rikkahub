package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/** This is deliberately distinct from Linux workspace_shell: paths and identity are Android-host native. */
fun createWorkspaceAndroidShellTool(
    workspace: WorkspaceEntity,
    repository: WorkspaceRepository,
    runtime: WorkspaceRuntimeSettings,
): Tool = Tool(
    name = "workspace_android_shell",
    description = "Run a command in the PHONE'S native Android shell using only the privilege explicitly authorized by the user for this workspace. " +
        "This is not Ubuntu/PRoot, not SSH and not the remote HTTP host. cwd is an absolute Android host path, default /. " +
        "Linux /workspace and its bind mounts do not exist here. The result reports the actual execution UID. " +
        "Cannot request permissions, choose a higher privilege, or fall back to another identity. " +
        "A missing authorization, disconnected Shizuku or denied su is an error. Non-interactive; no persistent host background-job support.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Android shell command") })
                put("cwd", buildJsonObject { put("type", "string"); put("description", "Absolute host directory, default /; never a Linux /workspace path") })
                put("timeout", buildJsonObject { put("type", "integer"); put("description", "Timeout seconds; 0 means no limit unless a user maximum is configured") })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { resolveWorkspaceToolApproval("workspace_android_shell", workspace.toolApprovalOverrides()) },
    execute = { input ->
        val args = input.jsonObject
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull ?: "/"
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: runtime.commandTimeoutSec.toLong()
        require(seconds >= 0) { "Timeout cannot be negative" }
        val maximum = runtime.maxTimeoutSec.toLong()
        val bounded = if (maximum > 0) { if (seconds == 0L) maximum else minOf(seconds, maximum) } else seconds
        val execution = repository.executeAndroidCommand(workspace.id, command, cwd, bounded.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
        val result = execution.result
        listOf(UIMessagePart.Text(buildJsonObject {
            put("environment", "android-host")
            put("privilege", execution.identity.mode.name)
            put("uid", execution.identity.uid)
            put("selinuxContext", execution.identity.selinuxContext)
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timedOut", result.timedOut)
            put("truncated", result.truncated)
        }.toString()))
    },
)
