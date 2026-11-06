package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * 归库批次日志：先写 planned 落盘，再逐项改归属，每项改完立刻回写结果。
 *
 * 不变量：
 * - **先把计划写进磁盘，再动数据库**：崩溃时最多看到「计划里列了、实际没做」，
 *   不会出现「已经搬走、却没有任何记录」的不可撤销状态；
 * - 每条搬动自己带状态（planned/applied/skipped/failed），撤销只碰确认做过的项；
 * - 崩溃留下 planned 的批次会被标成 interrupted，撤销时按「当前归属」再确认一次，
 *   不盲目重放、不重复调模型；
 * - 只存 id、文件夹 id、时间戳和状态，**不存标题、不存正文**。
 */
@Serializable
data class AutoFileJournalMove(
    val conversationId: String,
    val fromFolderId: String? = null,
    val toFolderId: String? = null,
    val folderName: String? = null,
    val status: String = AutoFileMoveStatus.PLANNED,
    val updateAtBefore: Long = 0L,
    val updateAtAfter: Long? = null,
    /** 可解释理由（本地规则模式给出，AI 模式为空）。只存一句短文本，不存正文。 */
    val reason: String? = null,
    val error: String? = null,
)

@Serializable
data class AutoFileJournalBatch(
    val id: String,
    val assistantId: String,
    val createdAt: Long,
    val status: String = AutoFileBatchStatus.PLANNED,
    /** 这批用的整理模式（`ai` / `local`），历史列表要显示「当时是哪种方式」。 */
    val mode: String = AutoFileMode.AI.name.lowercase(),
    val moves: List<AutoFileJournalMove> = emptyList(),
    /** 这批新建的文件夹（撤销时不删库，只把会话搬回来，避免误删用户后来放进去的东西）。 */
    val createdFolderIds: List<String> = emptyList(),
    val finishedAt: Long? = null,
) {
    val movedCount: Int get() = moves.count { it.status == AutoFileMoveStatus.APPLIED }
    val failedCount: Int get() = moves.count { it.status == AutoFileMoveStatus.FAILED }
    val skippedCount: Int get() = moves.count { it.status == AutoFileMoveStatus.SKIPPED }
    val undoneCount: Int get() = moves.count { it.status == AutoFileMoveStatus.UNDONE }

    /** [mode] 解析回枚举；坏值按 AI 处理（老日志没有这个字段）。 */
    val modeOrDefault: AutoFileMode
        get() = when (mode.lowercase()) {
            AutoFileMode.LOCAL.name.lowercase() -> AutoFileMode.LOCAL
            else -> AutoFileMode.AI
        }

    fun isUndoable(): Boolean = status in AutoFileBatchStatus.UNDOABLE

    /**
     * 是否有一条「还没收尾、又可能已经动过库」的项。
     *
     * planned 表示崩溃时不确定搬没搬（[PLANNED][AutoFileMoveStatus.PLANNED]），applied 表示确实搬过。
     * 只要批次自己也还没收尾（planned/interrupted），这种批次就不能被新批悄悄盖住。
     */
    fun isUnresolved(): Boolean =
        status in AutoFileBatchStatus.UNRESOLVED &&
            moves.any { it.status == AutoFileMoveStatus.PLANNED || it.status == AutoFileMoveStatus.APPLIED }
}

@Serializable
data class AutoFileJournalFile(
    val version: Int = 1,
    val assistantId: String = "",
    val batches: List<AutoFileJournalBatch> = emptyList(),
)

object AutoFileMoveStatus {
    const val PLANNED = "planned"
    const val APPLIED = "applied"
    const val SKIPPED = "skipped"
    const val FAILED = "failed"
    const val UNDONE = "undone"
}

object AutoFileBatchStatus {
    const val PLANNED = "planned"
    const val DONE = "done"
    const val PARTIAL = "partial"
    const val INTERRUPTED = "interrupted"
    const val UNDONE = "undone"

