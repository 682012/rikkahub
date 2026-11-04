package me.rerere.rikkahub.data.organization

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - 所有写操作在**同一个进程共用的** [Mutex] 里做「重读磁盘 → 改 → 原子替换」，
 *   进程崩溃不会留半个 JSON；同一个文件被多个实例持有时（多个 ViewModel / 页面重建）也不会互相覆盖
 * - 落盘失败**不会被吞**：抛 [ImageCachePersistException]，同时把失败挂到 [persistFailure] 给人看；
 *   内存里的状态保持落盘前的样子，不会出现「界面显示已保存、磁盘上没有」。
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
    /**
     * 每次成功落盘就 +1。
     *
     * 同一个进程里可能有多个 [ImageOrganizerCache] 指向同一个文件（页面重建、两个 ViewModel），
     * 光看 mtime + 长度在同毫秒写入时可能分辨不出「磁盘上的已经是别人写的版本」，所以把版本号写进 JSON：
     * 谁写成功的，谁持有最新版本；别的实例下次进锁时对不上号就重读磁盘再改，改动是合并而不是覆盖。
     */
    val revision: Long = 0L,
    val entries: Map<String, ImageCacheEntry> = emptyMap(),
    /** 用户标记保留的稳定 id（`upload:1` / `gen:2` / `media:...`）。 */
    val protectedIds: Set<String> = emptySet(),
)

object ImageCacheKeys {
    /** 缓存 key：[sourceId] 用稳定 id，[size]/[mtime] 用来判断文件是否被换过。 */
    fun of(sourceId: String, relativePath: String, size: Long, lastModified: Long): String =
        "$sourceId|$relativePath|$size|$lastModified"
}

/** 一次落盘失败。[file] 和 [message] 可以直接给人看，[cause] 留给日志。 */
data class ImageCachePersistFailure(
    val file: File,
    val message: String,
    val cause: Throwable? = null,
)

/**
 * 落盘失败。抛出时内存状态**没有**被改成新值，磁盘上也还是上一版，两边一致。
 *
 * 调用方要么提示用户（「保留标记没能保存」），要么至少不要当成成功继续往下走。
 */
