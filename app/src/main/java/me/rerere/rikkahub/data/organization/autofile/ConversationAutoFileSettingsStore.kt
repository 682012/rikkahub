package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * 按助手存自动归库设置：`<根目录>/settings/<assistantUUID>.json`，一个助手一个文件。
 *
 * 规则：
 * - 写入走 [AutoFileAtomicFile]，断电最多留下旧版本；
 * - 文件损坏、或文件里的 assistantId 和文件名对不上（被复制/手动改名），都当作**未设置**，
 *   默认关闭，并把坏文件改名成 `*.bak` 留档，不静默吞掉；
 * - 只读本地文件，不发任何请求，纯 JVM 可测。
 */
@Serializable
internal data class StoredAutoFileSettings(
    val version: Int = 1,
    val assistantId: String = "",
    val settings: ConversationAutoFileSettings = ConversationAutoFileSettings.DEFAULT,
)

class ConversationAutoFileSettingsStore(
    private val rootDir: File,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dir: File get() = File(rootDir, "settings")

    /** 同一进程里多个实例写同一个文件时串行，避免互相覆盖。 */
    private val mutex: Mutex = lockFor(runCatching { dir.absolutePath }.getOrElse { rootDir.path })

    fun fileFor(assistantId: Uuid): File = File(dir, "$assistantId.json")

    /**
     * 读设置。不存在 → 默认关闭；损坏/错归属 → 备份后按默认关闭处理。
     */
    fun read(assistantId: Uuid): ConversationAutoFileSettings {
        val file = fileFor(assistantId)
        val text = AutoFileAtomicFile.readText(file) ?: return ConversationAutoFileSettings.DEFAULT
        val stored = runCatching { json.decodeFromString(StoredAutoFileSettings.serializer(), text) }.getOrNull()
        if (stored == null) {
            AutoFileAtomicFile.quarantine(file, "corrupt")
            return ConversationAutoFileSettings.DEFAULT
        }
        if (stored.assistantId != assistantId.toString()) {
            // 文件被复制到了别的助手名下：不认这份内容，避免把 A 的开关状态带到 B。
            AutoFileAtomicFile.quarantine(file, "mismatch")
            return ConversationAutoFileSettings.DEFAULT
        }
        return stored.settings.clamped()
    }

    suspend fun write(assistantId: Uuid, settings: ConversationAutoFileSettings) {
        val payload = StoredAutoFileSettings(
            version = 1,
            assistantId = assistantId.toString(),
            settings = settings.clamped(),
        )
        val text = json.encodeToString(StoredAutoFileSettings.serializer(), payload)
        mutex.withLock { AutoFileAtomicFile.write(fileFor(assistantId), text) }
    }

    /**
     * 哪些助手是开着的。启动时只给这些助手恢复定时任务，**不默认扫全库**。
     * 坏文件在这里同样被隔离，不会因为一个坏文件让整个调度起不来。
     */
    fun enabledAssistantIds(): List<Uuid> {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val id = runCatching { Uuid.parse(file.name.removeSuffix(".json")) }.getOrNull()
                    ?: return@mapNotNull null
                val settings = runCatching { read(id) }.getOrNull() ?: return@mapNotNull null
                if (settings.enabled) id else null
            }
            .toList()
    }

    internal companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(key: String): Mutex =
            locks[key] ?: Mutex().let { created -> locks.putIfAbsent(key, created) ?: created }
    }
}