    /** 可以被撤销的批次状态。 */
    val UNDOABLE = setOf(DONE, PARTIAL, INTERRUPTED, PLANNED)

    /** 还没收尾的批次：可能是上次进程被杀留下的，撤销记录必须保住，不能让新批盖掉。 */
    val UNRESOLVED = setOf(PLANNED, INTERRUPTED)
}

class ConversationAutoFileJournal(
    private val rootDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dir: File get() = File(rootDir, "journal")

    /** 批次 id 的进程内序号，保证同一毫秒/同一列表长度也不会撞名。 */
    private val sequence = AtomicLong()

    private val mutex: Mutex = lockFor(runCatching { dir.absolutePath }.getOrElse { rootDir.path })

    fun fileFor(assistantId: Uuid): File = File(dir, "$assistantId.json")

    /** 只读检查有没有可撤销批次，给 `observe()` 用，不加锁、不发请求。 */
    fun peekUndoable(assistantId: Uuid): Boolean = readFile(assistantId).batches.lastOrNull()?.isUndoable() == true

    suspend fun latest(assistantId: Uuid): AutoFileJournalBatch? = mutex.withLock {
        readFile(assistantId).batches.lastOrNull()
    }

    /**
     * 历史批次，**最新的在最前**，最多 [limit] 条。
     *
     * 只读本地 JSON，不发请求、不读消息正文；坏日志同样被隔离后当空日志。
     */
    suspend fun history(assistantId: Uuid, limit: Int = MAX_BATCHES): List<AutoFileJournalBatch> = mutex.withLock {
        readFile(assistantId).batches.asReversed().take(limit.coerceIn(1, MAX_BATCHES))
    }

    /** 按 id 取一批（历史详情用）；不存在或不属于该助手时返回 null。 */
    suspend fun batch(assistantId: Uuid, batchId: String): AutoFileJournalBatch? = mutex.withLock {
        readFile(assistantId).batches.firstOrNull { it.id == batchId }
    }

    /**
     * 落盘一批计划。写入成功后调用方才可以去改归属。
     * 同一助手的旧批次保留最近 [MAX_BATCHES] 批。
     */
    suspend fun begin(
        assistantId: Uuid,
        moves: List<AutoFileJournalMove>,
        mode: AutoFileMode = AutoFileMode.AI,
    ): AutoFileJournalBatch =
        mutex.withLock {
            val file = readFile(assistantId)
            val batch = AutoFileJournalBatch(
                // 带上进程内自增序号：只用 now()+列表长度做 id，在日志被裁到上限后会和旧批撞名，
                // 撞名会让 markMove 同时改到别的批次。
                id = "b${now()}-${file.batches.size + 1}-${sequence.incrementAndGet()}",
                assistantId = assistantId.toString(),
                createdAt = now(),
                status = AutoFileBatchStatus.PLANNED,
                mode = mode.name.lowercase(),
                moves = moves,
            )
            val batches = (file.batches + batch).takeLast(MAX_BATCHES)
            writeFile(assistantId, file.copy(batches = batches))
            batch
        }

    suspend fun createFolder(assistantId: Uuid, batchId: String, folderName: String, folderId: Uuid) =
        updateBatch(assistantId, batchId) { batch ->
            val moves = batch.moves.map { move ->
                if (move.folderName == folderName && move.toFolderId == null) {
                    move.copy(toFolderId = folderId.toString())
                } else {
                    move
                }
            }
            batch.copy(moves = moves, createdFolderIds = batch.createdFolderIds + folderId.toString())
        }

    suspend fun markMove(
        assistantId: Uuid,
        batchId: String,
        conversationId: Uuid,
        status: String,
        toFolderId: Uuid? = null,
        updateAtAfter: Long? = null,
        error: String? = null,
    ) = updateBatch(assistantId, batchId) { batch ->
        batch.copy(
            moves = batch.moves.map { move ->
                if (move.conversationId != conversationId.toString()) {
                    move
                } else {
                    move.copy(
                        status = status,
                        toFolderId = toFolderId?.toString() ?: move.toFolderId,
                        updateAtAfter = updateAtAfter ?: move.updateAtAfter,
                        error = error,
                    )
                }
            },
        )
    }

    suspend fun finish(assistantId: Uuid, batchId: String, status: String) =
        updateBatch(assistantId, batchId) { batch ->
            batch.copy(status = status, finishedAt = now())
        }

    suspend fun markUndone(assistantId: Uuid, batchId: String, status: String = AutoFileBatchStatus.UNDONE) =
        updateBatch(assistantId, batchId) { batch ->
            batch.copy(status = status, finishedAt = now())
        }

    /** 进程重启后把还停在 planned 的批次标成 interrupted（不重放，只让撤销认得它）。 */
    suspend fun markInterrupted(assistantId: Uuid): Int = mutex.withLock {
        val file = readFile(assistantId)
        if (file.batches.none { it.status == AutoFileBatchStatus.PLANNED }) return@withLock 0
        var changed = 0
        val batches = file.batches.map { batch ->
            if (batch.status == AutoFileBatchStatus.PLANNED) {
                changed += 1
                batch.copy(status = AutoFileBatchStatus.INTERRUPTED, finishedAt = now())
            } else {
                batch
            }
        }
        writeFile(assistantId, file.copy(batches = batches))
        changed
    }

    /**
     * 把这一批里还没做的项一次性收尾成 skipped（关闭开关提前结束本批时用）。
     * 一次写盘，不留 planned 悬项；没有待收尾项时不写文件。
     */
    suspend fun skipPending(assistantId: Uuid, batchId: String, reason: String): Int = mutex.withLock {
        val file = readFile(assistantId)
        val batch = file.batches.firstOrNull { it.id == batchId } ?: return@withLock 0
        if (batch.moves.none { it.status == AutoFileMoveStatus.PLANNED }) return@withLock 0
        var changed = 0
        val moves = batch.moves.map { move ->
            if (move.status == AutoFileMoveStatus.PLANNED) {
                changed += 1
                move.copy(status = AutoFileMoveStatus.SKIPPED, error = reason)
            } else {
                move
            }
        }
        writeFile(
            assistantId,
            file.copy(batches = file.batches.map { item -> if (item.id == batchId) item.copy(moves = moves) else item }),
        )
        changed
    }

    private suspend fun updateBatch(
        assistantId: Uuid,
        batchId: String,
        transform: (AutoFileJournalBatch) -> AutoFileJournalBatch,
    ) = mutex.withLock {
        val file = readFile(assistantId)
        if (file.batches.none { it.id == batchId }) return@withLock
        writeFile(
            assistantId,
            file.copy(batches = file.batches.map { batch -> if (batch.id == batchId) transform(batch) else batch }),
        )
    }

    private fun readFile(assistantId: Uuid): AutoFileJournalFile {
        val file = fileFor(assistantId)
        val text = AutoFileAtomicFile.readText(file) ?: return AutoFileJournalFile(assistantId = assistantId.toString())
        val parsed = runCatching { json.decodeFromString(AutoFileJournalFile.serializer(), text) }.getOrNull()
        if (parsed == null || parsed.assistantId != assistantId.toString()) {
            // 坏日志/错归属：留档后当空日志，撤销能力丢失但绝不错搬别人的会话。
            AutoFileAtomicFile.quarantine(file, if (parsed == null) "corrupt" else "mismatch")
            return AutoFileJournalFile(assistantId = assistantId.toString())
        }
        return parsed
    }

    private fun writeFile(assistantId: Uuid, file: AutoFileJournalFile) {
        AutoFileAtomicFile.write(
            fileFor(assistantId),
            json.encodeToString(AutoFileJournalFile.serializer(), file),
        )
    }

    internal companion object {
        /** 只留最近若干批：撤销只需要最近一批，留多了只是占空间。 */
        const val MAX_BATCHES = 10

        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(key: String): Mutex =
            locks[key] ?: Mutex().let { created -> locks.putIfAbsent(key, created) ?: created }
    }
}
