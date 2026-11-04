package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * 已经处在一次「持屏障的后台执行」里。
 *
 * 这个上下文元素的唯一作用是让**嵌套**的工具调用（tool_batch 的成员、工具内部再调工具）
 * 不要再各自起任务：外层已经持有串行屏障，再抢一次会和自己的父调用互相等待。
 */
internal class BackgroundBarrierHeld : AbstractCoroutineContextElement(BackgroundBarrierHeld) {
    companion object Key : CoroutineContext.Key<BackgroundBarrierHeld>
}

/**
 * 通用工具转后台的执行器。
 *
 * 三件必须一起成立的事：
 *
 * 1. **同一份执行**：前台等待超时只放弃「等」，不取消也不重跑。工具体在 manager 自己的
 *    `SupervisorJob` scope 里跑，所以即使整轮生成被停掉，已经转后台的调用照样跑完。
 * 2. **真串行屏障**：写/未知工具（`SERIAL`）按会话排队。前一个转后台还没跑完时，后一个
 *    `SERIAL` 调用会先 `await` 它的闸门；`PARALLEL`/只读工具不受影响，照常和其它工作重叠。
 *    没有这道闸，"转后台"就等于让模型把两条写命令同时踩到同一批文件上。
 * 3. **可查、可续、绝不重放**：元数据 + 正文落盘，重启后 `RUNNING` 一律标 `INTERRUPTED`
 *    （效果未知），不会自动再执行一次。
 *
 * 归属只按会话（[BackgroundToolOwner]）。结果正文既可以按偏移分页读，也在同一进程内保留
 * 附件（图片/文档等非文本 part），重启后只剩文本。
 */
