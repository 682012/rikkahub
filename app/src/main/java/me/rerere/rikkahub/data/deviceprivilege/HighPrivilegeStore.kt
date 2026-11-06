package me.rerere.rikkahub.data.deviceprivilege

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 自动化授权的小文件存储（设备本地、默认全关、撤销就是写一份全关记录）。
 *
 * 三条硬规则：
 *  - 文件不存在 / 损坏 / 超长 → 返回默认全关，绝不"猜成已授权"。
 *  - 原子写：临时文件 fsync 后 rename，半截 JSON 不会变成"授权状态不明"。
 *  - 纯 JVM 可测：不依赖 Context，单测直接给一个临时目录。
 */
class HighPrivilegeStore(private val directory: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    @Volatile
    private var lastError: String? = null

    fun lastError(): String? = lastError

    fun read(): HighPrivilegeAutomation = synchronized(lock) {
        val file = file()
        if (!file.exists()) return@synchronized HighPrivilegeAutomation()
        try {
            require(file.length() <= MAX_BYTES) { "record is too large" }
            json.decodeFromString(HighPrivilegeAutomation.serializer(), file.readText()).normalized().also {
                lastError = null
            }
        } catch (t: Throwable) {
            lastError = "Stored privilege automation record is unreadable; automation stays off until re-enabled."
            HighPrivilegeAutomation()
        }
    }

    fun write(state: HighPrivilegeAutomation) = synchronized(lock) {
        val normalized = state.normalized()
        check(directory.isDirectory || directory.mkdirs()) { "cannot create the privilege record directory" }
        val destination = file()
        val staging = File(directory, ".automation.pending")
        val bytes = json.encodeToString(HighPrivilegeAutomation.serializer(), normalized).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "record is too large" }
        try {
            FileOutputStream(staging).use { it.write(bytes); it.fd.sync() }
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            lastError = null
        } finally {
            staging.delete()
        }
    }

    private fun file(): File = File(directory, "automation.json")

    private companion object {
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * 归一化：写权限不能脱离总开关存在；空字符串的 assistant id 一律当"没绑定"，
 * 绝不当成通配。
 */
fun HighPrivilegeAutomation.normalized(): HighPrivilegeAutomation = copy(
    allowWrites = enabled && allowWrites,
    assistantId = assistantId?.trim()?.takeIf { it.isNotEmpty() },
)
