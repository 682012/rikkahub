package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 定时任务的开关；真实实现是 WorkManager（见 [ConversationAutoFileWork]），测试里用假实现。 */
interface ConversationAutoFileScheduler {
    /** [enabled] 为 true 时按助手排一个唯一周期任务，为 false 时取消它。 */
    fun schedule(assistantId: Uuid, enabled: Boolean)
}

/**
 * 可关闭的话题归库控制器（Koin 单例）。
 *
 * 对外只暴露契约里的五个方法：[observe] / [updateSettings] / [organizeNow] / [undoLast] / [initialize]，
 * 外加 Worker 用的 [organizeScheduled]。构造参数见同目录 README。
 *
 * 关键约束都在这里执行：
 * - 默认关闭；关闭时**不排任何后台任务**，也不会自动跑分类；手动「立即整理」是一次显式动作，
 *   即使开关是关的也允许跑，但定时任务每批都要重检开关；
 * - 同一助手同一时间只跑一批（批次 [Mutex] + [tryLock]），失败/取消一律释放 running 与锁，
 *   界面能看错并重试；设置写盘用**另一把**顺序锁，快速连点开关不必等分类跑完；
 * - 先写 planned 日志再改归属，逐项回写结果；只通过 [ConversationAutoFileGateway.move]（即
 *   `ChatService.applyAutoFileMove`）提交，不保存整条会话快照；
 * - 一批最多 [ConversationAutoFilePlanner.MAX_ITEMS_PER_RUN] 条模型输入、最多
 *   [ConversationAutoFilePlanner.MAX_MOVES_PER_RUN] 条实际搬迁（同族兄弟跟随代表），
 *   置信度 ≥0.85、新库 ≥3 个**不同家族**、文件夹总数 ≤12，全部由
 *   [ConversationAutoFilePlanner] 把关；
 * - 撤销只动「修完之后用户没再动过」的项，绝不覆盖用户后来聊天或手动移动的结果。
 */
