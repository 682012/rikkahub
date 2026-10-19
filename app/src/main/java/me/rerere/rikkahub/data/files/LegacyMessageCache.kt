package me.rerere.rikkahub.data.files

import java.io.File

/** Compatibility reader for fork.14. Never follows arbitrary paths supplied by a tool/model. */
internal class LegacyMessageCache(private val directory: File) {
    private val marker = Regex("\\n…\\[full text: ([^\\r\\n]+)]$")
    private val cacheName = Regex("[0-9a-fA-F]{32}\\.[a-zA-Z0-9]+")

    private fun resolve(path: String): File? {
        val name = path.substringAfterLast('/')
        if (!cacheName.matches(name)) return null
        // Backups may contain another package's absolute path. Only relocate msg_cache basenames.
        if (path.substringBeforeLast('/').substringAfterLast('/') != "msg_cache") return null
        val root = directory.canonicalFile
        val file = File(root, name).canonicalFile
        return file.takeIf { it.parentFile == root && it.isFile }
    }

    fun textFile(text: String): File? {
        val match = marker.find(text) ?: return null
        val file = resolve(match.groupValues[1]) ?: return null
        if (file.extension != "txt") return null
        // A plain textual marker is not authorization to read unrelated private files.
        val prefix = text.substring(0, match.range.first)
        if (prefix.length != 2_000) return null
        val actual = file.bufferedReader().use { reader ->
            val chars = CharArray(prefix.length)
            var count = 0
            while (count < chars.size) {
                val n = reader.read(chars, count, chars.size - count)
                if (n < 0) break
                count += n
            }
            String(chars, 0, count)
        }
        return file.takeIf { actual == prefix }
    }

    fun restoreText(text: String): String? = runCatching { textFile(text)?.readText() }.getOrNull()

    fun readPreview(text: String, maxChars: Int): String? = runCatching { textFile(text)?.bufferedReader()?.use { reader ->
        val out = StringBuilder()
        val buffer = CharArray(4_096)
        while (out.length < maxChars) {
            val n = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (n < 0) break
            out.append(buffer, 0, n)
        }
        out.toString()
    } }.getOrNull()

    fun mediaFile(url: String): File? = runCatching {
        if (url.startsWith("file://")) resolve(url.removePrefix("file://")) else null
    }.getOrNull()
}
