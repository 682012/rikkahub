package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.Serializable
import me.rerere.workspace.WorkspaceCommandResult

/** Device-local consent; never included in portable workspace exports. */
@Serializable
data class WorkspaceAccessSettings(
    val trustedLinux: Boolean = false,
    val androidShellEnabled: Boolean = false,
    val androidPrivilege: WorkspacePrivilegeMode = WorkspacePrivilegeMode.APP,
    val mounts: List<WorkspaceMountSpec> = emptyList(),
)

@Serializable
enum class WorkspacePrivilegeMode { APP, SHIZUKU, ROOT }

@Serializable
data class WorkspaceMountSpec(
    val id: String,
    val sourcePath: String,
    val targetPath: String,
    val treeUri: String? = null,
)

@Serializable
data class WorkspaceIdentity(
    val mode: WorkspacePrivilegeMode,
    val uid: Int,
    val selinuxContext: String = "",
)

data class WorkspaceAndroidCommandResult(
    val identity: WorkspaceIdentity,
    val result: WorkspaceCommandResult,
)
