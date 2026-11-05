package me.rerere.rikkahub.data.skills

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/** Disk cache only: a cache hit still returns the full requested text to the current model context. */
internal class StampedFileCache<T>(
    private val maxEntries: Int = 128,
    private val maxWeight: Int = 8 * 1024 * 1024,
    private val weight: (T) -> Int = { 1 },
    private val revalidateMillis: Long = 30000,
    private val now: () -> Long = { System.nanoTime() / 1000000 },
) {
    private data class Stamp(val size: Long, val modified: Long, val changed: String?, val key: String?)
    private data class Entry<T>(val stamp: Stamp, val value: T, val weight: Int, val loadedAt: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private var used = 0

    private fun stamp(file: File): Stamp {
        val path = file.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(attrs.isRegularFile) { "Not a regular skill file: ${file.name}" }
        val changed = runCatching { Files.getAttribute(path, "unix:ctime") as? FileTime }.getOrNull()
        return Stamp(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS), changed?.toString(), attrs.fileKey()?.toString())
    }

    fun get(file: File, load: (File) -> T): T {
        val target = file.canonicalFile
        val key = target.path
        val before = stamp(target) // Always stat: deletion, replacement and external workspace edits are visible.
        synchronized(entries) { entries[key]?.takeIf { it.stamp == before && now() - it.loadedAt < revalidateMillis }?.let { return it.value } }
        val value = load(target)
        val after = stamp(target)
        synchronized(entries) {
            entries.remove(key)?.let { used -= it.weight }
            val size = weight(value).coerceAtLeast(1)
            if (before == after && size <= maxWeight) {
                entries[key] = Entry(after, value, size, now())
                used += size
                while (entries.size > maxEntries || used > maxWeight) {
                    val iterator = entries.entries.iterator()
                    used -= iterator.next().value.weight
                    iterator.remove()
                }
            }
        }
        return value
    }

    fun invalidate(file: File) = synchronized(entries) {
        val path = file.canonicalPath
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == path || entry.key.startsWith(path + File.separator)) {
                used -= entry.value.weight
                iterator.remove()
            }
        }
    }
}

internal object SkillFileCache {
    private const val MAX_HEADER_CHARS = 64 * 1024
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private val headers = StampedFileCache<SkillFrontmatter>()
    private val bodies = StampedFileCache<String>(maxEntries = 32, weight = { it.length * 2 })

    fun metadata(file: File): SkillFrontmatter = headers.get(file) { target ->
        target.bufferedReader().use { reader ->
            val header = StringBuilder()
            val line = StringBuilder()
            var first = true
            while (true) {
                val c = reader.read()
                if (c < 0) {
                    if (line.toString().trimEnd('\r') == "---" && !first) header.append(line)
                    break
                }
                require(header.length + line.length < MAX_HEADER_CHARS) { "Skill metadata exceeds 64 KiB" }
                if (c.toChar() == '\n') {
                    val value = line.toString().trimEnd('\r')
                    if (first && value != "---") return@use SkillFrontmatter.Empty
                    header.append(line).append('\n')
                    line.setLength(0)
                    if (!first && value == "---") break
                    first = false
                } else line.append(c.toChar())
            }
            SkillFrontmatterParser.parse(header.toString())
        }
    }

    fun text(file: File): String = bodies.get(file) { target ->
        require(target.length() <= MAX_BODY_BYTES) { "Skill file exceeds 4 MiB; split it into linked references" }
        target.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BODY_BYTES) { "Skill file exceeds 4 MiB" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    fun invalidate(file: File) { headers.invalidate(file); bodies.invalidate(file) }
}
