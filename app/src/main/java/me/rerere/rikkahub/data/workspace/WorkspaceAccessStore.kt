package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Small device-local consent records; portable imports cannot enable privileged execution. */
class WorkspaceAccessStore(private val directory: File) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val errors = ConcurrentHashMap<String, String>()

    fun read(root: String): WorkspaceAccessSettings = synchronized(lock(root)) {
        val file = file(root)
        if (!file.exists()) return@synchronized WorkspaceAccessSettings()
        try {
            require(file.length() <= 256 * 1024) { "Access record is too large" }
            json.decodeFromString<WorkspaceAccessSettings>(file.readText()).also {
                WorkspaceStoragePaths.validate(it)
                errors.remove(root)
            }
        } catch (_: Exception) {
            errors[root] = "Local authorization record is damaged; permissions remain disabled. Reauthorize this workspace."
            WorkspaceAccessSettings()
        }
    }

    fun write(root: String, settings: WorkspaceAccessSettings) = synchronized(lock(root)) {
        WorkspaceStoragePaths.validate(settings)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create authorization directory" }
        val destination = file(root)
        val staging = File(directory, ".$root.pending")
        val bytes = json.encodeToString(settings).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "Access record is too large" }
        try {
            FileOutputStream(staging).use { it.write(bytes); it.fd.sync() }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            errors.remove(root)
        } finally {
            staging.delete()
        }
    }

    fun error(root: String): String? = errors[root]

    private fun lock(root: String): Any {
        require(root.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid workspace root" }
        return locks.getOrPut(root) { Any() }
    }

    private fun file(root: String): File = File(directory, "$root.json")
}
