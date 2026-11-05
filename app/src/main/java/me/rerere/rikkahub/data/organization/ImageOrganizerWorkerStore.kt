package me.rerere.rikkahub.data.organization

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 识别并发数单独存自己的小 JSON，不动 `model.json`，也不动全局 `Settings`。
 *
 * 文件：`filesDir/image_organizer/workers.json`，内容 `{"version":1,"workers":3}`。
 *
 * 读写语义和 [ImageOrganizerModelStore] 一致：写只有「整份成功」和「整份失败」两种结果
 * （临时文件 → fsync → rename 覆盖），同一个文件在任何实例上共用一把进程内的锁。
 * 读出来的值一律夹到 [ImageOrganizerState.MIN_WORKERS]..[ImageOrganizerState.MAX_WORKERS]，
 * 文件坏了 / 不在 / 值离谱时回落到 [ImageOrganizerState.DEFAULT_WORKERS]。
 */
class ImageOrganizerWorkerStore(private val file: File) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(key: String): Any =
            locks[key] ?: Any().let { created -> locks.putIfAbsent(key, created) ?: created }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val lock: Any = lockFor(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })

    /** 最近一次写失败的原因；写成功后清空。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 读不出来一律给默认值，不抛异常。 */
    fun read(): Int = synchronized(lock) {
        if (!file.isFile) return@synchronized ImageOrganizerState.DEFAULT_WORKERS
        runCatching {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            val raw = (obj["workers"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
            raw?.let(ImageOrganizerState::coerceWorkers) ?: ImageOrganizerState.DEFAULT_WORKERS
        }.getOrDefault(ImageOrganizerState.DEFAULT_WORKERS)
    }

    /**
     * 原子写。
     *
     * @return true 表示已经完整写到磁盘；false 表示没写成，磁盘上还是上一次的值，[lastFailure] 有原因。
     */
    fun write(workers: Int): Boolean = synchronized(lock) {
        val value = ImageOrganizerState.coerceWorkers(workers)
        val payload = """{"version":1,"workers":$value}"""
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replaceAtomically(temp)) {
                throw IOException("临时文件改名失败")
            }
            lastFailure = null
            true
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            lastFailure = "并发设置没能保存：${t.message ?: t::class.simpleName ?: "未知原因"}"
            false
        }
    }

    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
