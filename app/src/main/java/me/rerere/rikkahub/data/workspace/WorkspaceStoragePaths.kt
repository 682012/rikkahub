package me.rerere.rikkahub.data.workspace

import java.io.File

/** SAF document IDs are not filesystem paths. Only external-storage trees are resolvable. */
object WorkspaceStoragePaths {
    private val reserved = listOf(
        "/workspace", "/skills", "/builtin_skills", "/upload", "/tool_outputs", "/tmp", "/proc", "/sys", "/dev",
    )

    fun target(path: String): String {
        require(path.startsWith('/')) { "Mount target must be an absolute Linux path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' || it == ':' }) {
            "Mount target contains an unsupported character"
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "Mount target cannot contain . or .." }
        val normalized = "/" + parts.joinToString("/")
        require(normalized != "/") { "Cannot replace the Rootfs root" }
        require(reserved.none { overlaps(normalized, it) }) { "Mount target overlaps a reserved workspace path" }
        return normalized
    }

    fun source(path: String): File {
        require(File(path).isAbsolute && path.isNotBlank()) { "Source must be an absolute Android path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' || it == ':' }) {
            "Source contains an unsupported character"
        }
        return File(path).canonicalFile
    }

    fun fromDocumentId(documentId: String, volumeRoots: Map<String, String>): File {
        val separator = documentId.indexOf(':')
        require(separator > 0) { "Unsupported storage tree" }
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        require(!relative.startsWith('/') && relative.split('/').none { it == "." || it == ".." }) {
            "Storage tree escapes its volume"
        }
        val rootPath = volumeRoots.entries.firstOrNull { it.key.equals(volume, ignoreCase = true) }?.value
            ?: error("Storage volume is unavailable; cloud and virtual folders cannot be bind-mounted")
        val root = source(rootPath)
        val child = File(root, relative).canonicalFile
        require(child.toPath().startsWith(root.toPath())) { "Storage tree escapes its volume" }
        return child
    }

    fun validate(settings: WorkspaceAccessSettings) {
        require(settings.mounts.size <= 128) { "Too many mount entries" }
        val targets = settings.mounts.map { target(it.targetPath) }
        require(targets.distinct().size == targets.size) { "Mount targets must be unique" }
        settings.mounts.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid mount identifier" }
            source(it.sourcePath)
        }
    }

    private fun overlaps(a: String, b: String): Boolean =
        a == b || a.startsWith("$b/") || b.startsWith("$a/")
}
