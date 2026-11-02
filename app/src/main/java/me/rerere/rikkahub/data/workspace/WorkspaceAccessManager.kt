package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceLocalAccess
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class WorkspaceAccessManager(context: Context, private val privileges: WorkspacePrivilegeManager) {
    private val context = context.applicationContext
    private val store = WorkspaceAccessStore(File(this.context.noBackupFilesDir, "workspace-access"))
    private val states = ConcurrentHashMap<String, MutableStateFlow<WorkspaceAccessSettings>>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val epochs = ConcurrentHashMap<String, AtomicLong>()
    private val identities = ConcurrentHashMap<String, WorkspaceIdentity>()

    fun settings(root: String): StateFlow<WorkspaceAccessSettings> =
        states.getOrPut(root) { MutableStateFlow(store.read(root)) }

    fun get(root: String): WorkspaceAccessSettings = settings(root).value
    fun lastIdentity(root: String): WorkspaceIdentity? = identities[root]
    fun recordError(root: String): String? = store.error(root)
    fun shizukuStatus(): String = privileges.shizukuStatus()

    fun storageAccessGranted(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun localAccess(root: String): WorkspaceLocalAccess {
        val config = get(root)
        val mounts = config.mounts.map {
            val source = WorkspaceStoragePaths.source(it.sourcePath)
            check(source.isDirectory && source.canRead() && source.canWrite()) {
                "Mount ${it.targetPath} is unavailable; restore storage authorization or remove this mount"
            }
            WorkspaceBindMount(source, WorkspaceStoragePaths.target(it.targetPath), writable = true)
        }
        return WorkspaceLocalAccess(mounts, config.trustedLinux)
    }

    suspend fun setTrusted(root: String, trusted: Boolean) = update(root) { it.copy(trustedLinux = trusted) }

    suspend fun authorize(root: String, mode: WorkspacePrivilegeMode): WorkspaceIdentity {
        val identity = privileges.requestAuthorization(mode)
        revoke(root)
        update(root) { it.copy(androidShellEnabled = true, androidPrivilege = mode) }
        identities[root] = identity
        return identity
    }

    suspend fun revoke(root: String) {
        val oldEpoch = withContext(Dispatchers.IO) {
            synchronized(locks.getOrPut(root) { Any() }) {
                val next = get(root).copy(androidShellEnabled = false)
                store.write(root, next)
                states.getValue(root).value = next
                epochs.getOrPut(root) { AtomicLong() }.getAndIncrement()
            }
        }
        identities.remove(root)
        privileges.cancelOwner("$root:$oldEpoch")
    }

    suspend fun executeAndroid(root: String, command: String, cwd: String, timeoutMillis: Long): WorkspaceAndroidCommandResult {
        val (config, epoch) = synchronized(locks.getOrPut(root) { Any() }) {
            get(root) to epochs.getOrPut(root) { AtomicLong() }.get()
        }
        check(config.androidShellEnabled) { "Android execution is not authorized for this workspace" }
        require(File(cwd).isAbsolute && cwd != "/workspace" && !cwd.startsWith("/workspace/")) {
            "Android shell requires a host path, not the Linux /workspace path"
        }
        val result = privileges.execute(config.androidPrivilege, command, cwd, timeoutMillis, ownerKey = "$root:$epoch")
        check(get(root).androidShellEnabled && epochs.getValue(root).get() == epoch) {
            "Workspace authorization was revoked while this command was running"
        }
        identities[root] = result.identity
        return result
    }

    suspend fun addMount(root: String, sourcePath: String, targetPath: String, treeUri: String? = null) {
        val source = WorkspaceStoragePaths.source(sourcePath)
        check(source.isDirectory && source.canRead() && source.canWrite()) {
            "This folder is not directly accessible. Grant shared-storage access first; SAF alone is not a filesystem mount."
        }
        val target = WorkspaceStoragePaths.target(targetPath)
        val mount = WorkspaceMountSpec(UUID.randomUUID().toString(), source.path, target, treeUri)
        update(root) { it.copy(mounts = it.mounts + mount) }
    }

    suspend fun addMountFromTree(root: String, uri: Uri) {
        require(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
            "Only a local phone-storage folder can be bind-mounted; use import for virtual or cloud folders"
        }
        val roots = mutableMapOf("primary" to Environment.getExternalStorageDirectory().absolutePath)
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { volume ->
                val path = volume.directory?.absolutePath ?: return@forEach
                roots[if (volume.isPrimary) "primary" else volume.uuid ?: return@forEach] = path
            }
        }
        val source = WorkspaceStoragePaths.fromDocumentId(DocumentsContract.getTreeDocumentId(uri), roots)
        check(source.isDirectory && source.canRead() && source.canWrite()) {
            "Folder selected, but Linux cannot access it yet. Enable shared-storage access, then select it again."
        }
        val resolver = context.contentResolver
        val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var acquired = false
        try {
            resolver.takePersistableUriPermission(uri, flags)
            acquired = true
            val used = get(root).mounts.map { it.targetPath }.toSet()
            val base = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "storage" }
            var target = "/mnt/$base"
            var suffix = 2
            while (target in used) target = "/mnt/$base-${suffix++}"
            addMount(root, source.path, target, uri.toString())
        } catch (error: Exception) {
            if (acquired && !alreadyGranted) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
            throw error
        }
    }

    suspend fun removeMount(root: String, mountId: String) =
        update(root) { it.copy(mounts = it.mounts.filterNot { mount -> mount.id == mountId }) }

    suspend fun clear(root: String) {
        revoke(root)
        update(root) { WorkspaceAccessSettings() }
    }

    private suspend fun update(root: String, change: (WorkspaceAccessSettings) -> WorkspaceAccessSettings) {
        withContext(Dispatchers.IO) {
            synchronized(locks.getOrPut(root) { Any() }) {
                val next = change(get(root))
                store.write(root, next)
                states.getValue(root).value = next
            }
        }
    }
}
