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
import kotlin.uuid.Uuid

/**
 * 识别模型的选择存自己的小 JSON，不动全局 `Settings`（主设置里的模型选择属于别的功能）。
 *
 * 文件：`filesDir/image_organizer/model.json`，内容 `{"version":1,"modelId":"<uuid>"}`。
 *
 * 写只有「整份成功」和「整份失败」两种结果：临时文件 → fsync → rename 覆盖，失败不吞，
 * 由 [write] 的返回值告诉调用方。同一个文件被多个实例持有（页面重建、两个 ViewModel）时共用一把进程内的锁，
 * 不会出现两次写互相踩。
 */
class ImageOrganizerModelStore(private val file: File) {
    companion object {
        /** 文件（canonical 路径）→ 锁。同一个文件在任何实例上都串行。 */
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(key: String): Any =
            locks[key] ?: Any().let { created -> locks.putIfAbsent(key, created) ?: created }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val lock: Any = lockFor(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })

    /** 最近一次写失败的原因；写成功后清空。给界面提示「模型选择没能保存」。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 读不出来（文件不在 / 内容坏了 / 被清空）都当「没选模型」，不抛异常。 */
    fun read(): Uuid? = synchronized(lock) {
        if (!file.isFile) return@synchronized null
        runCatching {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            val raw = (obj["modelId"] as? JsonPrimitive)?.content?.trim()
            raw?.takeIf { it.isNotEmpty() }?.let(Uuid::parse)
        }.getOrNull()
    }

    /**
     * 原子写：先写临时文件再 rename，避免进程被杀时留下半个 JSON。
     *
     * @return true 表示已经完整写到磁盘；false 表示没写成，磁盘上还是上一次的值，[lastFailure] 有原因。
     */
    fun write(modelId: Uuid?): Boolean = synchronized(lock) {
        val payload = if (modelId == null) {
            """{"version":1}"""
        } else {
            """{"version":1,"modelId":"$modelId"}"""
        }
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
            lastFailure = "识别模型选择没能保存：${t.message ?: t::class.simpleName ?: "未知原因"}"
            false
        }
    }

    /** rename 在个别文件系统上不支持覆盖，用 [Files.move] 再试一次；仍失败就报失败，不退回「直接覆盖」。 */
    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
