package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.mediagen.model.MediaKind
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationSession
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.organization.MediaOutputDeleteRules
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 媒体创作的会话、生成记录以及它们在磁盘上的文件。
 *
 * 时间线由「项」组成，每一项下面是一条或多条记录（版本），其中一条被选中显示。
 *
 * 文件按归属存放：`media_creation/{会话}/{记录}/` 下是一条记录自己的输入和输出，`media_creation/{会话}/draft/`
 * 下是草稿里还没有提交的素材，从相册选进来的和选用已有产出时复制过来的都在这里。删除记录或会话时连同目录一起删除。
 */
class MediaCreationRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: MediaCreationDAO,
    private val json: Json,
) {
    private val activeStatuses = MediaCreationStatus.ACTIVE.map { it.name }

    // ---- 文件 ----

    fun resolve(path: String): File = File(context.filesDir, path)

    fun relativePath(file: File): String = file.relativeTo(context.filesDir).path

    fun sessionDir(sessionId: Uuid): File = File(context.filesDir, "${FileFolders.MEDIA_CREATION}/$sessionId")

    fun recordDir(sessionId: Uuid, recordId: Uuid): File = File(sessionDir(sessionId), recordId.toString())

    fun draftDir(sessionId: Uuid): File = File(sessionDir(sessionId), DRAFT_DIR)

    // ---- 会话 ----

    fun observeSessions(): Flow<List<MediaCreationSession>> =
        dao.observeSessions(activeStatuses, MediaCreationStatus.SUCCEEDED.name).map { sessions ->
            sessions.map {
                it.session.toSession().copy(
                    nodeCount = it.nodeCount,
                    activeCount = it.activeCount,
                    cover = it.coverOutputs
                        ?.let { outputs -> decodeOrDefault<List<MediaCreationOutput>>(outputs, emptyList()) }
                        ?.firstOrNull(),
                )
            }
        }

    fun observeSession(id: Uuid): Flow<MediaCreationSession?> =
        dao.observeSession(id.toString()).map { it?.toSession() }

    suspend fun getSession(id: Uuid): MediaCreationSession? = dao.getSession(id.toString())?.toSession()

    suspend fun getLatestSession(): MediaCreationSession? = dao.getLatestSession()?.toSession()

    /**
     * 返回一个还没有任何记录的会话：已经有空会话时直接复用，避免攒下一堆空会话。
     */
    suspend fun getOrCreateEmptySession(draft: MediaCreationDraft): MediaCreationSession = database.withTransaction {
        dao.getEmptySession()?.toSession() ?: MediaCreationSession(draft = draft).also {
            dao.insertSession(it.toEntity())
        }
    }

    /** 准备一个空会话，模型沿用最近那个会话选中的。 */
    suspend fun newSession(): MediaCreationSession =
        getOrCreateEmptySession(MediaCreationDraft(modelId = getLatestSession()?.draft?.modelId))

    suspend fun renameSession(id: Uuid, title: String) = dao.renameSession(id.toString(), title)

    suspend fun saveDraft(id: Uuid, draft: MediaCreationDraft) =
        dao.updateDraft(id.toString(), json.encodeToString(draft))

    suspend fun updateSession(id: Uuid, transform: (MediaCreationSession) -> MediaCreationSession) {
        database.withTransaction {
            val session = dao.getSession(id.toString())?.toSession() ?: return@withTransaction
            dao.updateSession(transform(session).toEntity())
        }
    }

    suspend fun deleteSession(id: Uuid) {
        dao.deleteSession(id.toString())
        withContext(Dispatchers.IO) { sessionDir(id).deleteRecursively() }
    }

    // ---- 时间线 ----

    /**
     * 会话的时间线，最新的一项排在最前。开启占位后第 0 项始终是最新的那一项，界面可以可靠地滚回底部。
     */
    fun observeNodes(sessionId: Uuid): Flow<PagingData<MediaCreationNode>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true)) {
            dao.nodePagingSource(sessionId.toString())
        }.flow.map { page ->
            page.map { MediaCreationNode(it.record.toRecord(), it.versionIndex, it.versionCount) }
        }

    /** 一项在 [observeNodes] 里的位置。 */
    suspend fun getNodePosition(id: Uuid): Int = dao.getNodePosition(id.toString())

    suspend fun hasNode(id: Uuid): Boolean = dao.getNode(id.toString()) != null

    /** 时间线上每一项当前显示的版本，从最早的一项排起。 */
    suspend fun getSelectedRecordsOfSession(sessionId: Uuid): List<MediaCreationRecord> =
        dao.getSelectedRecordsOfSession(sessionId.toString()).map { it.toRecord() }

    /** 一项的所有版本，按创建顺序。 */
    suspend fun getVersionIds(nodeId: Uuid): List<Uuid> = dao.getVersionIds(nodeId.toString()).map { Uuid.parse(it) }

    /** 从时间线上删除一项，连同它的所有版本和文件。 */
    suspend fun deleteNode(sessionId: Uuid, nodeId: Uuid) {
        // 版本随这一项级联删除，目录要自己清理
        val versions = getVersionIds(nodeId)
        dao.deleteNode(nodeId.toString())
        withContext(Dispatchers.IO) { versions.forEach { recordDir(sessionId, it).deleteRecursively() } }
    }

    /**
     * 让一项改为显示相邻的版本，[offset] 为 -1 是上一个、1 是下一个；已经到头时不变。
     */
    suspend fun switchVersion(nodeId: Uuid, offset: Int) = database.withTransaction {
        val node = dao.getNode(nodeId.toString()) ?: return@withTransaction
        val versions = dao.getVersionIds(node.id)
        val target = versions.getOrNull(versions.indexOf(node.selectedRecordId) + offset) ?: return@withTransaction
        dao.selectRecord(node.id, target)
    }

    // ---- 记录 ----

    suspend fun getRecord(id: Uuid): MediaCreationRecord? = dao.getRecord(id.toString())?.toRecord()

    suspend fun getRecordsOfSession(sessionId: Uuid): List<MediaCreationRecord> =
        dao.getRecordsOfSession(sessionId.toString()).map { it.toRecord() }

    suspend fun getActiveRecords(): List<MediaCreationRecord> =
        dao.getRecordsByStatus(activeStatuses).map { it.toRecord() }

    fun observeRecentSucceededRecords(limit: Int): Flow<List<MediaCreationRecord>> =
        dao.observeRecentRecords(MediaCreationStatus.SUCCEEDED.name, limit).map { records ->
            records.map { it.toRecord() }
        }

    suspend fun getLatestRecordOfKind(kind: MediaKind): MediaCreationRecord? =
        dao.getLatestRecordOfKind(kind.name)?.toRecord()

    /**
     * 把 [record] 写进时间线并选中它：[MediaCreationRecord.nodeId] 对应的项已经存在时成为它的新版本，
     * 否则在时间线末尾另起一项。
     */
    suspend fun insertRecord(record: MediaCreationRecord) = database.withTransaction {
        val entity = record.toEntity()
        if (dao.getNode(entity.nodeId) == null) {
            dao.insertNode(
                MediaCreationNodeEntity(
                    id = entity.nodeId,
                    sessionId = entity.sessionId,
                    selectedRecordId = entity.id,
                    createAt = entity.createAt,
                )
            )
            dao.insertRecord(entity)
        } else {
            dao.insertRecord(entity)
            dao.selectRecord(entity.nodeId, entity.id)
        }
    }

    /**
     * 原子地修改一条记录，返回修改后的记录；记录已被删除时返回 null。
     */
    suspend fun updateRecord(
        id: Uuid,
        transform: (MediaCreationRecord) -> MediaCreationRecord,
    ): MediaCreationRecord? = database.withTransaction {
        val current = dao.getRecord(id.toString())?.toRecord() ?: return@withTransaction null
        val updated = transform(current)
        if (updated != current) dao.updateRecord(updated.copy(updateAt = Instant.now()).toEntity())
        updated
    }

    /**
     * 删除一个版本。它是所在项的最后一个版本时这一项也随之消失；删掉的是正在显示的版本时，
     * 改为显示它前面的那个，没有的话显示后面的。
     */
    suspend fun deleteRecord(record: MediaCreationRecord) {
        database.withTransaction {
            val id = record.id.toString()
            val nodeId = record.nodeId.toString()
            val versions = dao.getVersionIds(nodeId)
            dao.deleteRecord(id)
            val node = dao.getNode(nodeId) ?: return@withTransaction
            val remaining = versions - id
            when {
                remaining.isEmpty() -> dao.deleteNode(nodeId)
                node.selectedRecordId == id ->
                    dao.selectRecord(nodeId, remaining[(versions.indexOf(id) - 1).coerceIn(remaining.indices)])
            }
        }
        withContext(Dispatchers.IO) { recordDir(record.sessionId, record.id).deleteRecursively() }
    }

    // ---- 单张产出图 ----

    /**
     * 删掉一条记录里的**一张**产出图。不碰记录本身、不碰输入素材、不碰其他产出、不动时间线位置和版本序号。
     *
     * 和 [deleteRecord] / [deleteNode] 的区别很重要：那两个会把记录目录 `deleteRecursively()`，
     * 连用户的输入素材一起删掉，语义完全不同，所以这里绝不调用它们。
     *
     * 流程：
     * 1. 按 [relativePath] 找列出它的记录（SQL 粗筛 + 解码后按 path 精确匹配），要求记录不是 ACTIVE；
     * 2. 目标文件 canonical 之后必须正好在该记录目录里，且是真文件（不跟 symlink、不接受绝对路径/网络地址）；
     * 3. 确认没有别的记录把它当输入素材、没有别的产出（含视频封面/尾帧）或会话草稿引用它；
     * 4. 同目录 rename 到临时名做隔离 → 事务里再确认一次（记录非 ACTIVE、outputs 里还有这一条、引用仍然为空）
     *    → 从 outputs 里摘掉这一条 → 提交成功后 unlink 临时文件。
     *    事务失败就把临时文件 rename 回原路径，磁盘和数据库都保持原样。
     *
     * 返回 [MediaOutputRemoval]；`removed == false` 时 [MediaOutputRemoval.message] 可以直接显示给用户。
     */
    suspend fun removeImageOutput(relativePath: String): MediaOutputRemoval = withContext(Dispatchers.IO) {
        val path = relativePath.trim()
        if (path.isEmpty()) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.UNSAFE_PATH,
                message = "没有给出要删除的产出路径",
            )
        }

        val matches = dao.getRecordsMentioningOutput(path)
            .map { it.toRecord() }
            .filter { record -> record.outputs.any { it.path == path } }
        if (matches.isEmpty()) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.NOT_IN_RECORD,
                message = "没有记录还列着这张图，未做改动",
            )
        }
        if (matches.size > 1) {
            // 正常不会发生（每条记录有自己的目录），真遇到就不猜
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.AMBIGUOUS,
                message = "有多条记录都列着这张图，未做改动",
            )
        }

        val owner = matches.single()
        val output = owner.outputs.first { it.path == path }
        if (!output.mimeType.startsWith("image/") || output.posterPath != null || output.lastFramePath != null) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.NOT_IMAGE,
                message = "这条产出不是一张能单独删的图（不是图片，或还带着封面/尾帧）",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
        }
        if (owner.status.isActive) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.RECORD_ACTIVE,
                message = "这条记录还在生成中，等它完成后再删",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
        }

        val target = when (val check = MediaOutputDeleteRules.resolveTarget(context.filesDir, recordDir(owner.sessionId, owner.id), path)) {
            is MediaOutputDeleteRules.TargetCheck.Rejected -> return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.UNSAFE_PATH,
                message = check.message,
                recordId = owner.id,
                sessionId = owner.sessionId,
            )

            is MediaOutputDeleteRules.TargetCheck.Ok -> check.target
        }

        // 先查一次引用：被引用时没必要动文件；事务里还会再确认一次（防止这中间又有人引用）
        val references = referencesTo(path, target.canonicalPath, owner.id)
        if (references.isNotEmpty()) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.STILL_REFERENCED,
                message = "这张图还被${references.joinToString("、")}引用，已跳过",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
        }

        // 同目录改名做隔离：rename 不搬数据，失败说明目录不可写，此时数据库还没动
        val parentDir = target.file.parentFile
        if (parentDir == null) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.UNSAFE_PATH,
                message = "找不到文件所在目录，已拒绝删除",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
        }
        val keep = File(parentDir, "${target.file.name}.${Uuid.random()}.deleting")
        if (!target.file.renameTo(keep)) {
            return@withContext MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.ISOLATION_FAILED,
                message = "无法把文件挪到临时位置，未改动记录",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
        }

        var failure: MediaOutputRemoval? = null
        val committed = try {
            database.withTransaction {
                val current = dao.getRecord(owner.id.toString())?.toRecord()
                val remaining = current?.let { MediaOutputDeleteRules.withoutOutput(it.outputs, path) }
                val stillReferenced = referencesTo(path, target.canonicalPath, owner.id)
                val problem = when {
                    current == null -> MediaOutputRemoval(
                        removed = false,
                        reason = MediaOutputRemovalReason.NOT_IN_RECORD,
                        message = "记录已经不存在了，未改动记录",
                    )

                    current.status.isActive -> MediaOutputRemoval(
                        removed = false,
                        reason = MediaOutputRemovalReason.RECORD_ACTIVE,
                        message = "这条记录又开始生成了，未改动记录",
                    )

                    remaining == null -> MediaOutputRemoval(
                        removed = false,
                        reason = MediaOutputRemovalReason.NOT_IN_RECORD,
                        message = "记录里已经没有这张图了，未改动记录",
                    )

                    stillReferenced.isNotEmpty() -> MediaOutputRemoval(
                        removed = false,
                        reason = MediaOutputRemovalReason.STILL_REFERENCED,
                        message = "这张图刚被${stillReferenced.joinToString("、")}引用，已跳过",
                    )

                    else -> null
                }
                if (problem != null) {
                    failure = problem
                    return@withTransaction false
                }
                // 只动 outputs 这一项：记录 id / nodeId / 状态 / taskId / 其他产出 / 输入素材都原样写回
                dao.updateRecord(
                    current!!.copy(outputs = remaining!!, updateAt = Instant.now()).toEntity(),
                )
                true
            }
        } catch (e: CancellationException) {
            // 页面被关掉 / 协程被取消：文件必须还原回去，再把取消原样抛出去
            restoreIsolatedFile(keep, target.file)
            throw e
        } catch (e: Exception) {
            failure = MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.DB_UPDATE_FAILED,
                message = "数据库更新失败：${e.message ?: "未知原因"}",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
            false
        }

        if (!committed) {
            val restored = restoreIsolatedFile(keep, target.file)
            val base = failure ?: MediaOutputRemoval(
                removed = false,
                reason = MediaOutputRemovalReason.DB_UPDATE_FAILED,
                message = "数据库没有更新成功",
                recordId = owner.id,
                sessionId = owner.sessionId,
            )
            return@withContext if (restored) {
                base.copy(message = "${base.message}；文件已还原")
            } else {
                base.copy(
                    message = "${base.message}；临时文件没能还原，现在在 ${keep.relativeTo(context.filesDir).path}，需要手动清理",
                    diskCleanupPending = true,
                )
            }
        }

        // 到这里 outputs 里已经没有这一条了，磁盘上的隔离文件才是最后的残渣
        val cleaned = runCatching { keep.delete() }.getOrDefault(false)
        MediaOutputRemoval(
            removed = true,
            reason = null,
            message = if (cleaned) {
                "已删除这张产出图"
            } else {
                "记录里已经移除这张图，磁盘上没删掉（${keep.relativeTo(context.filesDir).path}），留给下次清理"
            },
            recordId = owner.id,
            sessionId = owner.sessionId,
            diskCleanupPending = !cleaned,
        )
    }

    /**
     * 把隔离中的临时文件改回原路径；成功返回 true。
     *
     * 只有「原路径还是空的」才改回去，避免覆盖这中间又被写出来的同名文件。
     * 纯文件操作、不 suspend，所以协程被取消时也能执行。
     */
    private fun restoreIsolatedFile(keep: File, original: File): Boolean =
        runCatching { !original.exists() && keep.renameTo(original) }.getOrDefault(false)

    /**
     * 还有谁指着 [canonicalPath]：别的记录的输入素材、别的产出（含视频封面/尾帧）、会话草稿里的素材。
     *
     * [ownerRecordId] 自己那一条 `path == [path]` 的产出不算引用（它就是待删的那个），其余全算。
     * 只读数据库里的 JSON 字段，不扫磁盘、不扫相册。
     */
    private suspend fun referencesTo(
        path: String,
        canonicalPath: String,
        ownerRecordId: Uuid,
    ): List<String> {
        val filesDir = context.filesDir
        val found = linkedSetOf<String>()

        fun check(raw: String?, label: () -> String) {
            if (raw != null && MediaOutputDeleteRules.sameFile(filesDir, raw, canonicalPath)) found += label()
        }

        dao.getRecordsMentioningReference(path).map { it.toRecord() }.forEach { record ->
            val who = record.id.toString().take(8)
            record.inputs.forEach { asset -> check(asset.path) { "记录 $who 的输入素材" } }
            record.outputs.forEach { other ->
                if (record.id == ownerRecordId && other.path == path) return@forEach
                check(other.path) { "记录 $who 的产出" }
                check(other.posterPath) { "记录 $who 的封面" }
                check(other.lastFramePath) { "记录 $who 的尾帧" }
            }
        }

        dao.getDraftsMentioning(path).forEach { draft ->
            decodeOrDefault<MediaCreationDraft>(draft, MediaCreationDraft()).assets.forEach { asset ->
                check(asset.path) { "创建页的草稿素材" }
            }
        }

        return found.toList()
    }

    // ---- 映射 ----

    private fun MediaCreationSessionEntity.toSession() = MediaCreationSession(
        id = Uuid.parse(id),
        title = title,
        draft = decodeOrDefault(draft, MediaCreationDraft()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationSession.toEntity() = MediaCreationSessionEntity(
        id = id.toString(),
        title = title,
        draft = json.encodeToString(draft),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private fun MediaCreationRecordEntity.toRecord() = MediaCreationRecord(
        id = Uuid.parse(id),
        sessionId = Uuid.parse(sessionId),
        nodeId = Uuid.parse(nodeId),
        providerId = Uuid.parse(providerId),
        providerName = providerName,
        modelId = modelId,
        kind = MediaKind.valueOf(kind),
        prompt = prompt,
        params = decodeOrDefault(params, MediaCreationParams()),
        inputs = decodeOrDefault(inputs, emptyList()),
        status = MediaCreationStatus.valueOf(status),
        taskId = taskId,
        error = error,
        outputs = decodeOrDefault(outputs, emptyList()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationRecord.toEntity() = MediaCreationRecordEntity(
        id = id.toString(),
        sessionId = sessionId.toString(),
        nodeId = nodeId.toString(),
        providerId = providerId.toString(),
        providerName = providerName,
        modelId = modelId,
        kind = kind.name,
        prompt = prompt,
        params = json.encodeToString(params),
        inputs = json.encodeToString(inputs),
        status = status.name,
        taskId = taskId,
        error = error,
        outputs = json.encodeToString(outputs),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private inline fun <reified T> decodeOrDefault(text: String, default: T): T =
        runCatching { json.decodeFromString<T>(text) }.getOrDefault(default)

    companion object {
        private const val DRAFT_DIR = "draft"
        private const val PAGE_SIZE = 20
    }
}

/**
 * [MediaCreationRepository.removeImageOutput] 的结果。
 *
 * [removed] 为 true 表示记录里的 outputs 已经不含这条产出（这时 [message] 只是给人看的说明）；
 * 为 false 表示什么都没改（或已还原），[reason] 是机器可判定的原因，[message] 可以直接显示。
 */
data class MediaOutputRemoval(
    val removed: Boolean,
    val reason: MediaOutputRemovalReason? = null,
    val message: String = "",
    val recordId: Uuid? = null,
    val sessionId: Uuid? = null,
    /** 记录里已经移除这一条，但磁盘上的临时文件没删掉：不影响记录，留给下次清理。 */
    val diskCleanupPending: Boolean = false,
)

enum class MediaOutputRemovalReason {
    /** 没有任何记录还列着这个路径。 */
    NOT_IN_RECORD,

    /** 有多条记录都列着同一个路径，不敢猜。 */
    AMBIGUOUS,

    /** 所属记录还在生成中（PREPARING / QUEUED / RUNNING / DOWNLOADING）。 */
    RECORD_ACTIVE,

    /** 路径或文件不安全：绝对路径、越界、symlink、不是普通文件。 */
    UNSAFE_PATH,

    /** 还被别的记录或会话草稿引用。 */
    STILL_REFERENCED,

    /** 不是图片，或带着封面 / 尾帧这类附属文件。 */
    NOT_IMAGE,

    /** 同目录改临时名失败，什么都没改。 */
    ISOLATION_FAILED,

    /** 数据库更新失败，磁盘已经还原。 */
    DB_UPDATE_FAILED,
}
