package me.rerere.rikkahub.data.organization.autofile

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 定时任务的开关；真实实现是 WorkManager（见 [ConversationAutoFileWork]），测试里用假实现。 */
interface ConversationAutoFileScheduler {
    /**
     * [enabled] 为 true 时按助手排一个唯一周期任务，为 false 时取消它。
     *
     * [mode] 决定任务约束：AI 模式需要联网（[AutoFileMode.AI]），本地规则模式完全离线，不要求网络。
     * 默认值让 r21 的调用方/假实现不改也能编译，但新实现要按模式选约束。
     */
    fun schedule(assistantId: Uuid, enabled: Boolean, mode: AutoFileMode = AutoFileMode.AI)

    /**
     * 「立即整理」：排一个**一次性**后台任务（进程/页面关掉也会跑完），并返回真实入队的标识。
     *
     * 与周期任务是两个不同名字的唯一任务，同一助手的批次锁保证不会同时搬库；
     * 同名任务已在队列里时不会叠第二个，返回的 `enqueued=false`。
     */
    suspend fun scheduleManual(assistantId: Uuid): AutoFileManualWork =
        AutoFileManualPolicy.manualWork(assistantId, workId = null, state = "UNSUPPORTED", enqueued = false)

    /** 手动任务的唯一名字（不给实现时用默认命名）。 */
    fun manualWorkName(assistantId: Uuid): String = AutoFileManualPolicy.manualWorkName(assistantId)

