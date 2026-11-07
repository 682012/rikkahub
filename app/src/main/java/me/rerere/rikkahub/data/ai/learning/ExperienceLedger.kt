package me.rerere.rikkahub.data.ai.learning

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 「哪些轮次已经总结过、哪些经验文本已经写过」的记账本。
 *
 * 每助手一个文件 `rootDir/<assistantId>.experience.json`，和指标文件（`<id>.json`）分开放：
 * 指标文件是既有格式和既有测试的领地，这里不改它的 schema。
 *
 * 契约：
 * - 写入顺序：临时文件 → fsync → 原子改名；失败抛异常，旧文件不受影响；
 * - 全进程按路径共用一把 [Mutex]，读改写都在锁里，写前重读磁盘；
 * - 文件坏了就当没有记录（不是当崩溃）：去总结一次不会丢用户数据，只是可能重复写一行，
 *   而记忆库追加本身也有内容去重兜底；
 * - 只有指纹，没有第二条原文副本。
 */
class ExperienceLedger(
    private val rootDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun fileFor(assistantId: String): File = File(rootDir, sanitize(assistantId) + ".experience.json")

    /**
     * 记账操作：读 → 判定 → 可选写。
     *
     * [block] 拿到当前记录，返回 null 表示「什么都不用改」（第一项为 null），返回新记录则落盘。
     * 整个过程在该文件的锁里完成，所以两个并发触发不会都认为自己是第一次。
     */
    suspend fun <T> transact(assistantId: String, block: (ExperienceFile) -> Pair<ExperienceFile?, T>): T =
        withContext(ioDispatcher) {
            val file = fileFor(assistantId)
            lockFor(file).withLock {
                val current = load(file, assistantId)
                val (next, result) = block(current)
                if (next != null && next != current) commit(file, next)
                result
            }
        }

    /** 只读快照（设置页/诊断用）。 */
    suspend fun read(assistantId: String): ExperienceFile = withContext(ioDispatcher) {
        val file = fileFor(assistantId)
        lockFor(file).withLock { load(file, assistantId) }
    }

    /** 清空记录（用户点了「清空经验学习记录」）。文件直接删掉，下次从空记录开始。 */
    suspend fun clear(assistantId: String) = withContext(ioDispatcher) {
        val file = fileFor(assistantId)
        lockFor(file).withLock {
            runCatching { if (file.isFile) file.delete() }
            Unit
        }
    }

    private fun load(file: File, assistantId: String): ExperienceFile {
        if (!file.isFile) return ExperienceFile(assistantId = assistantId)
        return try {
            val parsed = json.decodeFromString(ExperienceFile.serializer(), file.readText())
            if (parsed.version != LearningExperienceLimits.SCHEMA_VERSION) {
                ExperienceFile(assistantId = assistantId)
            } else {
                parsed.normalize(assistantId)
            }
        } catch (_: Exception) {
            // 读不出来就当空记录：不抛给调用方，也不动用户的其它文件
            ExperienceFile(assistantId = assistantId)
        }
    }

    private fun ExperienceFile.normalize(assistantId: String): ExperienceFile = copy(
        version = LearningExperienceLimits.SCHEMA_VERSION,
        assistantId = assistantId,
        handled = handled.takeLast(LearningExperienceLimits.MAX_HANDLED),
        memoryFingerprints = memoryFingerprints.takeLast(LearningExperienceLimits.MAX_FINGERPRINTS),
        knowledgeFingerprints = knowledgeFingerprints.takeLast(LearningExperienceLimits.MAX_FINGERPRINTS),
        recent = recent.takeLast(LearningExperienceLimits.MAX_RUN_RECORDS),
    )

    private fun commit(file: File, next: ExperienceFile) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create experience ledger dir: ${parent.absolutePath}")
        }
        val temp = File(parent, file.name + "." + UUID.randomUUID() + ".tmp")
        try {
            val bytes = json.encodeToString(ExperienceFile.serializer(), next.copy(updatedAt = clock()))
                .toByteArray(Charsets.UTF_8)
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replace(temp, file)) {
                throw IOException("Cannot rename experience ledger: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            runCatching { temp.delete() }
            throw e
        }
    }

    private fun replace(temp: File, target: File): Boolean = runCatching {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    private fun sanitize(id: String): String {
        val clean = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(clean.isNotEmpty()) { "assistantId must not be blank" }
        return clean
    }

    private companion object {
        val locks = Array(16) { Mutex() }

        fun lockFor(file: File): Mutex = locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
    }
}

/** 轮次去重用的 key：同一会话 + 同一条助手消息。 */
internal fun experienceKey(conversationId: String, messageId: String): String = "$conversationId|$messageId"

/** 追加一条指纹并去重、截断到上限。 */
internal fun List<String>.plusCapped(extra: String, cap: Int): List<String> =
    (this + extra).distinct().takeLast(cap)

/** 进程内正在总结中的轮次，防止同一轮被并发触发两次（跨进程也有文件记账兜底）。 */
internal object ExperienceInFlight {
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** 返回 true 表示这次真的抢到了；false 表示已经有人在处理这一轮。 */
    fun tryAcquire(key: String): Boolean = active.add(key)

    fun release(key: String) {
        active.remove(key)
    }
}
