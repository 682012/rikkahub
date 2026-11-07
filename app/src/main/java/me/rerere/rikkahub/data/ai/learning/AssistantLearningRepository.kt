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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 用户自己写的一条经验，落盘格式。 */
@Serializable
internal data class UserGuidanceRecord(
    val id: String,
    val text: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 每助手一个 JSON 文件的固定 schema。
 *
 * 只有指标、开关和用户经验；没有对话正文、工具参数、提示词或凭据。
 * 字段顺序固定（序列化按声明顺序），[version] 用于以后升级。
 */
@Serializable
internal data class LearningFile(
    val version: Int = LearningLimits.SCHEMA_VERSION,
    val assistantId: String = "",
    val revision: Long = 0L,
    val enabled: Boolean = true,
    /** 每轮经验总结（额外一次模型请求，会计费）。默认关闭，必须用户显式打开。 */
    val experienceReflection: Boolean = false,
    val updatedAt: Long = 0L,
    val turns: List<LearningTurn> = emptyList(),
    val guidances: List<UserGuidanceRecord> = emptyList(),
    val disabledAutoGuidanceIds: Set<String> = emptySet(),
)

/** JSON 损坏时只抢救这些字段：开关状态和版本不应该跟着坏数据一起丢。 */
@Serializable
internal data class LearningFileHeader(
    val version: Int = LearningLimits.SCHEMA_VERSION,
    val assistantId: String = "",
    val revision: Long = 0L,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
)

/**
 * 按助手保存的「经验库」：指标、自动经验（算出来的）和用户经验（存下来的）。
 *
 * 契约：
 * - 一个助手一个文件 `rootDir/<assistantId>.json`，UUID 独立，互不影响，也不进数据库、不做迁移；
 * - 写入顺序：临时文件 → fsync → 原子改名。失败抛 [LearningStorageException]，原文件保持不动；
 * - 同一进程里多个实例共用一把**按路径**的 [Mutex]，写之前会重读磁盘，不会拿旧快照覆盖别人的新改动；
 * - 读到的 JSON 坏了：先整份复制成 `<file>.corrupt-<时刻>` 留底，再按能救的字段重建，
 *   所以既能恢复，也不会在没有备份时覆盖掉用户的原始文件；
 * - 内存里只缓存少量（[LearningLimits.MAX_CACHED_ASSISTANTS] 个）已解析文件，其余随用随读、用完淘汰；
 *   任何接口都不返回「所有助手的历史」；
 * - 关闭（[setEnabled] false）之后不再收集（[recordTurn] 直接不写盘），也不再注入
 *   （[buildPrompt] 返回空串）；已经存下的数据保留，等用户重新打开或 [clear]；
 * - 所有 IO 都在 [ioDispatcher] 上，UI 侧可以放心调用。
 */
class AssistantLearningRepository(
    private val rootDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 变更通知：全局只递增一个版本号，订阅方自己按自己的助手 id 取快照，
     * 所以不会把每个助手的完整历史常驻内存，也不会因为别的助手在写而被挤掉事件。
     */

    /** 最近解析过的少量文件，key 是绝对路径；访问顺序淘汰。 */
    private val cache = object : LinkedHashMap<String, CachedFile>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFile>): Boolean =
            size > LearningLimits.MAX_CACHED_ASSISTANTS
    }
    private val cacheGuard = Any()

    private data class CachedFile(
        val file: LearningFile,
        val stamp: String?,
        val revision: Long,
        val present: Boolean,
        val backup: String?,
    )

    private data class Loaded(
        val file: LearningFile,
        val path: String,
        val present: Boolean,
        val backup: String?,
    )

    /** 该助手的 json 文件。id 只允许字母、数字、`-`、`_`，避免路径穿越和奇怪文件名。 */
    fun fileFor(assistantId: String): File {
        val id = assistantId.trim()
        require(
            id.isNotEmpty() &&
                id.length <= LearningLimits.MAX_ID_CHARS &&
                id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        ) {
            "助手 id 只能包含字母、数字、- 和 _，最长 ${LearningLimits.MAX_ID_CHARS} 个字符"
        }
        return File(rootDir, "$id.json")
    }

    /**
     * 订阅一个助手的状态：先给当前快照，之后每次变化再给一份新快照。
     * 没有变化不会重复发（[distinctUntilChanged]）。
     */
    fun observe(assistantId: String): Flow<LearningState> =
        changes.map { snapshot(assistantId) }.distinctUntilChanged()

    /** 读一次快照（含按助手整体指标算出的自动经验）。 */
    suspend fun snapshot(assistantId: String): LearningState = withContext(ioDispatcher) {
        val file = fileFor(assistantId)
        lockFor(file).withLock { stateOf(assistantId, loadLocked(assistantId, file)) }
    }

    /** 打开/关闭自我学习。关闭后不再收集也不再注入，已存数据保留。 */
    suspend fun setEnabled(assistantId: String, enabled: Boolean): LearningState =
        mutate(assistantId) { data, _ -> if (data.enabled == enabled) null else data.copy(enabled = enabled) }

    /**
     * 打开/关闭「每轮让模型总结经验」。
     *
     * 关闭时不动任何已写下的经验；打开前界面必须明确告诉用户这会多花一次模型请求（会计费）。
     */
    suspend fun setExperienceReflection(assistantId: String, enabled: Boolean): LearningState =
        mutate(assistantId) { data, _ ->
            if (data.experienceReflection == enabled) null else data.copy(experienceReflection = enabled)
        }

    /** 经验学习的生效开关：本助手的自我学习总开关 + 显式的经验总结开关。 */
    suspend fun experienceSettings(assistantId: String): ExperienceSettings =
        experienceSettingsOf(snapshot(assistantId))

    /**
     * 每轮终态的经验入口，由 DI（ExperienceHook）接到 [ExperienceService.onTurnFinished]。
     *
     * 这里是唯一接缝：没接线时为 null，指标照常记录，只是不做经验总结。
     */
    @Volatile
    var experience: ((TurnFacts) -> Unit)? = null

    /**
     * 记录一轮生成的指标。
     *
     * - 关闭时直接返回，不写盘（`revision` 不变）；
     * - 同一个 messageId 再次记录只更新指标，不把重连或重复投递当成重新生成；
     *   用户反馈必须由 markRegenerated 显式记录；
     * - 只保留最近 [LearningLimits.MAX_TURNS] 轮，最旧的丢掉。
     */
    suspend fun recordTurn(
        assistantId: String,
        turn: LearningTurn,
        facts: TurnFacts? = null,
    ): LearningState {
        val state = mutate(assistantId) { data, now ->
            require(turn.assistantId.isBlank() || turn.assistantId == assistantId) { "Learning metrics belong to another assistant" }
            if (!data.enabled) return@mutate null
            data.withTurn(LearningAnalyzer.sanitizeTurn(turn, assistantId, now), now)
        }
        // 只有调用方给了终态 facts（成功/终止，取消与等待审批为 null）才触发一次经验总结。
        // 触发本身不阻塞：ExperienceService.onTurnFinished 立刻返回 Job，失败在那边可见。
        if (facts != null) {
            runCatching { experience?.invoke(facts) }
                .onFailure { android.util.Log.w("AssistantLearning", "Could not start experience reflection", it) }
        }
        return state
    }

    /** Only an explicit regenerate button action is a feedback signal, never duplicate delivery. */
    suspend fun markRegenerated(assistantId: String, messageId: String): LearningState =
        mutate(assistantId) { data, _ ->
            if (!data.enabled) return@mutate null
            if (data.turns.none { it.messageId == messageId }) return@mutate null
            data.copy(turns = data.turns.map { if (it.messageId == messageId)
                it.copy(regenerations = (it.regenerations + 1).coerceAtMost(1000)) else it })
        }

    /**
     * 新增或编辑一条用户经验。[id] 为空是新增，否则编辑同 id 的条目。
     *
     * 条数上限 [LearningLimits.MAX_USER_GUIDANCE]，超了抛 [LearningLimitException]，
     * 不会悄悄挤掉用户已有的条目。
     */
    suspend fun upsertGuidance(
        assistantId: String,
        text: String,
        id: String? = null,
        enabled: Boolean = true,
    ): LearningState = mutate(assistantId) { data, now ->
        val body = LearningAnalyzer.sanitizeGuidanceText(text)
        require(body.isNotEmpty()) { "经验内容不能为空" }
        val targetId = id?.takeIf { it.isNotBlank() }?.let { LearningAnalyzer.sanitizeId(it) }
            ?: "user-" + UUID.randomUUID().toString()
        val records = data.guidances.toMutableList()
        val index = records.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            val current = records[index]
            if (current.text == body && current.enabled == enabled) {
                null
            } else {
                records[index] = current.copy(text = body, enabled = enabled, updatedAt = now)
                data.copy(guidances = records.toList())
            }
        } else {
            if (records.size >= LearningLimits.MAX_USER_GUIDANCE) {
                throw LearningLimitException(
                    "用户经验最多 ${LearningLimits.MAX_USER_GUIDANCE} 条，先删掉一条再加"
                )
            }
            records += UserGuidanceRecord(
                id = targetId,
                text = body,
                enabled = enabled,
                createdAt = now,
                updatedAt = now,
            )
            data.copy(guidances = records.toList())
        }
    }

    /** 启用/停用一条用户经验（停用只是不注入，内容还在）。 */
    suspend fun setGuidanceEnabled(assistantId: String, id: String, enabled: Boolean): LearningState =
        mutate(assistantId) { data, now ->
            val target = LearningAnalyzer.sanitizeId(id) ?: return@mutate null
            if (target.startsWith("auto:")) {
                // 已经是这个状态就不写盘：一次全文件重写不值得为一个重复点击发生
                val alreadyDisabled = target in data.disabledAutoGuidanceIds
                return@mutate if (alreadyDisabled == !enabled) null else data.copy(
                    disabledAutoGuidanceIds = if (enabled) {
                        data.disabledAutoGuidanceIds - target
                    } else {
                        (data.disabledAutoGuidanceIds + target).take(24).toSet()
                    }
                )
            }
            val index = data.guidances.indexOfFirst { it.id == target }
            if (index < 0 || data.guidances[index].enabled == enabled) {
                null
            } else {
                val records = data.guidances.toMutableList()
                records[index] = records[index].copy(enabled = enabled, updatedAt = now)
                data.copy(guidances = records.toList())
            }
        }

    /** 删除一条用户经验；不存在时什么都不改。 */
    suspend fun deleteGuidance(assistantId: String, id: String): LearningState =
        mutate(assistantId) { data, _ ->
            val target = LearningAnalyzer.sanitizeId(id) ?: return@mutate null
            if (target.startsWith("auto:")) {
                // 自动条目没有落盘内容，删除等于停用；已经停用就什么都不用改
                if (target in data.disabledAutoGuidanceIds) return@mutate null
                return@mutate data.copy(
                    disabledAutoGuidanceIds = (data.disabledAutoGuidanceIds + target).take(24).toSet()
                )
            }
            if (data.guidances.none { it.id == target }) {
                null
            } else {
                data.copy(guidances = data.guidances.filterNot { it.id == target })
            }
        }

    /** 清空指标和/或用户经验。开关状态保留；删除后不可恢复（文件里直接不写这些字段）。 */
    suspend fun clear(
        assistantId: String,
        scope: LearningClearScope = LearningClearScope.ALL,
    ): LearningState = mutate(assistantId) { data, _ ->
        val turns = if (scope == LearningClearScope.GUIDANCE) data.turns else emptyList()
        val guidances = if (scope == LearningClearScope.TURNS) data.guidances else emptyList()
        if (turns == data.turns && guidances == data.guidances) {
            null
        } else {
            data.copy(turns = turns, guidances = guidances)
        }
    }

    /**
     * 按当前状态生成注入文本。纯函数（不读盘），UI 可以直接用 [snapshot] 的结果调它。
     * 返回空串表示这次不注入。
     */
    fun buildPrompt(state: LearningState, modelId: String? = null, now: Long = clock()): String =
        LearningPromptBuilder.buildPrompt(state, modelId, now)

    // ---- 内部：读改写都在「按路径的锁」里做，写完才更新缓存和通知 ----

    private suspend fun mutate(
        assistantId: String,
        block: (LearningFile, Long) -> LearningFile?,
    ): LearningState {
        var result: LearningState? = null
        var changed = false
        withContext(ioDispatcher) {
            val file = fileFor(assistantId)
            lockFor(file).withLock {
                val loaded = loadLocked(assistantId, file)
                val now = clock()
                val proposed = block(loaded.file, now)
                if (proposed == null) {
                    result = stateOf(assistantId, loaded)
                    return@withLock
                }
                val next = proposed.copy(
                    assistantId = assistantId,
                    revision = loaded.file.revision + 1,
                    updatedAt = now,
                )
                commitLocked(file, next, loaded.backup)
                changed = true
                result = stateOf(assistantId, loaded.copy(file = next, present = true))
            }
        }
        if (changed) changes.update { it + 1L }
        return result ?: snapshot(assistantId)
    }

    private fun stateOf(assistantId: String, loaded: Loaded): LearningState {
        val data = loaded.file
        val now = clock()
        return LearningState(
            assistantId = assistantId,
            enabled = data.enabled,
            experienceReflection = data.experienceReflection,
            revision = data.revision,
            updatedAt = data.updatedAt,
            turns = data.turns,
            autoGuidances = LearningAnalyzer.analyze(data.turns, modelId = null, now = now)
                .map { it.copy(enabled = it.id !in data.disabledAutoGuidanceIds) },
            disabledAutoGuidanceIds = data.disabledAutoGuidanceIds,
            userGuidances = data.guidances.map { it.toGuidance() },
            stats = LearningAnalyzer.stats(data.turns),
            storage = LearningStorageInfo(path = loaded.path, present = loaded.present, corruptBackup = loaded.backup),
        )
    }

    private fun UserGuidanceRecord.toGuidance(): LearningGuidance = LearningGuidance(
        id = id,
        text = text,
        kind = GuidanceKind.USER,
        enabled = enabled,
        evidenceTurns = 0,
        updatedAt = updatedAt,
    )

    /** 追加一轮：同 messageId 视为重新生成，其余按 FIFO 截到上限。 */
    private fun LearningFile.withTurn(turn: LearningTurn, now: Long): LearningFile? {
        val turns = this.turns.toMutableList()
        val existing = if (turn.messageId == null) -1 else turns.indexOfLast { it.messageId == turn.messageId }
        val merged = if (existing >= 0) {
            val previous = turns.removeAt(existing)
            turn.copy(regenerations = maxOf(previous.regenerations, turn.regenerations))
        } else {
            turn
        }
        turns += merged
        val trimmed = if (turns.size > LearningLimits.MAX_TURNS) {
            turns.subList(turns.size - LearningLimits.MAX_TURNS, turns.size).toList()
        } else {
            turns.toList()
        }
        if (trimmed == this.turns) return null
        return copy(turns = trimmed, updatedAt = now)
    }

    /**
     * 取一份可信的最新状态：在按路径的锁里总是重读磁盘（不做「内存命中就不读」），
     * 所以别的实例或别的进程写过之后也不会拿旧内容当基础；内存缓存只记 present 和备份路径。
     */
    private fun loadLocked(assistantId: String, file: File): Loaded {
        val key = file.absolutePath
        val stamp = diskStamp(file)
        val shared = sharedRevisions[key] ?: 0L
        val cached = synchronized(cacheGuard) { cache[key] }
        if (!file.isFile) {
            val fresh = LearningFile(assistantId = assistantId)
            store(key, fresh, stamp, shared, present = false, backup = null)
            return Loaded(fresh, key, present = false, backup = null)
        }
        val length = file.length()
        if (length > LearningLimits.MAX_FILE_BYTES) {
            // 超过文件上限按损坏处理：先整份留底，再按「读不到开关」重建为关闭。
            // 不把超大文件读进内存，也不会把用户原文件直接覆盖掉。
            val backup = backupCorrupt(file)
            val rebuilt = LearningFile(
                assistantId = assistantId,
                revision = 1L,
                enabled = false,
                updatedAt = clock(),
            )
            commitLocked(file, rebuilt, backup)
            return Loaded(rebuilt, key, present = true, backup = backup)
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            throw LearningStorageException("助手经验文件读不出来：$key", e)
        }
        val decoded = runCatching { json.decodeFromString(LearningFile.serializer(), text) }
        decoded.getOrNull()?.let { parsed ->
            if (parsed.version != LearningLimits.SCHEMA_VERSION || parsed.assistantId != assistantId) {
                throw LearningStorageException("Unsupported schema or assistant ownership mismatch: $key")
            }
            val normalized = parsed.normalize(assistantId, clock())
            store(key, normalized, stamp, shared, present = true, backup = cached?.backup)
            return Loaded(normalized, key, present = true, backup = cached?.backup)
        }
        // 损坏：先整份留底，再按还能认出来的字段重建。没有备份成功就抛错，绝不覆盖原文件。
        val backup = backupCorrupt(file)
        val salvaged = runCatching { json.decodeFromString(LearningFileHeader.serializer(), text) }.getOrNull()
        val rebuilt = LearningFile(
            assistantId = assistantId,
            revision = (salvaged?.revision ?: 0L) + 1,
            enabled = salvaged?.enabled ?: false,
            updatedAt = clock(),
        )
        commitLocked(file, rebuilt, backup)
        return Loaded(rebuilt, key, present = true, backup = backup)
    }

    /** 读进来的数据也按当前上限洗一遍：文件可能被手改过，或者来自更老的版本。 */
    private fun LearningFile.normalize(assistantId: String, now: Long): LearningFile = copy(
        version = LearningLimits.SCHEMA_VERSION,
        assistantId = assistantId,
        revision = revision.coerceAtLeast(0L),
        turns = turns.takeLast(LearningLimits.MAX_TURNS).map {
            LearningAnalyzer.sanitizeTurn(it, assistantId, now)
        },
        guidances = guidances.takeLast(LearningLimits.MAX_USER_GUIDANCE).mapNotNull { record ->
            val text = LearningAnalyzer.sanitizeGuidanceText(record.text)
            if (text.isEmpty()) null else record.copy(text = text)
        },
    )

    private fun store(
        key: String,
        file: LearningFile,
        stamp: String?,
        revision: Long,
        present: Boolean,
        backup: String?,
    ) {
        synchronized(cacheGuard) {
            cache[key] = CachedFile(file, stamp, revision, present, backup)
        }
    }

    /** 临时文件 → fsync → 原子改名。失败抛 [LearningStorageException]，磁盘上的旧文件不受影响。 */
    private fun commitLocked(file: File, next: LearningFile, backup: String?) {
        val parent = file.parentFile ?: throw LearningStorageException("没有可写的目录：${file.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw LearningStorageException("创建目录失败：${parent.absolutePath}")
        }
        val temp = File(parent, file.name + "." + UUID.randomUUID().toString() + ".tmp")
        try {
            val bytes = json.encodeToString(LearningFile.serializer(), next).toByteArray(Charsets.UTF_8)
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replaceFile(temp, file)) {
                throw IOException("临时文件改名失败：${file.absolutePath}")
            }
        } catch (e: Exception) {
            runCatching { temp.delete() }
            throw LearningStorageException("助手经验没能写入：${file.absolutePath}", e)
        }
        val key = file.absolutePath
        val revision = (sharedRevisions[key] ?: 0L) + 1
        sharedRevisions[key] = revision
        store(key, next, diskStamp(file), revision, present = true, backup = backup)
    }

    /** rename 不支持覆盖时再试一次；仍失败就报错，不退回「直接覆盖」。 */
    private fun replaceFile(temp: File, target: File): Boolean = runCatching {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    /** 把损坏的原文件整份复制留底，返回备份路径；复制失败就抛错（此时还没有任何覆盖）。 */
    private fun backupCorrupt(file: File): String {
        val target = File(file.parentFile, file.name + ".corrupt-" + clock() + "-" + UUID.randomUUID())
        try {
            file.copyTo(target, overwrite = false)
        } catch (e: Exception) {
            throw LearningStorageException(
                "助手经验文件损坏，备份失败，未做任何覆盖：${file.absolutePath}",
                e,
            )
        }
        return target.absolutePath
    }

    private fun diskStamp(file: File): String? = runCatching {
        if (file.isFile) file.length().toString() + ":" + file.lastModified() else null
    }.getOrNull()

    private companion object {
        /** 同一路径的读改写串行化；DI 出来的实例和测试/页面重建出来的实例共用同一把锁 */
        val sharedLocks = Array(64) { Mutex() }
        val changes = MutableStateFlow(0L)
        val sharedRevisions = ConcurrentHashMap<String, Long>()

        fun lockFor(file: File): Mutex = sharedLocks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % sharedLocks.size]
    }
}