    /** 撤掉还没开始跑的手动任务（正在跑的批次由 Controller 自己 cancelRun）。 */
    fun cancelManual(assistantId: Uuid) {}
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
    /**
     * 本地规则模式的分类器。默认就是完全本地的确定性实现（不发网、不花额度），
     * 所以接线处不需要任何额外改动就能用上「本地模式」。
     */
    private val localClassifier: ConversationAutoFileClassifier = ConversationAutoFileLocalClassifier(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val settingsStore = ConversationAutoFileSettingsStore(storeDir)
    private val journal = ConversationAutoFileJournal(storeDir, now)

    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<ConversationAutoFileState>>()

    /**
     * 批次体的**可取消**执行容器。
     *
     * 后台 Worker / 前台手动整理进来的那一批不是直接跑在调用方的 coroutine 里，而是包成一个子任务：
     * 用户关掉开关、或调用 [cancelRun] 时把这个子任务 cancel()，正在进行的模型请求/文件读写就会收到
     * CancellationException 立刻停下；调用方（Worker）自己不会被连带取消，仍能正常返回结果。
     */
    private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在跑的批次：关掉开关时取消它们，保证「关闭 = 停止在途可取消任务」。 */
    private val activeRuns = ConcurrentHashMap<Uuid, Job>()

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
                // 关闭：先取消后续调度，再叫停在途批次；两步都不依赖界面是否还活着。
                if (!clamped.enabled) cancelRun(assistantId)
                runCatching { scheduler.schedule(assistantId, clamped.enabled, clamped.mode) }
                    .onFailure { error ->
                        state.update { it.copy(error = "设置已保存，但定时整理没能安排：${error.describe()}") }
                    }
            }
        }
    }

    /** 用户明确点「立即整理」：即使开关是关的也跑一次，但仍然守 24 小时以上的闲置门槛。 */
    suspend fun organizeNow(assistantId: Uuid) = runBatch(assistantId, manual = true)

    /**
     * 界面/自动化点「立即整理」时用这个：把整理**排到后台一次性任务**里，不挂在当前页面的
     * coroutine 上——面板关掉、Compose 作用域被取消都不会连带取消整理。
     *
     * 返回 WorkManager 里的真实回执：[AutoFileManualWork.workId] 是查到的那个任务 id，
     * 同一个助手已有任务在排队/运行时不会重复入队（`enqueued=false`）。排不上时写进
     * [ConversationAutoFileState.error] 并如实返回，不假装成功。
     */
    suspend fun enqueueManualRun(assistantId: Uuid): AutoFileManualWork {
        val state = stateFor(assistantId)
        state.update { it.copy(settings = settingsOf(assistantId)) }
        val workName = scheduler.manualWorkName(assistantId)
        if (activeRuns[assistantId]?.isActive == true) {
            // 这一批已经在真跑（多半是刚被 Worker 拉起来），不用再排一个。
            return AutoFileManualWork(workName = workName, state = "RUNNING", enqueued = false)
        }
        return try {
            scheduler.scheduleManual(assistantId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.update { it.copy(error = "没能排上后台整理：${error.describe()}") }
            AutoFileManualWork(workName = workName, state = "FAILED", enqueued = false)
        }
    }

    /** 定时任务入口：每轮重新读设置，关掉了就直接不跑（不依赖调度是否及时取消）。 */
    suspend fun organizeScheduled(assistantId: Uuid) = runBatch(assistantId, manual = false)

    /**
     * 叫停正在跑的这一批（关掉开关时自动调用；自动化/MCP 也能显式调）。
     *
     * 取消是真的取消：批次体在独立子任务里跑，模型请求和后续搬迁都会收到 CancellationException。
     * 已经落盘的计划批会被标成 interrupted，撤销入口照样留着，不会变成「搬了却没记录」。
     * 返回是否真的有在跑的批次被叫停。
     */
    fun cancelRun(assistantId: Uuid): Boolean {
        // 还没开始跑的手动任务也要撤掉：否则「取消」之后它到点照样开工，等于没取消。
        runCatching { scheduler.cancelManual(assistantId) }
        val running = activeRuns[assistantId] ?: return false
        running.cancel(CancellationException("自动整理已被取消"))
        return true
    }

    /** 历史批次，最新的在最前。只读本地日志（不含正文），界面/自动化都用它。 */
    suspend fun history(
        assistantId: Uuid,
        limit: Int = ConversationAutoFileJournal.MAX_BATCHES,
    ): List<AutoFileJournalBatch> = runCatching { journal.history(assistantId, limit) }.getOrDefault(emptyList())

    /**
     * 某一批的详情：逐条会话 + 目标库 + 理由 + 状态。
     *
     * 标题是唯一需要查库的信息，用 [ConversationAutoFileGateway.loadConversationTitles] 一次轻量读；
     * 读失败/会话已删除时留空，界面退回显示 id，详情本身照样能给。
     */
    suspend fun details(assistantId: Uuid, batchId: String): AutoFileBatchDetail? {
        val batch = journal.batch(assistantId, batchId) ?: return null
        val ids = batch.moves.mapNotNull { parseUuid(it.conversationId) }
        val titles = if (ids.isEmpty()) {
            emptyMap()
        } else {
            runCatching { gateway.loadConversationTitles(assistantId, ids) }.getOrDefault(emptyMap())
        }
        return AutoFileBatchDetail(batch = batch, titles = titles)
    }

    /** 当前状态快照（和 [observe] 同一份数据），给自动化/MCP 用来一次性读状态。 */
    fun status(assistantId: Uuid): ConversationAutoFileState = stateFor(assistantId).value

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
        enabledAssistantIds().forEach { assistantId ->
            val settings = settingsOf(assistantId)
            runCatching { scheduler.schedule(assistantId, true, settings.mode) }
            stateFor(assistantId)
        }
    }

    /** 已开启自动归档的助手 id（只读本地设置文件），给启动恢复和对外门面用。 */
    fun enabledAssistantIds(): List<Uuid> =
        runCatching { settingsStore.enabledAssistantIds() }.getOrDefault(emptyList())

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
            // 重入保护：前台手动、前台「立即整理」、另一次 Worker 都走这把锁。
            // 手动撞车时明确提示；后台批次撞车时静默退出（不覆盖前台正在显示的状态，
            // 也不会出现两条并发路径同时改库）。
            if (manual) state.update { it.copy(error = "正在整理中，请稍候") }
            return
        }
        state.update { it.copy(running = true, error = null) }
        // 批次体放在独立子任务里跑：关闭开关 / cancelRun 能真正打断在途请求，
        // 而调用方（Worker）自己的 coroutine 不受影响，仍能正常收尾返回 Result。
        val run = runScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            executeBatch(assistantId, settings, manual)
        }
        // 先登记再 start：LAZY 保证登记完成之前它不会跑，取消时不会漏掉一个已在飞的子任务。
        activeRuns[assistantId] = run
        run.start()

        var outcome: BatchOutcome? = null
        var failure: Throwable? = null
        var cancelledByCaller: CancellationException? = null
        try {
            outcome = run.await()
        } catch (cancelled: CancellationException) {
            // 取消有两种来源，处理必须分开：
            // - 子任务被 cancelRun 叫停（调用方还活着）→ 记成「已取消」，不向上抛；
            // - 调用方自己（页面离开 / Worker 被杀）被取消 → 必须继续向上抛，不能吞成普通错误。
            cancelledByCaller = if (runCatching { currentCoroutineContext().ensureActive() }.isFailure) cancelled else null
        } catch (error: Throwable) {
            failure = error
        } finally {
            // 关键：子任务跑在 controller 自己的作用域里，**调用方取消不会连带取消它**。
            // 必须 cancel + join，等它真的停下来再放锁；否则旧批次继续搬库，而锁已经释放，
            // 同一个助手就会两批并跑。清理按 job 身份做，绝不覆盖后来那批的登记/状态。
            withContext(NonCancellable) {
                run.cancel()
                run.join()
                val mine = activeRuns.remove(assistantId, run)
                val done = outcome
                val cancelledState = cancelledByCaller
                val failed = failure
                if (mine) {
                    when {
                        done != null -> state.update {
                            it.copy(
                                running = false,
                                moved = done.moved,
                                canUndo = done.canUndo,
                                error = done.error,
                            )
                        }

                        cancelledState != null -> {
                            // 停在 planned 的计划批标成 interrupted：撤销入口留着，下次开新批前也会挡住。
                            runCatching { journal.markInterrupted(assistantId) }
                            state.update {
                                it.copy(
                                    running = false,
                                    canUndo = runCatching { journal.peekUndoable(assistantId) }.getOrDefault(false),
                                    error = "已取消",
                                )
                            }
                        }

                        else -> state.update {
                            it.copy(running = false, error = (failed ?: IllegalStateException()).describe("整理失败"))
                        }
                    }
                }
                mutex.unlock()
            }
        }
        // 调用方被取消：等子任务停稳、锁放掉之后再原样抛，别让 Worker 把「已取消」当成功。
        cancelledByCaller?.let { throw it }
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
        // 按设置里选定的模式分流：AI 模式沿用 r21 的快速模型分类器（外发限长摘要、花额度）；
        // 本地模式只跑确定性规则，不碰网络，也不会退化成 AI。
        val active = when (settings.mode) {
            AutoFileMode.LOCAL -> localClassifier
            AutoFileMode.AI -> classifier
        }
        val parsed = active.classify(assistantId, items, folders)
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
                    reason = move.reason,
                )
            },
            mode = settings.mode,
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