class ImageCachePersistException(val failure: ImageCachePersistFailure) :
    IOException(failure.message, failure.cause)

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

        // ---- 同进程共享状态：同一个缓存文件的所有实例共用一把锁和一个版本号 ----

        /** 缓存文件（canonical 路径）→ 锁。同一个文件在任何实例上都串行。 */
        private val locks = ConcurrentHashMap<String, Mutex>()

        /** 缓存文件（canonical 路径）→ 进程内已成功落盘的次数。 */
        private val revisions = ConcurrentHashMap<String, Long>()

        private fun lockFor(key: String): Mutex =
            locks[key] ?: Mutex().let { created -> locks.putIfAbsent(key, created) ?: created }
    }

    /** 这个实例对应的文件标识：canonical 路径，解析不了就退回绝对路径。 */
    private val storeKey: String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private val mutex: Mutex = lockFor(storeKey)

    private var loaded = false
    private var data = ImageCacheFile()

    /** 上次读/写磁盘时的文件指纹（长度 + mtime），用来发现别的进程改过文件。 */
    private var stamp: String? = null

    /** 上次读/写磁盘时 [revisions] 的值，对不上就说明同进程别的实例写过。 */
    private var seenRevision = 0L

    private val _persistFailure = MutableStateFlow<ImageCachePersistFailure?>(null)

    /**
     * 最近一次落盘失败，成功落盘后自动清空。
     *
     * 给界面用：拿它提示「保留标记 / 识别结果没能保存」，不必去猜 [ImageCachePersistException] 有没有发生。
     */
    val persistFailure: StateFlow<ImageCachePersistFailure?> = _persistFailure.asStateFlow()

    /** 读命中且未过期时返回条目。 */
    suspend fun get(key: String): ImageCacheEntry? = mutex.withLock {
        ensureFresh()
        val entry = data.entries[key] ?: return@withLock null
        if (now() - entry.at > expireAfterMillis) {
            // 过期条目顺手清掉。清不掉（写盘失败）只影响这次清理，读取照常返回 null，条目下次还会再清
            runCatching {
                commit(data.copy(revision = data.revision + 1, entries = data.entries - key))
            }
            return@withLock null
        }
        entry
    }

    /** 写一条识别结果。落盘失败抛 [ImageCachePersistException]，内存保持原样。 */
    suspend fun put(key: String, category: ImageCategory, description: String?) = mutex.withLock {
        mutate { current ->
            val entries = current.entries.toMutableMap()
            entries[key] = ImageCacheEntry(
                category = category.name,
                description = description?.takeIf { it.isNotBlank() },
                at = now(),
            )
            // 过期清理 + 条数上限：只留最新的若干条，别让 JSON 无限长
            val fresh = entries.filterValues { now() - it.at <= expireAfterMillis }
            val kept = if (fresh.size > maxEntries) {
                fresh.entries.sortedByDescending { it.value.at }.take(maxEntries).associate { it.key to it.value }
            } else {
                fresh
            }
            current.copy(entries = kept)
        }
        Unit
    }

    suspend fun isProtected(id: String): Boolean = mutex.withLock {
        ensureFresh()
        id in data.protectedIds
    }

    /** 一次取出所有保留 id，扫描时批量应用，避免逐条加锁。 */
    suspend fun protectedIds(): Set<String> = mutex.withLock {
        ensureFresh()
        data.protectedIds
    }

    /** 打/取消一个保留标记。落盘失败抛 [ImageCachePersistException]，磁盘和内存都还是旧标记。 */
    suspend fun setProtected(id: String, protected: Boolean) = mutex.withLock {
        mutate { current ->
            val ids = current.protectedIds.toMutableSet()
            if (protected) ids.add(id) else ids.remove(id)
            if (ids == current.protectedIds) null else current.copy(protectedIds = ids)
        }
        Unit
    }

    suspend fun setProtected(ids: Collection<String>, protected: Boolean) = mutex.withLock {
        mutate { current ->
            val next = current.protectedIds.toMutableSet()
            if (protected) next.addAll(ids) else next.removeAll(ids.toSet())
            if (next == current.protectedIds) null else current.copy(protectedIds = next)
        }
        Unit
    }

    /** 删除条目本身（文件没了以后清缓存）。 */
    suspend fun remove(key: String) = mutex.withLock {
        mutate { current ->
            if (key in current.entries) current.copy(entries = current.entries - key) else null
        }
        Unit
    }

    /** 丢掉除保留标记以外的全部识别结果。 */
    suspend fun clearResults() = mutex.withLock {
        mutate { current ->
            if (current.entries.isEmpty()) null else current.copy(entries = emptyMap())
        }
        Unit
    }

    // ---- 状态同步与落盘（调用方都已持有 [mutex]，所有方法都是非挂起的纯文件操作） ----

    /**
     * 保证 [data] 是磁盘上最新的一版：同进程别的实例写过（[seenRevision] 对不上）或者
     * 文件指纹变了（别的进程写过）就重读。
     *
     * 重读发生在锁里，所以「读到的内容」直接就是这次改写的基础，写回去的是合并结果而不是覆盖。
     */
    private fun ensureFresh() {
        val shared = revisions[storeKey] ?: 0L
        val disk = diskStamp()
        if (loaded && shared == seenRevision && disk == stamp) return
        // 先取指纹再读内容：万一这中间有人写，我们会认为自己落后，下次再读一遍，不会把旧内容当成最新
        stamp = disk
        data = readFile()
        seenRevision = shared
        loaded = true
    }

    private fun readFile(): ImageCacheFile = runCatching {
        if (file.isFile) json.decodeFromString<ImageCacheFile>(file.readText()) else ImageCacheFile()
    }.getOrDefault(ImageCacheFile())

    /** 文件指纹；文件不在或读不到属性时返回 null（当作「没有」）。 */
    private fun diskStamp(): String? = runCatching {
        if (file.isFile) "${file.length()}:${file.lastModified()}" else null
    }.getOrNull()

    /**
     * 在当前状态上改出 [block] 的结果并落盘。
     *
     * [block] 返回 null 表示没有变化，不落盘；落盘失败会把异常抛给调用方，[data] 保持调用前的值。
     */
    private inline fun mutate(block: (ImageCacheFile) -> ImageCacheFile?): Boolean {
        ensureFresh()
        val before = data
        val next = block(before) ?: return false
        if (next.copy(revision = before.revision) == before) return false
        commit(next.copy(revision = before.revision + 1))
        return true
    }

    /**
     * 把 [next] 写成文件：临时文件 → fsync → rename 覆盖。
     *
     * 只有整份写完并改名成功才算数：[data]、[stamp]、[seenRevision] 都在成功之后才更新，
     * 失败时原文件和内存里的旧状态都不动，同时把失败记到 [persistFailure]。
     */
    private fun commit(next: ImageCacheFile) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            val payload = json.encodeToString(next)
            FileOutputStream(temp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replaceAtomically(temp)) {
                throw IOException("临时文件改名失败")
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            val failure = ImageCachePersistFailure(
                file = file,
                message = "图片整理缓存没能写入：${t.message ?: t::class.simpleName ?: "未知原因"}",
                cause = t,
            )
            _persistFailure.value = failure
            throw ImageCachePersistException(failure)
        }
        // 先认账自己的这一版，再记指纹：别的实例看到版本号/指纹变了就会重读，而不是拿旧状态覆盖
        data = next
        val written = (revisions[storeKey] ?: 0L) + 1
        revisions[storeKey] = written
        seenRevision = written
        stamp = diskStamp()
        _persistFailure.value = null
    }

    /** rename 在个别文件系统上不支持覆盖，用 [Files.move] 再试一次；仍失败就报错，不退回「直接覆盖」。 */
    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
