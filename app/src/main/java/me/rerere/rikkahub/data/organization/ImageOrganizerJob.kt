package me.rerere.rikkahub.data.organization

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 后台识别队列的持久化任务单。
 *
 * 为什么单独存一份：识别要花钱，进程被杀 / 用户退到后台之后不能靠内存里的 progress 猜进度。
 * 每张图识别完立刻回写一次，重启后只处理 [pendingIds] 里还没做的，已经完成的和已经失败过的都不会再发请求
 * （失败可能是「请求已经发出去、钱已经花了」，静默重试等于重复计费）。
 */
enum class ImageJobStatus {
    /** 已入队，还没开始跑。 */
    QUEUED,

    /** 有执行者在跑。 */
    RUNNING,

    /** 被系统中断（进程死亡 / 任务被停），剩余项等用户显式点「继续」。 */
    PAUSED,

    /** 全部处理完（含单张失败）。 */
    DONE,

    /** 用户取消：已完成的保留，未开始的直接放弃。 */
    CANCELLED,

    /** 无法继续（模型不可用、任务单损坏等），剩余项等用户处理后重开。 */
    FAILED,
}

@Serializable
data class ImageOrganizerJob(
    val id: String,
    val status: ImageJobStatus = ImageJobStatus.QUEUED,
    /** 还没处理的图片 id，顺序就是入队时的顺序。 */
    val pendingIds: List<String> = emptyList(),
    /** 已经拿到结果（成功或单张失败）的图片 id。 */
    val doneIds: List<String> = emptyList(),
    /** 单张识别失败、且不会自动重试的图片 id。 */
    val failedIds: List<String> = emptyList(),
    val total: Int = 0,
    val modelId: String? = null,
    val workers: Int = ImageOrganizerState.DEFAULT_WORKERS,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * 当前执行者的随机标记。
     * 重启后执行者标记变了，就能区分「同一个进程在跑」和「上次进程被杀了」。
     */
    val runnerToken: String? = null,
    val error: String? = null,
) {
    val finishedCount: Int get() = doneIds.size
    val remaining: Int get() = pendingIds.size

    /** 这一单还要不要人管：终态以外都算活跃。 */
    val active: Boolean get() = status == ImageJobStatus.QUEUED || status == ImageJobStatus.RUNNING

    fun progress(currentName: String? = null): ImageProgress = ImageProgress(
        total = maxOf(total, doneIds.size + failedIds.size + pendingIds.size),
        done = doneIds.size + failedIds.size,
        failed = failedIds.size,
        currentName = currentName,
    )

    /** 一张做完：从待办移到完成/失败。返回新任务单，不落盘。 */
    fun record(id: String, failed: Boolean): ImageOrganizerJob = copy(
        pendingIds = pendingIds - id,
        doneIds = if (failed || id in doneIds) doneIds else doneIds + id,
        failedIds = if (failed && id !in failedIds) failedIds + id else failedIds,
        updatedAt = System.currentTimeMillis(),
    )

    /**
     * 上一个进程被系统杀掉后继续执行前要做的处理：不改动剩余项，先停下来让用户确认，
     * 避免把已经发出去过的请求再安静地发一遍。
     */
    fun pausedAfterInterruption(now: Long = System.currentTimeMillis()): ImageOrganizerJob = copy(
        status = ImageJobStatus.PAUSED,
        runnerToken = null,
        updatedAt = now,
        error = if (remaining > 0) "上次后台识别被系统中断，还剩 $remaining 张没有处理" else error,
    )

    /** 收尾：还有待办就保持 PAUSED 等用户决定，否则算完成。 */
    fun settled(now: Long = System.currentTimeMillis()): ImageOrganizerJob = if (pendingIds.isEmpty()) {
        copy(status = ImageJobStatus.DONE, runnerToken = null, updatedAt = now)
    } else {
        copy(status = ImageJobStatus.PAUSED, runnerToken = null, updatedAt = now)
    }

    fun cancelled(now: Long = System.currentTimeMillis()): ImageOrganizerJob = copy(
        status = ImageJobStatus.CANCELLED,
        runnerToken = null,
        updatedAt = now,
        error = if (remaining > 0) "已取消，${remaining} 张没有处理" else error,
    )
}

/**
 * `filesDir/image_organizer/queue.json` 的读写。
 *
 * 写入走「临时文件 → fsync → rename」，读失败一律当没有（不影响页面打开，也不会重复发请求）。
 */
class ImageOrganizerJobStore(private val file: File) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(key: String): Any =
            locks[key] ?: Any().let { created -> locks.putIfAbsent(key, created) ?: created }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }

    private val lock: Any = lockFor(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })

    @Volatile
    var lastFailure: String? = null
        private set

    fun read(): ImageOrganizerJob? = synchronized(lock) {
        lastFailure = null
        if (!file.exists()) return@synchronized null
        runCatching { json.decodeFromString<ImageOrganizerJob>(file.readText()) }
            .onFailure { lastFailure = "后台队列无法读取：${it.message}" }.getOrNull()
    }

    fun write(job: ImageOrganizerJob?): Boolean = synchronized(lock) {
        if (job == null) {
            val deleted = !file.exists() || file.delete()
            lastFailure = if (deleted) null else "后台队列删除失败"
            return@synchronized deleted
        }
        val payload = json.encodeToString(job)
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
            lastFailure = "后台识别进度没能保存：${t.message ?: t::class.simpleName ?: "未知原因"}"
            false
        }
    }

    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
