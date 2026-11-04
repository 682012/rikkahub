package me.rerere.ui.webview

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 内置预览页里“大段 HTML”的落盘缓存。
 *
 * 预览页只在导航状态里带一个 64 位 sha256 id，正文放在 cacheDir，避免把整页 HTML 塞进
 * SavedState/BackStack（Navigation3 会把 Screen 序列化，几百 KB 的正文会拖慢每一帧）。
 *
 * 缓存是**有界**的，避免用户看了一堆模型生成的长页面后 cacheDir 无限长：
 * - 每条最多保留 [MAX_AGE_MILLIS]（24 小时）；
 * - 目录总量最多 [MAX_TOTAL_BYTES]（8 MiB）、最多 [MAX_ENTRIES] 条，超了就删最久没被读的；
 * - 刚写入的那一条永远保留（即使它自己就超过预算），保证当前页面一定能打开；
 * - 只清理自己的 `webview_content` 子目录，**不**碰 WebView 的 HTTP 缓存/Cookie，在线页面的登录态
 *   不会被这里清掉。
 */
object WebViewContentCache {
    private const val DIRECTORY_NAME = "webview_content"
    private const val HASH_LENGTH = 64

    /** 单条内容最长保留 24 小时 */
    internal val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(24)

    /** 目录总量上限 8 MiB */
    internal const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /** 目录条数上限，防止大量极小条目把 inode 撑爆 */
    internal const val MAX_ENTRIES = 32

    private val hexDigits = "0123456789abcdef".toCharArray()

    fun store(cacheDir: File, content: String): String {
        val id = content.sha256()
        val directory = contentDirectory(cacheDir)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create WebView content cache directory"
        }

        val file = File(directory, id)
        if (!file.isFile) {
            file.writeText(content)
        }
        file.setLastModified(System.currentTimeMillis())

        prune(directory, keep = file)
        return id
    }

    fun load(cacheDir: File, id: String): String? {
        if (!id.isSha256()) return null

        val file = File(contentDirectory(cacheDir), id)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MILLIS) {
            file.delete()
            return null
        }

        return runCatching {
            file.readText().also {
                file.setLastModified(System.currentTimeMillis())
            }
        }.getOrNull()
    }

    /**
     * 清理过期与超预算的条目。[keep] 是刚写入的条目，即使超预算也不删。
     * 内部可见，方便单测在不写满 8 MiB 的情况下验证淘汰顺序。
     */
    internal fun prune(
        directory: File,
        keep: File? = null,
        now: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        val survivors = ArrayList<File>(files.size)

        files.forEach { file ->
            val expired = now - file.lastModified() > maxAgeMillis
            if (expired && file != keep) {
                file.delete()
            } else {
                survivors += file
            }
        }

        // 最久没被访问的排在最前；保留 keep 与最近使用的
        survivors.sortBy { it.lastModified() }
        var totalBytes = survivors.sumOf { it.length() }
        var count = survivors.size
        survivors.forEach { file ->
            if (count <= maxEntries && totalBytes <= maxTotalBytes) return
            if (file == keep) return@forEach
            // 先记长度再删：File.length() 在删除后返回 0，直接减会把账算错，导致多删文件
            val length = file.length()
            if (file.delete()) {
                count -= 1
                totalBytes -= length
            }
        }
    }

    private fun contentDirectory(cacheDir: File) = File(cacheDir, DIRECTORY_NAME)

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return CharArray(bytes.size * 2).also { result ->
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                result[index * 2] = hexDigits[value ushr 4]
                result[index * 2 + 1] = hexDigits[value and 0x0f]
            }
        }.concatToString()
    }

    private fun String.isSha256(): Boolean =
        length == HASH_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }
}
