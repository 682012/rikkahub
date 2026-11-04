package me.rerere.rikkahub.data.organization

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * 识别模型的选择存自己的小 JSON，不动全局 `Settings`（主设置里的模型选择属于别的功能）。
 *
 * 文件：`filesDir/image_organizer/model.json`，内容 `{"version":1,"modelId":"<uuid>"}`。
 */
class ImageOrganizerModelStore(private val file: File) {
    private val lock = Any()

    fun read(): Uuid? = synchronized(lock) {
        if (!file.isFile) return@synchronized null
        runCatching {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            val raw = (obj["modelId"] as? JsonPrimitive)?.content?.trim()
            raw?.takeIf { it.isNotEmpty() }?.let(Uuid::parse)
        }.getOrNull()
    }

    /** 原子写：先写临时文件再 rename，避免进程被杀时留下半个 JSON。 */
    fun write(modelId: Uuid?) = synchronized(lock) {
        runCatching {
            val dir = file.parentFile
            dir?.mkdirs()
            val payload = if (modelId == null) {
                """{"version":1}"""
            } else {
                """{"version":1,"modelId":"$modelId"}"""
            }
            val temp = File(dir, "${file.name}.tmp")
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                file.writeText(payload)
                temp.delete()
            }
        }
        Unit
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