class ConversationAutoFileController(
    storeDir: File,
    private val gateway: ConversationAutoFileGateway,
    private val classifier: ConversationAutoFileClassifier,
    private val scheduler: ConversationAutoFileScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val settingsStore = ConversationAutoFileSettingsStore(storeDir)
    private val journal = ConversationAutoFileJournal(storeDir, now)

    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<ConversationAutoFileState>>()

    /** 整理批次的锁：同一助手同一时间只跑一批。这把锁会被分类/搬迁**长时间**持有。 */
    private val locks = ConcurrentHashMap<Uuid, Mutex>()

    /**
     * 设置写的顺序锁：只管「写盘 → 发布状态 → 排调度」，和批次长锁是两把锁。
     * 用户快速连点开关时按调用顺序落盘；正在跑的批次也不会把开关操作堵在那里。
     */
    private val settingsLocks = ConcurrentHashMap<Uuid, Mutex>()

    /**
     * 进程内设置缓存。写入方只有 [updateSettings]（同进程单例），所以「每提交一条移动前重检开关」
     * 是一次内存查表，不必每条都读盘。缓存未命中时回落到磁盘读取。
     */
    private val settingsCache = ConcurrentHashMap<Uuid, ConversationAutoFileSettings>()

    init {
        // 目录先建好；目录建不出来时后续读写会各自报错，不会静默少文件。
        runCatching { storeDir.mkdirs() }
    }

    /** 界面按助手订阅状态。首次订阅时同步读一次本地设置（小文件，不发请求）。 */
    fun observe(assistantId: Uuid): StateFlow<ConversationAutoFileState> = stateFor(assistantId).asStateFlow()

    /**
     * 保存设置。
     *
     * 顺序是「先写盘成功，再改内存/界面状态」：写盘失败时磁盘、调度、界面都停在旧设置，
     * 只有 [ConversationAutoFileState.error] 说明原因，用户可以立刻重试——不会出现
     * 「界面显示已开启、实际没存下来」的分裂状态。
     */
    suspend fun updateSettings(assistantId: Uuid, settings: ConversationAutoFileSettings) {
        val clamped = settings.clamped()
        val state = stateFor(assistantId)
        settingsLockFor(assistantId).withLock {
            val failure = try {
                settingsStore.write(assistantId, clamped)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                error
            }
            if (failure != null) {
                // 写盘失败：磁盘、调度、界面都留在旧状态，只把原因显示出来，让用户重试。
                state.update { it.copy(error = "设置没能保存：${failure.describe()}") }
            } else {
                settingsCache[assistantId] = clamped
                state.update { it.copy(settings = clamped, error = null) }
                runCatching { scheduler.schedule(assistantId, clamped.enabled) }
                    .onFailure { error ->
                        state.update { it.copy(error = "设置已保存，但定时整理没能安排：${error.describe()}") }
                    }
            }
        }
    }

    /** 用户明确点「立即整理」：即使开关是关的也跑一次，但仍然守 24 小时以上的闲置门槛。 */
    suspend fun organizeNow(assistantId: Uuid) = runBatch(assistantId, manual = true)

    /** 定时任务入口：每轮重新读设置，关掉了就直接不跑（不依赖调度是否及时取消）。 */
    suspend fun organizeScheduled(assistantId: Uuid) = runBatch(assistantId, manual = false)

    suspend fun undoLast(assistantId: Uuid) {
        val state = stateFor(assistantId)
        val mutex = lockFor(assistantId)
        if (!mutex.tryLock()) {
            state.update { it.copy(error = "正在整理中，请稍候") }
            return
        }
        state.update { it.copy(running = true, error = null) }
        try {
            val batch = journal.latest(assistantId)
            if (batch == null || !batch.isUndoable()) {
                state.update { it.copy(running = false, moved = 0, canUndo = false, error = "没有可以撤销的整理") }
                return
            }
            var undone = 0
            var blocked = 0
            batch.moves.forEach { move ->
                currentCoroutineContext().ensureActive()
                val conversationId = parseUuid(move.conversationId) ?: return@forEach
                when (move.status) {
                    AutoFileMoveStatus.APPLIED, AutoFileMoveStatus.PLANNED -> {
                        // 只认**计划时的那一版**：归属变更不动 updateAt，所以「现在还是计划版本」等于
                        // 「搬完之后用户没再聊过」。反过来，绝不能拿搬完之后重新读到的版本当基准
                        // （用户恰好续聊，那个版本会把人误判成可撤），也不能只要求「现在又闲置够久」。
                        val expectedVersion = move.updateAtBefore.takeIf { it > 0L }
                        val targetFolderId = parseUuid(move.toFolderId)
                        if (expectedVersion == null || targetFolderId == null) {
                            blocked += 1
                            return@forEach
                        }
                        val placement = gateway.placement(conversationId)
                        if (placement == null ||
                            placement.folderId != targetFolderId ||
                            placement.updateAt != expectedVersion ||
                            placement.pinned ||
                            placement.locked
                        ) {
                            blocked += 1
                            return@forEach
                        }
                        val restored = gateway.move(
                            assistantId = assistantId,
                            conversationId = conversationId,
                            expectedUpdateAt = expectedVersion,
                            expectedFolderId = targetFolderId,
                            folderId = parseUuid(move.fromFolderId),
                            undo = true,
                        )
                        if (restored) {
                            undone += 1
                            journal.markMove(assistantId, batch.id, conversationId, AutoFileMoveStatus.UNDONE)
                        } else {
                            blocked += 1
                        }
                    }

                    else -> Unit
                }
            }
            journal.markUndone(assistantId, batch.id)
            state.update {
                it.copy(
                    running = false,
                    moved = 0,
                    canUndo = false,
                    error = if (blocked > 0) "有 $blocked 个会话已被改动，未撤销" else null,
                )
            }
        } catch (cancelled: CancellationException) {
            state.update { it.copy(running = false, error = "已取消") }
            throw cancelled
        } catch (error: Throwable) {
            state.update { it.copy(running = false, error = error.describe("撤销失败")) }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 启动时调用：只为已经打开的助手恢复周期任务。**不扫全库、不默认开 AI 分类**。
     */
    fun initialize() {
        val enabled = runCatching { settingsStore.enabledAssistantIds() }.getOrDefault(emptyList())
        enabled.forEach { assistantId ->
            runCatching { scheduler.schedule(assistantId, true) }
            stateFor(assistantId)
        }
    }

    // ---- 内部 ----

    private data class BatchOutcome(
        val moved: Int,
        val canUndo: Boolean,
        val error: String?,
    )

    private fun quietOutcome(assistantId: Uuid) = BatchOutcome(
        moved = 0,
        canUndo = runCatching { journal.peekUndoable(assistantId) }.getOrDefault(false),
        error = null,
    )

    private suspend fun runBatch(assistantId: Uuid, manual: Boolean) {
        val state = stateFor(assistantId)
        val settings = settingsOf(assistantId)
        state.update { it.copy(settings = settings) }
        if (!manual && !settings.enabled) return

        val mutex = lockFor(assistantId)
        if (!mutex.tryLock()) {
            state.update { it.copy(error = "正在整理中，请稍候") }
            return
        }
        state.update { it.copy(running = true, error = null) }
        try {
            val outcome = executeBatch(assistantId, settings, manual)
            state.update {
                it.copy(
                    running = false,
                    moved = outcome.moved,
                    canUndo = outcome.canUndo,
                    error = outcome.error,
                )
            }
        } catch (cancelled: CancellationException) {
            // 取消不是失败：清忙态后原样抛出，不能落到下面的 Throwable 分支被当成普通错误。
            state.update { it.copy(running = false, error = "已取消") }
            throw cancelled
        } catch (error: Throwable) {
            state.update { it.copy(running = false, error = error.describe("整理失败")) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeBatch(
        assistantId: Uuid,
        settings: ConversationAutoFileSettings,
        manual: Boolean,
    ): BatchOutcome {
        // 先保留「上一轮被中断」的事实：它可能已经搬过东西，必须留着撤销记录。
        journal.markInterrupted(assistantId)
        val unresolved = journal.latest(assistantId)?.takeIf { it.isUnresolved() }
        if (unresolved != null) {
            // 不猜、不重发不确定的操作，也不开新批把旧批的撤销记录挤掉。
            return BatchOutcome(
                moved = 0,
                canUndo = runCatching { journal.peekUndoable(assistantId) }.getOrDefault(false),
                error = "上一批整理被中断，请先撤销上一批再继续整理",
            )
        }

        // 定时任务在花钱分类之前再确认一次开关（用户可能刚关掉）。
        if (!manual && !settingsOf(assistantId).enabled) return quietOutcome(assistantId)

        val candidates = gateway.loadCandidates(assistantId)
        val representatives = ConversationAutoFilePlanner.eligible(candidates, settings, now())
        if (representatives.isEmpty()) return quietOutcome(assistantId)

        // 只有家族代表进模型输入（≤16 条），兄弟会话只在采纳后跟随，不增加一次分类的输入量。
        // 摘录用按助手限定的读法：长度有上限，且顺带重检这条会话确实属于这个助手。
        val items = representatives.map { candidate ->
            AutoFileClassifyItem(
                id = candidate.id,
                title = candidate.title,
                excerpt = gateway.loadExcerptForAssistant(
                    assistantId,
                    candidate.id,
                    EXCERPT_HEAD_CHARS,
                    EXCERPT_TAIL_CHARS,
                ),
            )
        }
        val folders = gateway.loadFolders(assistantId)
        val parsed = classifier.classify(assistantId, items, folders)
        if (parsed.error != null) {
            return BatchOutcome(moved = 0, canUndo = runCatching { journal.peekUndoable(assistantId) }.getOrDefault(false), error = parsed.error)
        }
        // 分类期间用户关掉了开关：一条都不动，不建库、不搬会话。
        if (!manual && !settingsOf(assistantId).enabled) return quietOutcome(assistantId)

        // 采纳后的扩项：代表所属家族里所有**仍合格**的兄弟一起搬，避免每 12 小时只搬一个、
        // 把同族的其余散卡留在未归类里。兄弟不额外过模型，门槛与代表一致（且提交时主服务再重检）。
        val members = ConversationAutoFilePlanner.expandFamilyMembers(representatives, candidates, settings, now())
        val expanded = ConversationAutoFilePlanner.expandAssignments(parsed, representatives, members)

        val plan = ConversationAutoFilePlanner.plan(members, folders, expanded)
        if (plan.isEmpty) return quietOutcome(assistantId)

        val updates = members.associate { it.id to it.updateAt }
        // 先把计划落盘：崩溃时最多留一批「写了没做」的记录，绝不会出现搬了却没记录的会话。
        val batch = journal.begin(
            assistantId = assistantId,
            moves = plan.moves.map { move ->
                AutoFileJournalMove(
                    conversationId = move.conversationId.toString(),
                    fromFolderId = null,
                    toFolderId = move.existingFolderId?.toString(),
                    folderName = move.folderName,
                    status = AutoFileMoveStatus.PLANNED,
                    updateAtBefore = updates[move.conversationId] ?: 0L,
                )
            },
        )

        val createdFolders = LinkedHashMap<String, Uuid>()
        val touchedNames = LinkedHashSet<String>()
        var moved = 0
        var failed = 0
        var aborted = false

        for (move in plan.moves) {
            // 取消必须真的停：每条之前检查活跃状态，CancellationException 一路往上抛。
            currentCoroutineContext().ensureActive()
            val expectedUpdateAt = updates[move.conversationId] ?: continue
            if (!aborted && !manual && !settingsOf(assistantId).enabled) aborted = true
            // 关闭之后不再创建文件夹、不再新增移动，剩余项最后一次性标成 skipped。
            if (aborted) continue
            if (!gateway.canAutoFile(move.conversationId)) {
                journal.markMove(
                    assistantId, batch.id, move.conversationId, AutoFileMoveStatus.SKIPPED,
                    error = "会话正在使用中",
                )
                failed += 1
                continue
            }
            var targetFolderId = move.existingFolderId
            if (targetFolderId == null) {
                targetFolderId = createdFolders[move.folderName]
                if (targetFolderId == null) {
                    val created = gateway.createFolder(assistantId, move.folderName)
                    if (created == null) {
                        journal.markMove(
                            assistantId, batch.id, move.conversationId, AutoFileMoveStatus.FAILED,
                            error = "新建文件夹失败",
                        )
                        failed += 1
                        continue
                    }
                    createdFolders[move.folderName] = created
                    journal.createFolder(assistantId, batch.id, move.folderName, created)
                    targetFolderId = created
                }
            }
            val ok = gateway.move(
                assistantId = assistantId,
                conversationId = move.conversationId,
                expectedUpdateAt = expectedUpdateAt,
                expectedFolderId = null,
                folderId = targetFolderId,
                undo = false,
            )
            if (!ok) {
                journal.markMove(
                    assistantId, batch.id, move.conversationId, AutoFileMoveStatus.FAILED,
                    error = "归属没有改变",
                )
                failed += 1
                continue
            }
            moved += 1
            touchedNames += move.folderName
            // 版本用计划时的那一版，不去读搬完之后可能已经被用户续聊改掉的新版本。
            journal.markMove(
                assistantId = assistantId,
                batchId = batch.id,
                conversationId = move.conversationId,
                status = AutoFileMoveStatus.APPLIED,
                toFolderId = targetFolderId,
                updateAtAfter = expectedUpdateAt,
            )
        }

        // 没做的项一次性收尾成 skipped：批已经结束，别在日志里留 planned 让下次当「不确定」。
        journal.skipPending(
            assistantId = assistantId,
            batchId = batch.id,
            reason = if (aborted) "自动归库已关闭" else "没有执行",
        )

        journal.finish(
            assistantId = assistantId,
            batchId = batch.id,
            status = if (failed == 0) AutoFileBatchStatus.DONE else AutoFileBatchStatus.PARTIAL,
        )

        if (moved > 0) {
            runCancellable {
                gateway.postSummary(
                    assistantId,
                    "已自动归入 $moved 个会话：${touchedNames.joinToString("、")}",
                )
            }
        }

        return BatchOutcome(
            moved = moved,
            canUndo = moved > 0,
            error = when {
                aborted -> "自动归库已关闭，本批已停止"
                moved == 0 && failed > 0 -> "这一批没有会话被归入，可以重试"
                else -> null
            },
        )
    }

    /** 与 [runCatching] 相同，但**不吞**取消：取消要继续向上抛，否则 finally 之外的清理会乱。 */
    private suspend fun runCancellable(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Throwable) {
            // 汇总通知失败不影响这一批的结果。
        }
    }

    private fun settingsOf(assistantId: Uuid): ConversationAutoFileSettings =
        settingsCache[assistantId]
            ?: runCatching { settingsStore.read(assistantId) }
                .getOrDefault(ConversationAutoFileSettings.DEFAULT)
                .also { settingsCache[assistantId] = it }

    private fun stateFor(assistantId: Uuid): MutableStateFlow<ConversationAutoFileState> {
        states[assistantId]?.let { return it }
        val created = MutableStateFlow(
            ConversationAutoFileState(
                settings = settingsOf(assistantId),
                canUndo = runCatching { journal.peekUndoable(assistantId) }.getOrDefault(false),
            ),
        )
        return states.putIfAbsent(assistantId, created) ?: created
    }

    private fun lockFor(assistantId: Uuid): Mutex {
        locks[assistantId]?.let { return it }
        val created = Mutex()
        return locks.putIfAbsent(assistantId, created) ?: created
    }

    private fun settingsLockFor(assistantId: Uuid): Mutex {
        settingsLocks[assistantId]?.let { return it }
        val created = Mutex()
        return settingsLocks.putIfAbsent(assistantId, created) ?: created
    }

    private fun parseUuid(raw: String?): Uuid? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Uuid.parse(value) }.getOrNull()
    }

    private fun Throwable.describe(fallback: String = "未知错误"): String =
        message?.take(160)?.takeIf { it.isNotBlank() } ?: fallback

    private companion object {
        const val EXCERPT_HEAD_CHARS = 400
        const val EXCERPT_TAIL_CHARS = 400
    }
}
