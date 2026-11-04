package me.rerere.rikkahub.data.organization

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地小 JSON 缓存：记识别结果和用户的「保留」标记，不拷贝原图。
 *
 * - 文件：`filesDir/image_organizer/classification_cache.json`
 * - key：来源 id + 相对路径 + 大小 + mtime，文件被替换后自动失效重识别
 * - 保留标记按稳定 id 存，不随文件 mtime 变化丢失
 * - 所有写操作在 [Mutex] 里做整体读改写，再原子替换文件（临时文件 + rename），进程崩溃不会留半个 JSON
 */

@Serializable
data class ImageCacheEntry(
    val category: String,
    val description: String? = null,
    val at: Long = 0L,
)

@Serializable
data class ImageCacheFile(
    val version: Int = 1,
    val entries: Map<String, ImageCacheEntry> = emptyMap(),
    /** 用户标记保留的稳定 id（`upload:1` / `gen:2` / `media:...`）。 */
    val protectedIds: Set<String> = emptySet(),
)

object ImageCacheKeys {
    /** 缓存 key：[sourceId] 用稳定 id，[size]/[mtime] 用来判断文件是否被换过。 */
    fun of(sourceId: String, relativePath: String, size: Long, lastModified: Long): String =
        "$sourceId|$relativePath|$size|$lastModified"
}

class ImageOrganizerCache(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val expireAfterMillis: Long = DEFAULT_EXPIRE_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_EXPIRE_MILLIS = 90L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_ENTRIES = 4000
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private var loaded = false
    private var data = ImageCacheFile()

    /** 读命中且未过期时返回条目。 */
    suspend fun get(key: String): ImageCacheEntry? = mutex.withLock {
        ensureLoaded()
        val entry = data.entries[key] ?: return@withLock null
        if (now() - entry.at > expireAfterMillis) {
            data = data.copy(entries = data.entries - key)
            persist()
            return@withLock null
        }
        entry
    }

    suspend fun put(key: String, category: ImageCategory, description: String?) = mutex.withLock {
        ensureLoaded()
        val entries = data.entries.toMutableMap()
        entries[key] = ImageCacheEntry(
            category = category.name,
            description = description?.takeIf { it.isNotBlank() },
            at = now(),
        )
        // 过期清理 + 条数上限：只留最新的若干条，别让 JSON 无限长
        val fresh = entries.filterValues { now() - it.at <= expireAfterMillis }
        data = data.copy(
            entries = if (fresh.size > maxEntries) {
                fresh.entries.sortedByDescending { it.value.at }.take(maxEntries).associate { it.key to it.value }
            } else {
                fresh
            }
        )
        persist()
    }

    suspend fun isProtected(id: String): Boolean = mutex.withLock {
        ensureLoaded()
        id in data.protectedIds
    }

    /** 一次取出所有保留 id，扫描时批量应用，避免逐条加锁。 */
    suspend fun protectedIds(): Set<String> = mutex.withLock {
        ensureLoaded()
        data.protectedIds
    }

    suspend fun setProtected(id: String, protected: Boolean) = mutex.withLock {
        ensureLoaded()
        val ids = data.protectedIds.toMutableSet()
        if (protected) ids.add(id) else ids.remove(id)
        if (ids == data.protectedIds) return@withLock
        data = data.copy(protectedIds = ids)
        persist()
    }

    suspend fun setProtected(ids: Collection<String>, protected: Boolean) = mutex.withLock {
        ensureLoaded()
        val current = data.protectedIds.toMutableSet()
        if (protected) current.addAll(ids) else current.removeAll(ids.toSet())
        if (current == data.protectedIds) return@withLock
        data = data.copy(protectedIds = current)
        persist()
    }

    /** 删除条目本身（文件没了以后清缓存）。 */
    suspend fun remove(key: String) = mutex.withLock {
        ensureLoaded()
        if (key !in data.entries) return@withLock
        data = data.copy(entries = data.entries - key)
        persist()
    }

    /** 丢掉除保留标记以外的全部识别结果。 */
    suspend fun clearResults() = mutex.withLock {
        ensureLoaded()
        data = data.copy(entries = emptyMap())
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        data = runCatching {
            if (file.isFile) json.decodeFromString<ImageCacheFile>(file.readText()) else ImageCacheFile()
        }.getOrDefault(ImageCacheFile())
    }

    /** 调用方已持有 [mutex]。先写临时文件再 rename，避免半个文件。 */
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(data))
            if (!temp.renameTo(file)) {
                // rename 在个别文件系统上会失败（目标已存在），退回直接覆盖
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
