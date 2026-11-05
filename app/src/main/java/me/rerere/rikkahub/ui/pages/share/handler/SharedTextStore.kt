package me.rerere.rikkahub.ui.pages.share.handler

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Navigation carries an opaque ID, never the full shared text or a caller-provided file path. */
class SharedTextStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    fun write(text: String): String = synchronized(lock) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "分享文字超过4 MiB，请作为文本文件分享" }
        check(directory.isDirectory || directory.mkdirs()) { "无法保存分享文字" }
        val cutoff = now() - MAX_AGE_MS
        directory.listFiles().orEmpty().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
        val existing = directory.listFiles().orEmpty().filter { it.isFile }
        require(existing.size < MAX_FILES && existing.sumOf { it.length() } + bytes.size <= MAX_TOTAL_BYTES) {
            "待导入文字过多，请先处理已有分享，或作为文件导入"
        }
        val id = UUID.randomUUID().toString()
        val file = target(id)
        val temp = File(directory, "$id.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.flush(); it.fd.sync() }
            check(temp.renameTo(file)) { "分享文字保存失败" }
            file.setLastModified(now())
        } finally { temp.delete() }
        id
    }

    fun read(id: String): String = synchronized(lock) {
        val file = target(id)
        check(file.isFile) { "分享文字已过期或被系统清理，请重新分享" }
        require(file.length() <= MAX_TEXT_BYTES) { "分享文字文件过大" }
        file.setLastModified(now())
        file.readText(Charsets.UTF_8)
    }

    private fun target(id: String): File {
        require(ID.matches(id)) { "无效的分享文字标识" }
        val file = File(directory, "$id.txt")
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "分享文字路径无效" }
        return file
    }

    companion object {
        const val DIRECTORY = "shared_text"
        private val lock = Any()
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        private const val MAX_FILES = 128
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