class BackgroundToolManager(
    private val rootDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val lease: BackgroundForegroundLease = BackgroundForegroundLease.None,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单条正文的落盘上限（超出的部分标 `truncated`，不静默丢）。单测可以调小来验证边界。 */
    private val maxPersistedChars: Int = BACKGROUND_MAX_RESULT_CHARS,
) {
    /**
     * 任务结束提醒的投递口：由 ChatService 接到会话收件箱（见 BACKGROUND-WIRING.md）。
     * 没接线时什么都不发（提醒只是附加信息，任务本身与 result/wait 读取不受影响）。
     */
    @Volatile
    var noticeSink: ((BackgroundToolNotice) -> Unit)? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val tasks = ConcurrentHashMap<String, BackgroundToolTask>()

    /** 同进程内保留的完整输出（含附件）。落盘只有文本。 */
    private val outputs = ConcurrentHashMap<String, List<UIMessagePart>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 已经转后台的任务（原子 claim 凭据，保证"只转一次"）。 */
    private val backgrounded = ConcurrentHashMap.newKeySet<String>()

    /**
     * 需要落盘的任务 id：可能带副作用的调用（`SERIAL`）和已经转后台的调用。
     *
     * 普通只读调用不进这个集合，也就不会为「读一次文件」写一份 `task.json`——那属于白费 IO，
     * 而且它们在前台就把结果交付了，本来也没有什么"崩溃后效果未知"要保护。
     */
    private val crashTracked = ConcurrentHashMap.newKeySet<String>()

    /** 已经发过完成提醒的任务。 */
    private val notified = ConcurrentHashMap.newKeySet<String>()
    private val leased = ConcurrentHashMap.newKeySet<String>()

    /**
     * 每个 owner 的串行闸门。
     *
     * 用 Mutex，不用「链式 CompletableDeferred」：排队者被取消（用户停止生成）时 Mutex 只是把它从
     * 队列里摘掉，闸门不会提前打开；链式闸门会在 `finally` 里 complete 掉自己的闸门，而第三个写
     * 等的正是那个闸门——它会和第一个写并发。Mutex 本身是 FIFO 的，排队顺序照旧。
     */
    private val serialGates = ConcurrentHashMap<String, Mutex>()
    private val persistenceLock = Any()
    private val executionStateLock = Any()
    private val restored = AtomicBoolean(false)

    // ------------------------------------------------------------------ 公开读

    /** 本会话的任务，最新在前，最多 [BACKGROUND_MAX_TASKS_PER_OWNER] 条。 */
    fun list(owner: BackgroundToolOwner): List<BackgroundToolTask> {
        ensureRestored()
        val key = owner.conversationId?.toString()
        return tasks.values
            .filter { it.conversationId == key }
            .sortedByDescending { it.createdAt }
            .take(BACKGROUND_MAX_TASKS_PER_OWNER)
    }

    /** 按 id 取任务：内存优先，缺失时懒读这一条 `task.json`；别的会话的 id 一律当不存在。 */
    fun get(id: String, owner: BackgroundToolOwner): BackgroundToolTask? {
        if (!isBackgroundTaskId(id)) return null
        ensureRestored()
        val key = owner.conversationId?.toString()
        tasks[id]?.let { task -> if (task.conversationId == key) return task }
        val restoredTask = readTaskFile(dirOf(key, id)) ?: return null
        if (restoredTask.conversationId != key) return null
        tasks.putIfAbsent(id, restoredTask)
        // 从磁盘读回来的记录当然要跟着继续落盘（取消/改状态后重启还能看到）
        crashTracked.add(id)
        return tasks[id] ?: restoredTask
    }

    /**
     * 分页读一次结果：只读一页，页大小上限 [BACKGROUND_RESULT_MAX_LIMIT]。
     *
     * 坐标是**留存正文**的字符偏移（[BackgroundToolTask.storedChars]）。正文被截断时
     * `page.task.truncated` 为 true、`task.resultChars` 是真实全文长度，分页头会说明缺口。
     * 页数据是流式读的（文件最多读一页大小），不会为了读一页把整份正文读进内存。
     */
    fun readResult(
        id: String,
        owner: BackgroundToolOwner,
        offset: Int = 0,
        limit: Int = BACKGROUND_RESULT_DEFAULT_LIMIT,
    ): BackgroundToolResultPage? {
        val task = get(id, owner) ?: return null
        val total = storedLength(task)
        val safeLimit = if (limit <= 0) BACKGROUND_RESULT_DEFAULT_LIMIT else limit.coerceAtMost(BACKGROUND_RESULT_MAX_LIMIT)
        val (start, text) = pageSlice(task, total, offset, safeLimit)
        val end = start + text.length
        return BackgroundToolResultPage(
            task = task,
            offset = start,
            limit = safeLimit,
            totalChars = total,
            text = text,
            endOffset = end,
            eof = end >= total,
            attachments = if (start == 0) attachmentsOf(task) else emptyList(),
        )
    }

    /**
     * 留存正文（最多 [BackgroundToolTask.storedChars] 个字符，内存优先、其次结果文件）。
     *
     * 和分页同一套坐标：正文被截断时这里也只返回留存的那一段，不会嘴上说 2M、
     * 实际只给 32 个字符却报告成完整。
     */
    fun resultText(task: BackgroundToolTask): String {
        val total = storedLength(task)
        if (total <= 0) return ""
        return readStoredText(task, 0, total)
    }

    /** 同进程内保留的非文本附件；重启后为空（正文仍可读，`hasAttachments` 仍是 true）。 */
    fun attachmentsOf(task: BackgroundToolTask): List<UIMessagePart> =
        outputs[task.id].orEmpty().filter { it !is UIMessagePart.Text }

    /** 轮询等任务结束（不 await 执行协程，所以永远不会把取消传染给工具体）。 */
    suspend fun waitFor(
        id: String,
        owner: BackgroundToolOwner,
        timeoutMillis: Long,
    ): BackgroundToolTask? {
        val deadline = clock() + timeoutMillis.coerceAtLeast(0L)
        var task = get(id, owner) ?: return null
        while (!task.isFinished && clock() < deadline) {
            delay(BACKGROUND_WAIT_POLL_MS)
            task = get(id, owner) ?: return null
        }
        return task
    }

    /**
     * 取消一条后台任务。
     *
     * 有活的执行协程时取消会**继续向下传播**（工具体真的被 cancel，状态落 CANCELLED）；
     * 进程重启后的孤儿记录没有执行者，只能标成 `INTERRUPTED`，绝不再执行一次。
     */
    fun cancel(id: String, owner: BackgroundToolOwner): Boolean {
        val task = get(id, owner) ?: return false
        if (task.isFinished) return false
        val job = jobs[id]
        if (job != null && !job.isCompleted) {
            job.cancel(CancellationException("background tool cancelled by request"))
            return true
        }
        finishTask(
            id = id,
            status = BackgroundToolStatus.INTERRUPTED,
            text = "",
            error = "interrupted: no live execution for this task; effects are unknown and it was NOT replayed",
        )
        return true
    }

    // ------------------------------------------------------------- 前台调用入口

    /**
     * 前台调用一个工具：先等 [waitMillis]，超时后转后台并返回回执。
     *
     * 同一个工具体只会执行一次；前台取消（用户停止生成）在**还没转后台**时取消真实执行，
     * 已转后台的调用继续跑（结果靠 result/wait 读）。
     */
    suspend fun execute(
        tool: Tool,
        arguments: JsonElement,
        owner: BackgroundToolOwner,
        waitMillis: Long,
    ): List<UIMessagePart> {
        val run = begin(tool, arguments, owner, explicit = false)
        val waited = try {
            withTimeoutOrNull(waitMillis.coerceAtLeast(1L)) { run.completion.await() }
        } catch (e: CancellationException) {
            if (run.task.id !in backgrounded) {
                run.job.cancel(CancellationException("foreground tool caller cancelled"))
            }
            throw e
        }
        if (waited != null) return waited.getOrThrow()
        // 恰好在这时跑完：直接给真结果，不要发一条「去后台读」的回执让模型再跑一趟
        if (isFinished(run.task.id)) return run.completion.await().getOrThrow()
        if (!enterBackground(run.task.id, owner.conversationId)) return run.completion.await().getOrThrow()
        return listOf(UIMessagePart.Text(backgroundReceipt(run.task.id)))
    }

    /** 显式后台启动：立刻返回任务记录，不等待。 */
    fun startDetached(
        tool: Tool,
        arguments: JsonElement,
        owner: BackgroundToolOwner,
        explicit: Boolean = true,
    ): BackgroundToolTask = begin(tool, arguments, owner, explicit).task

    // ------------------------------------------------------------------ 执行内部

    private class BackgroundRun(
        val task: BackgroundToolTask,
        val completion: CompletableDeferred<Result<List<UIMessagePart>>>,
        val job: Job,
    )

    private fun begin(
        tool: Tool,
        arguments: JsonElement,
        owner: BackgroundToolOwner,
        explicit: Boolean,
    ): BackgroundRun {
        ensureRestored()
        val id = Uuid.random().toString()
        val record = BackgroundToolTask(
            id = id,
            toolName = tool.name,
            conversationId = owner.conversationId?.toString(),
            arguments = arguments.toString().take(BACKGROUND_MAX_ARGUMENT_CHARS),
            status = BackgroundToolStatus.RUNNING,
            explicit = explicit,
            backgrounded = explicit,
            createdAt = clock(),
        )
        tasks[id] = record
        if (explicit) backgrounded.add(id)
        // 崩溃恢复记录只给「可能有副作用」的调用写：普通只读调用每次读都落一盘是白费 IO，
        // 它们真要转后台时 enterBackground 会把它加进来补写。
        if (explicit || tool.executionMode == ToolExecutionMode.SERIAL) {
            crashTracked.add(id)
            persist(record)
        }
        pruneOwner(owner)
        pruneGlobal()
        val completion = CompletableDeferred<Result<List<UIMessagePart>>>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runWorker(id, tool, arguments, owner, completion)
        }
        jobs[id] = job
        job.invokeOnCompletion { cause ->
            // Cancellation can happen before the coroutine body starts (no try/finally then).
            if (cause != null && !completion.isCompleted) {
                finishTask(id, BackgroundToolStatus.CANCELLED, "", "cancelled before execution started")
                completion.complete(Result.failure(cause))
                jobs.remove(id)
                releaseLease(id)
                notifyFinished(id)
            }
        }
        job.start()
        if (explicit) {
            acquireLease(id, owner.conversationId)
            if (isFinished(id)) {
                releaseLease(id)
                notifyFinished(id)
            }
        }
        return BackgroundRun(record, completion, job)
    }

    private suspend fun runWorker(
        id: String,
        tool: Tool,
        arguments: JsonElement,
        owner: BackgroundToolOwner,
        completion: CompletableDeferred<Result<List<UIMessagePart>>>,
    ) {
        try {
            val output = withContext(BackgroundBarrierHeld()) {
                val body: suspend () -> List<UIMessagePart> = { tool.execute(arguments) }
                if (tool.executionMode == ToolExecutionMode.SERIAL) {
                    serialized(owner.key, body)
                } else {
                    body()
                }
            }.ifEmpty { listOf(UIMessagePart.Text(BACKGROUND_EMPTY_OUTPUT)) }
            synchronized(executionStateLock) {
                retainOutput(id, output)
                finishTask(id, BackgroundToolStatus.COMPLETED, textOf(output), null)
            }
            completion.complete(Result.success(output))
        } catch (e: CancellationException) {
            finishTask(id, BackgroundToolStatus.CANCELLED, textOf(outputs[id].orEmpty()), "cancelled")
            completion.complete(Result.failure(e))
            throw e
        } catch (e: Throwable) {
            finishTask(id, BackgroundToolStatus.FAILED, textOf(outputs[id].orEmpty()), describe(e))
            completion.complete(Result.failure(e))
        } finally {
            jobs.remove(id)
            releaseLease(id)
            notifyFinished(id)
        }
    }

    /**
     * 串行闸门：同一个 owner 的 `SERIAL` 调用严格首尾相接。
     *
     * 后手在真正开始前拿闸门——前手可能已经转后台还在跑，这正是要挡的情况。
     * 排队者被取消时 Mutex 只是把它从队列里摘掉（不会像链式闸门那样在 `finally` 里提前开闸），
     * 所以「取消第二条写」绝不会让第三条写和第一条并发。Mutex 是 FIFO 的，顺序照旧。
     */
    private suspend fun <T> serialized(ownerKey: String, block: suspend () -> T): T {
        val gate = serialGates.computeIfAbsent(ownerKey) { Mutex() }
        return gate.withLock { block() }
    }

    /**
     * 内存里的完整 parts（含附件）只留给**已经脱离前台**的任务。
     *
     * 普通调用在前台就把输出交给调用方了，再在 manager 里留一份（可能是几 MB 正文或图片）纯属
     * 内存增长；正文需要时从 result.txt 分页读，`bodyRetained` 说了它还留不留。
     */
    private fun retainOutput(id: String, parts: List<UIMessagePart>) {
        val task = tasks[id] ?: return
        if (!task.isDetached) return
        val textOnly = parts.all { it is UIMessagePart.Text }
        if (textOnly && textOf(parts).length > BACKGROUND_MAX_MEMORY_RESULT_CHARS) return
        outputs[id] = parts
    }

    private fun enterBackground(id: String, conversationId: Uuid?): Boolean {
        val accepted = synchronized(executionStateLock) {
            val current = tasks[id]
            if (current == null || current.isFinished) false else {
                backgrounded.add(id)
                crashTracked.add(id)
                updateTask(id) { it.copy(backgrounded = true) }
                true
            }
        }
        if (!accepted) return false
        acquireLease(id, conversationId)
        if (isFinished(id)) {
            releaseLease(id)
            notifyFinished(id)
        }
        return true
    }

    private fun acquireLease(id: String, conversationId: Uuid?) {
        if (conversationId == null) return
        val taskId = runCatching { Uuid.parse(id) }.getOrNull() ?: return
        if (!leased.add(id)) return
        runCatching { lease.acquire(taskId, conversationId) }
    }

    private fun releaseLease(id: String) {
        if (!leased.remove(id)) return
        val taskId = runCatching { Uuid.parse(id) }.getOrNull() ?: return
        runCatching { lease.release(taskId) }
    }

    // ------------------------------------------------------------------ 状态记录

    private fun isFinished(id: String): Boolean = tasks[id]?.isFinished == true

    private fun updateTask(
        id: String,
        transform: (BackgroundToolTask) -> BackgroundToolTask,
    ): BackgroundToolTask? {
        val updated = tasks.computeIfPresent(id) { _, old -> transform(old) } ?: return null
        persist(updated)
        return updated
    }

    /**
     * 收尾：状态落终态、正文按「是否已脱离前台」决定要不要留。
     *
     * 落盘失败**不影响**状态与前台输出：`persistenceError` 记下原因，运行状态照实写成 COMPLETED/
     * FAILED，绝不因为写盘失败把一次成功的调用报成失败或假完成。
     */
    private fun finishTask(
        id: String,
        status: BackgroundToolStatus,
        text: String,
        error: String?,
    ) = synchronized(executionStateLock) {
        val old = tasks[id] ?: return@synchronized
        if (old.isFinished) return@synchronized
        val retained = old.isDetached
        val stored = if (retained) text.take(maxPersistedChars) else ""
        val updated = old.copy(
            status = status,
            finishedAt = clock(),
            resultChars = text.length,
            storedChars = stored.length,
            // 截断必须是可见的：resultChars 是全文长度，storedChars 才是能读到的部分
            truncated = retained && stored.length < text.length,
            bodyRetained = retained,
            error = error,
            hasAttachments = outputs[id].orEmpty().any { it !is UIMessagePart.Text },
        )
        // Publish a terminal state only after its retained body is readable.
        if (retained) writeResult(updated, stored)
        tasks[id] = updated.copy(persistenceError = tasks[id]?.persistenceError ?: updated.persistenceError)
        if (crashTracked.remove(id)) persist(tasks[id] ?: updated)
        if (!retained) outputs.remove(id)
    }

    private fun notifyFinished(id: String) {
        val task = tasks[id] ?: return
        // 只有已经脱离前台调用者的任务才提醒：前台等到结果的调用不打扰用户
        if (!task.isFinished || !task.isDetached) return
        // 正文没留（前台已交付）时不要再发「去 action=result 读」——那条提醒是假的
        if (!task.bodyRetained) return
        val conversationId = task.conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return
        val sink = noticeSink ?: return
        if (!notified.add(id)) return
        runCatching { sink(backgroundNotice(task, conversationId)) }
            .onFailure { notified.remove(id) }
    }

    private fun backgroundNotice(task: BackgroundToolTask, conversationId: Uuid): BackgroundToolNotice {
        // 只读前面一小段做预览，不为了提醒把整份正文读进内存
        val preview = readStoredText(task, 0, BACKGROUND_NOTICE_PREVIEW_CHARS).trim()
        val text = buildString {
            append(task.toolName)
            append(" (").append(task.id).append(") ")
            append(task.status.name.lowercase())
            append(", ").append(task.resultChars).append(" chars")
            if (task.truncated) append(" (only ").append(task.storedChars).append(" readable)")
            task.error?.let { append(": ").append(it) }
            append(". Use background_tool with action=result to read it.")
            if (preview.isNotBlank()) append('\n').append(preview)
        }
        return BackgroundToolNotice(
            conversationId = conversationId,
            key = backgroundNoticeKey(task.id),
            notice = UIMessagePart.Notice(title = "Background tool finished", text = text),
        )
    }

    private fun backgroundReceipt(id: String): String {
        val task = tasks[id]
        return buildJsonObject {
            put("status", "moved_to_background")
            put("task_id", id)
            put("tool", task?.toolName.orEmpty())
            put("task_status", task?.status?.name?.lowercase() ?: "running")
            put(
                "note",
                "This call is still running in the background; the same execution continues and will not be " +
                    "replayed. Continue with other work, then read the result with background_tool " +
                    "action=result (paged), action=wait, or stop it with action=cancel using this task_id.",
            )
        }.toString()
    }

    // ------------------------------------------------------------------ 磁盘

    private fun dirOf(task: BackgroundToolTask): File =
        dirOf(task.conversationId, task.id)

    private fun dirOf(ownerKey: String?, id: String): File =
        File(File(rootDir, ownerKey ?: BACKGROUND_OWNER_UNSCOPED), id)

    /**
     * 写任务记录。
     *
     * 失败**不抛**、也不改运行状态：原因记进 `persistenceError`（内存里照样能读，只是重启后会丢）。
     * 前台已经成功的输出绝不能因为写盘失败变成「失败」或「假完成」。
     */
    private fun persist(task: BackgroundToolTask): Boolean {
        val failure = synchronized(persistenceLock) {
            runCatching {
                val dir = dirOf(task).apply { mkdirs() }
                val target = File(dir, BACKGROUND_TASK_FILE)
                val tmp = File(dir, "$BACKGROUND_TASK_FILE.tmp")
                tmp.writeText(json.encodeToString(BackgroundToolTask.serializer(), task))
                if (!tmp.renameTo(target)) {
                    target.delete()
                    check(tmp.renameTo(target)) { "rename ${tmp.name} -> ${target.name} failed" }
                }
            }.exceptionOrNull()
        }
        if (failure == null) return true
        recordPersistenceError(task.id, "persistence failed: ${describe(failure)}")
        return false
    }

    private fun writeResult(task: BackgroundToolTask, text: String): Boolean {
        val failure = synchronized(persistenceLock) {
            runCatching {
                val dir = dirOf(task).apply { mkdirs() }
                val target = File(dir, BACKGROUND_RESULT_FILE)
                val tmp = File(dir, "$BACKGROUND_RESULT_FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    check(tmp.renameTo(target)) { "rename ${tmp.name} -> ${target.name} failed" }
                }
            }.exceptionOrNull()
        }
        if (failure == null) return true
        recordPersistenceError(task.id, "result persistence failed: ${describe(failure)}")
        return false
    }

    private fun recordPersistenceError(id: String, message: String) {
        tasks.computeIfPresent(id) { _, current ->
            if (current.persistenceError == null) current.copy(persistenceError = message) else current
        }
    }

    /** 留存正文的长度：内存优先；老记录缺 `storedChars` 时按文件字符数流式统计。 */
    private fun storedLength(task: BackgroundToolTask): Int {
        if (!task.isFinished || !task.bodyRetained) return 0
        if (task.storedChars > 0) return task.storedChars
        outputs[task.id]?.let { parts -> if (parts.isNotEmpty()) return textOf(parts).length }
        val file = File(dirOf(task), BACKGROUND_RESULT_FILE)
        if (!file.isFile) return 0
        return runCatching {
            file.reader().use { reader ->
                val buffer = CharArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrDefault(0)
    }

    /**
     * 读留存正文的一段：内存里有完整 parts 就切片，否则走文件流式 `skip` + `read`。
     * 最多读 [length] 个字符——一页的大小决定内存占用，不把整份正文读进来。
     */
    private fun readStoredText(task: BackgroundToolTask, start: Int, length: Int): String {
        if (length <= 0 || start < 0) return ""
        outputs[task.id]?.let { parts ->
            val text = textOf(parts)
            if (text.isNotEmpty()) {
                val from = start.coerceAtMost(text.length)
                return text.substring(from, (from + length).coerceAtMost(text.length))
            }
        }
        val file = File(dirOf(task), BACKGROUND_RESULT_FILE)
        if (!file.isFile) return ""
        return runCatching {
            file.reader().use { reader ->
                var skipped = 0
                while (skipped < start) {
                    val step = reader.skip((start - skipped).toLong())
                    if (step <= 0L) return@use ""
                    skipped += step.toInt()
                }
                val buffer = CharArray(length)
                var read = 0
                while (read < length) {
                    val step = reader.read(buffer, read, length - read)
                    if (step < 0) break
                    read += step
                }
                String(buffer, 0, read)
            }
        }.getOrNull().orEmpty()
    }

    /** 页首/页尾不切开代理对：只在边界上多看两个字符，不整份读入。 */
    private fun pageSlice(
        task: BackgroundToolTask,
        total: Int,
        offset: Int,
        limit: Int,
    ): Pair<Int, String> {
        var start = offset.coerceIn(0, total)
        if (start in 1 until total) {
            val edge = readStoredText(task, start - 1, 2)
            if (edge.length == 2 && edge[1].isLowSurrogate() && edge[0].isHighSurrogate()) start -= 1
        }
        var end = (start + limit).coerceAtMost(total)
        if (end in 1 until total) {
            val edge = readStoredText(task, end - 1, 2)
            if (edge.length == 2 && edge[1].isLowSurrogate() && edge[0].isHighSurrogate()) end += 1
        }
        return start to readStoredText(task, start, end - start)
    }

    private fun readTaskFile(dir: File): BackgroundToolTask? = runCatching {
        if (!dir.isDirectory) return null
        val file = File(dir, BACKGROUND_TASK_FILE)
        if (!file.isFile) return null
        json.decodeFromString(BackgroundToolTask.serializer(), file.readText())
    }.getOrNull()

    /**
     * 冷启动恢复：每个 owner 目录最多读 [BACKGROUND_MAX_TASKS_PER_OWNER] 条。
     * 还标着 `RUNNING` 的一律改成 `INTERRUPTED`（效果未知），**绝不重放**。
     *
     * 老记录没有 `storedChars`（旧版本落的盘）：这里补算一次，之后分页直接用记录里的长度，
     * 不用每次翻页都去数字符。
     */
    @Synchronized
    private fun ensureRestored() {
        if (!restored.compareAndSet(false, true)) return
        runCatching {
            rootDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { ownerDir ->
                ownerDir.listFiles().orEmpty()
                    .filter { it.isDirectory && isBackgroundTaskId(it.name) }
                    .mapNotNull { readTaskFile(it) }
                    .sortedByDescending { it.createdAt }
                    .take(BACKGROUND_MAX_TASKS_PER_OWNER)
                    .forEach { task ->
                        val record = if (task.status == BackgroundToolStatus.RUNNING) {
                            val resumed = task.copy(
                                status = BackgroundToolStatus.INTERRUPTED,
                                finishedAt = clock(),
                                error = "interrupted: the process stopped while this call was running; " +
                                    "its effects are unknown and it was NOT replayed",
                            )
                            persist(resumed)
                            resumed
                        } else {
                            task
                        }
                        tasks.putIfAbsent(record.id, record)
                        crashTracked.add(record.id)
                        if (record.bodyRetained && record.storedChars == 0) {
                            tasks.computeIfPresent(record.id) { _, current ->
                                if (current.storedChars == 0) current.copy(storedChars = storedLength(current)) else current
                            }
                        }
                    }
            }
        }
    }

    /**
     * 单个会话的保留上限：记录最多 [BACKGROUND_MAX_TASKS_PER_OWNER] 条（内存 + 磁盘），
     * 正文最多给最近 [BACKGROUND_MAX_RETAINED_BODIES] 条终态任务留在内存里（更老的仍从磁盘分页读）。
     *
     * 同时回收 `outputs` / `jobs` / `notified` / `backgrounded` / `leased` 里同一批 id——
     * 这些按 task id 存的东西原来没人清，是真正随调用次数增长的那部分内存。
     */
    private fun pruneOwner(owner: BackgroundToolOwner) {
        val ownerKey = owner.conversationId?.toString()
        val dir = File(rootDir, ownerKey ?: BACKGROUND_OWNER_UNSCOPED)
        runCatching {
            val mine = tasks.values.filter { it.conversationId == ownerKey }
            mine.sortedByDescending { it.createdAt }
                .drop(BACKGROUND_MAX_TASKS_PER_OWNER)
                .filter { it.isFinished }
                .forEach { forget(it) }
            mine.filter { it.isFinished && it.bodyRetained && outputs.containsKey(it.id) }
                .sortedByDescending { it.createdAt }
                .drop(BACKGROUND_MAX_RETAINED_BODIES)
                .forEach { outputs.remove(it.id) }
            val onDisk = dir.listFiles().orEmpty()
                .filter { it.isDirectory && isBackgroundTaskId(it.name) }
                .sortedByDescending { it.lastModified() }
                .drop(BACKGROUND_MAX_TASKS_PER_OWNER)
            onDisk.forEach { old -> if (!tasks.containsKey(old.name)) runCatching { old.deleteRecursively() } }
        }
    }

    /** 从内存里彻底忘掉一条任务，并删掉它的磁盘目录。 */
    private fun forget(task: BackgroundToolTask) {
        tasks.remove(task.id)
        outputs.remove(task.id)
        jobs.remove(task.id)
        notified.remove(task.id)
        backgrounded.remove(task.id)
        crashTracked.remove(task.id)
        releaseLease(task.id)
        runCatching { dirOf(task).deleteRecursively() }
    }

    /**
     * 跨会话的总量兜底：记录数超过 [BACKGROUND_MAX_TASKS_TOTAL] 时从内存里淘汰最老的终态任务。
     *
     * 只清内存、不动磁盘：下次 `get` 还会从 `task.json` 懒读回来，历史不会因为这个丢。
     */
    private fun pruneGlobal() {
        if (tasks.size <= BACKGROUND_MAX_TASKS_TOTAL) return
        val overflow = tasks.size - BACKGROUND_MAX_TASKS_TOTAL
        tasks.values
            .filter { it.isFinished }
            .sortedBy { it.createdAt }
            .take(overflow)
            .forEach { task ->
                tasks.remove(task.id)
                outputs.remove(task.id)
                jobs.remove(task.id)
                notified.remove(task.id)
            }
    }

    private fun textOf(parts: List<UIMessagePart>): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun describe(error: Throwable): String =
        "[${error.javaClass.simpleName}] ${(error.message ?: "tool execution failed").take(2_000)}"
}

/** 完成提醒的收件箱 key：同一任务只放进一条。 */
internal fun backgroundNoticeKey(taskId: String): String = "background_tool:$taskId"

private val BACKGROUND_TASK_ID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

/** 严格 UUID：任何带 `/`、`\`、`..` 的 id 都拼不出路径。 */
internal fun isBackgroundTaskId(id: String): Boolean = BACKGROUND_TASK_ID_PATTERN.matches(id)
