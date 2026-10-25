package me.rerere.rikkahub.data.db.migrations

import java.security.MessageDigest

/** One `memory_file` row as read before the schema-33 path repair. */
internal data class MemoryFileRow(
    val assistantId: String,
    val path: String,
    val content: String,
    val updatedAt: Long,
)

/** A single write the repair has to perform. Pure data so the plan is unit-testable on the JVM. */
internal sealed interface MemoryPathStep {
    /** No conflict: only the key changes, content and updated_at stay exactly as they are. */
    data class PrefixPath(val assistantId: String, val from: String, val to: String) : MemoryPathStep

    /** The relative-path row is removed once its content lives at the canonical path or an archive. */
    data class DropRelative(val assistantId: String, val path: String) : MemoryPathStep

    /** The losing copy of a conflict, kept verbatim under `/_legacy/path_conflicts/...`. */
    data class KeepLegacy(
        val assistantId: String,
        val path: String,
        val content: String,
        val updatedAt: Long,
    ) : MemoryPathStep

    /** Newer timestamp for a row whose content does not change (deduplicated copy). */
    data class Touch(val assistantId: String, val path: String, val updatedAt: Long) : MemoryPathStep

    /** The canonical path takes the newer content of the relative-path row. */
    data class WriteCanonical(
        val assistantId: String,
        val path: String,
        val content: String,
        val updatedAt: Long,
    ) : MemoryPathStep
}

/**
 * Lossless plan for the schema 32 -> 33 memory path repair.
 *
 * fork.22 wrote memory paths without a leading slash while the UI only opens `/...`, so those rows
 * looked "missing". A blind `UPDATE OR REPLACE ... SET path = '/' || path` silently deletes the
 * canonical row whenever both spellings exist. Here a lone relative path is renamed in place, and a
 * real conflict keeps both texts: the newer `updated_at` wins the canonical path (ties keep the
 * already-canonical row), the other copy is preserved under [LEGACY_DIR]. Identical texts are
 * deduplicated, but the newer `updated_at` is not thrown away.
 *
 * Rows of different assistants never interact: the table key is (assistant_id, path).
 */
internal object MemoryPathRepair {
    const val LEGACY_DIR = "/_legacy/path_conflicts"

    fun plan(rows: List<MemoryFileRow>): List<MemoryPathStep> {
        val steps = mutableListOf<MemoryPathStep>()
        val byKey = linkedMapOf<Pair<String, String>, MemoryFileRow>()
        rows.forEach { byKey[it.assistantId to it.path] = it }
        val relativeRows = rows.filter { !it.path.startsWith("/") }
        // A rename target must never be reused as an archive path, or the two writes collide.
        val reserved = relativeRows.map { it.assistantId to "/" + it.path }.toSet()
        for (row in relativeRows.sortedWith(compareBy({ it.assistantId }, { it.path }))) {
            val key = row.assistantId to row.path
            val canonicalKey = row.assistantId to "/" + row.path
            val canonical = byKey[canonicalKey]
            if (canonical == null) {
                steps += MemoryPathStep.PrefixPath(row.assistantId, row.path, canonicalKey.second)
                byKey.remove(key)
                byKey[canonicalKey] = row.copy(path = canonicalKey.second)
                continue
            }
            steps += MemoryPathStep.DropRelative(row.assistantId, row.path)
            byKey.remove(key)
            if (canonical.content == row.content) {
                if (row.updatedAt > canonical.updatedAt) {
                    steps += MemoryPathStep.Touch(row.assistantId, canonical.path, row.updatedAt)
                    byKey[canonicalKey] = canonical.copy(updatedAt = row.updatedAt)
                }
                continue
            }
            val relativeWins = row.updatedAt > canonical.updatedAt
            val loser = if (relativeWins) canonical else row
            if (relativeWins) {
                steps += MemoryPathStep.WriteCanonical(row.assistantId, canonical.path, row.content, row.updatedAt)
                byKey[canonicalKey] = canonical.copy(content = row.content, updatedAt = row.updatedAt)
            }
            val (target, isNew) = archiveTarget(loser, reserved, byKey)
            val targetKey = row.assistantId to target
            val archived = byKey[targetKey]
            when {
                isNew -> {
                    steps += MemoryPathStep.KeepLegacy(row.assistantId, target, loser.content, loser.updatedAt)
                    byKey[targetKey] = MemoryFileRow(row.assistantId, target, loser.content, loser.updatedAt)
                }
                archived == null || loser.updatedAt > archived.updatedAt -> {
                    steps += MemoryPathStep.Touch(row.assistantId, target, loser.updatedAt)
                    if (archived != null) byKey[targetKey] = archived.copy(updatedAt = loser.updatedAt)
                }
            }
        }
        return steps
    }

    /**
     * Deterministic archive path for [row]: the relative path itself, then a content hash, then a
     * counter, skipping keys that are taken by a different text or reserved by a rename.
     */
    private fun archiveTarget(
        row: MemoryFileRow,
        reserved: Set<Pair<String, String>>,
        byKey: Map<Pair<String, String>, MemoryFileRow>,
    ): Pair<String, Boolean> {
        val base = archiveBase(row.path)
        val stem = base.removeSuffix(".md")
        val candidates = sequence {
            yield(base)
            yield("$stem-${shortHash(row.content)}.md")
            var index = 2
            while (true) {
                yield("$stem-${shortHash(row.content)}-$index.md")
                index++
            }
        }
        for (candidate in candidates) {
            val key = row.assistantId to candidate
            if (key in reserved) continue
            val existing = byKey[key] ?: return candidate to true
            if (existing.content == row.content) return candidate to false
        }
        error("Unreachable archive path for ${row.path}")
    }

    /** `/_legacy/path_conflicts/<relative path>.md`, without leading separators or `..` segments. */
    fun archiveBase(relative: String): String {
        val cleaned = relative.trimStart('/').replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
            .ifEmpty { "unnamed" }
        return "$LEGACY_DIR/" + if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
    }

    private fun shortHash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }
}
