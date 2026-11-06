package me.rerere.rikkahub.automation

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 账本可信度。只有 [READY] 才允许派发任何执行；其余状态一律"不跑"，绝不拿空账本放行。
 */
enum class AutomationLedgerHealth {
    /** 还没读盘。 */
    NOT_LOADED,

    /** 读盘成功（或确实没有账本文件），磁盘可写。 */
    READY,

    /** 账本文件存在但读不出来/不是合法 JSON：一步都不许跑，而且**绝不覆盖原文件**。 */
    UNREADABLE,

    /** 读盘是好的，但最近一次写失败：盘上可能还停在旧快照（下一次成功的写入会补上）。 */
    WRITE_FAILED,
}

private const val LEDGER_DIRECTORY = "automation"
private const val LEDGER_FILE_NAME = "automation.json"
private const val MAX_RUNS = 50

/**
 * 配方、运行历史、待确认副作用的本地持久化：App 私有目录一份 JSON。
 *
 * 四条硬要求：
 *
 * - **原子写**：临时文件 + fsync + rename；rename 失败就保留旧文件并抛异常，绝不"截断正式文件重写"
 *   （半截 JSON 会让配方和历史全丢，还可能让"未知副作用"记录一起消失）。
 * - **写失败不假成功**：所有写方法要么真的写进磁盘，要么抛 [IOException]。内存状态只在落盘成功之后
 *   才更新（[commitLocked]），所以界面永远看不到一条磁盘上不存在的"成功"记录。
 * - **坏账本不放行**：账本文件存在却读不出来/解析不了时，状态是 [AutomationLedgerHealth.UNREADABLE]，
 *   一律拒绝执行和拒绝对其写入——既不假装"以前什么都没做过"，也不会把原文件覆盖掉。
 *   已有内容（recipes/runs/pending）原地保留，等用户处理。
 * - **待确认记录独立于历史**：清历史不清 pending，否则用户能靠清历史绕过副作用确认。
 *
 * IO 走 [AutomationLedgerIo]：真实实现是 [FileAutomationLedgerIo]，测试可以用内存假实现注入写失败。
 */
class AutomationStore(private val io: AutomationLedgerIo) {

    /** 现有 Koin/Controller 用法：`AutomationStore(context)`。 */
    constructor(context: Context) : this(
        FileAutomationLedgerIo(File(File(context.filesDir, LEDGER_DIRECTORY), LEDGER_FILE_NAME)),
    )

    @Serializable
    private data class Payload(
        val version: Int = 2,
        val recipes: List<AutomationRecipe> = emptyList(),
        val runs: List<AutomationRun> = emptyList(),
        val pending: List<AutomationPendingBlock> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()

    private val _recipes = MutableStateFlow<List<AutomationRecipe>>(emptyList())
    val recipes: StateFlow<List<AutomationRecipe>> = _recipes.asStateFlow()

    private val _runs = MutableStateFlow<List<AutomationRun>>(emptyList())
    val runs: StateFlow<List<AutomationRun>> = _runs.asStateFlow()

    private val _pending = MutableStateFlow<List<AutomationPendingBlock>>(emptyList())
    val pending: StateFlow<List<AutomationPendingBlock>> = _pending.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var healthState = AutomationLedgerHealth.NOT_LOADED

    @Volatile
    private var healthDetail: String? = null

    /** 当前可信度。 */
    val health: AutomationLedgerHealth get() = healthState

    /** 账本坏在哪（设置页/日志直接显示）；健康时为 null。 */
    val healthMessage: String? get() = healthDetail

    /**
     * true = 账本存在但不可信（读不出来/不是合法 JSON）。
     *
     * 这时执行入口必须拒绝派发：拿空账本放行等于假装"以前什么都没做过"，
     * 会把还没确认的未知副作用一起放掉。
     */
    val blocked: Boolean get() = healthState == AutomationLedgerHealth.UNREADABLE

    /**
     * 读盘（幂等）。
     *
     * 读失败**不抛异常**（设置页/Worker 启动时抛异常会把整个进程带崩），而是把状态记成
     * [AutomationLedgerHealth.UNREADABLE]，由 [blocked] 通知执行入口停手。
     */
    suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

    /** 只给纯 JVM 测试/调试用：直接塞入内存状态，不读盘。 */
    suspend fun seed(recipes: List<AutomationRecipe>, runs: List<AutomationRun> = emptyList()) = mutex.withLock {
        if (loaded) return@withLock
        _recipes.value = recipes
        _runs.value = runs.take(MAX_RUNS)
        _pending.value = emptyList()
        loaded = true
        healthState = AutomationLedgerHealth.READY
        healthDetail = null
    }

    /** 保存配方。落盘失败抛 [IOException]：不允许"看起来存上了其实没有"。 */
    suspend fun saveRecipe(recipe: AutomationRecipe): AutomationRecipe = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(
            recipes = (_recipes.value.filterNot { it.id == recipe.id } + recipe).sortedBy { it.createdAt },
        )
        recipe
    }

    /** 删配方。历史与待确认记录都保留（用户删了配方，也要看得到上次跑过什么、还有什么没确认）。 */
    suspend fun deleteRecipe(recipeId: String) = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(recipes = _recipes.value.filterNot { it.id == recipeId })
    }

    suspend fun appendRun(run: AutomationRun) = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(
            runs = (listOf(run) + _runs.value).take(MAX_RUNS),
            pending = pendingAfter(run),
        )
    }

    suspend fun updateRun(run: AutomationRun) = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(
            runs = _runs.value.map { if (it.id == run.id) run else it }.take(MAX_RUNS),
            // 写步骤变成"可能已生效"时立刻登记待确认，不等进程退出
            pending = pendingAfter(run),
        )
    }

    suspend fun acknowledgeRun(runId: String) = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(
            runs = AutomationRunGuard.acknowledge(_runs.value, runId),
            pending = AutomationRunGuard.acknowledgePending(_pending.value, runId),
        )
    }

    /** 清历史：**不清**待确认记录，否则等于绕过副作用确认。 */
    suspend fun clearHistory() = mutex.withLock {
        loadLocked()
        requireWritable()
        commitLocked(runs = emptyList())
    }

    // ------------------------------------------------------------------ 内部实现

    private fun pendingAfter(run: AutomationRun): List<AutomationPendingBlock> {
        val block = AutomationRunGuard.pendingFromRun(run) ?: return _pending.value
        return _pending.value.filterNot { it.runId == block.runId } + block
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val text = try {
            withContext(Dispatchers.IO) { io.read() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            markUnreadable("could not read ${io.location}: ${describe(error)}")
            loaded = true
            return
        }
        if (text == null) {
            // 真的没有账本文件（第一次运行）：正常空起步，不是"坏了"
            loaded = true
            healthState = AutomationLedgerHealth.READY
            healthDetail = null
            return
        }
        if (text.isBlank()) {
            markUnreadable("${io.location} exists but is empty")
            loaded = true
            return
        }
        val payload = try {
            json.decodeFromString(Payload.serializer(), text)
        } catch (error: Throwable) {
            markUnreadable("${io.location} is not a readable automation ledger: ${describe(error)}")
            loaded = true
            return
        }
        val now = System.currentTimeMillis()
        val normalized = AutomationRunGuard.normalizeOnLoad(payload.runs, now)
        loaded = true
        _recipes.value = payload.recipes.sortedBy { it.createdAt }
        _runs.value = normalized.runs.take(MAX_RUNS)
        _pending.value = (payload.pending + normalized.pending).distinctBy { it.runId }
        healthState = AutomationLedgerHealth.READY
        healthDetail = null
        // 归一结果（进程死掉留下的 RUNNING → 未知副作用 + 待确认）立刻落盘。
        // 写不进去就标成写失败：内存里保留归一后的真实状态，下一次成功的写会把它补上。
        if (normalized.runs != payload.runs || _pending.value != payload.pending) {
            try {
                persistLocked()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                markWriteFailed(error)
            }
        }
    }

    /**
     * 先落盘、成功后才提交到内存。
     *
     * 意义：内存永远不领先磁盘，界面不会显示一条磁盘上不存在的记录；反过来，一次写失败也不会让
     * 内存以为"已经记下了"。失败时抛异常且内存保持上一次成功的快照。
     */
    private suspend fun commitLocked(
        recipes: List<AutomationRecipe> = _recipes.value,
        runs: List<AutomationRun> = _runs.value,
        pending: List<AutomationPendingBlock> = _pending.value,
    ) {
        persistLocked(recipes = recipes, runs = runs, pending = pending)
        _recipes.value = recipes
        _runs.value = runs
        _pending.value = pending
        healthState = AutomationLedgerHealth.READY
        healthDetail = null
    }

    /** 写整份账本（配方/历史/待确认一起写），失败抛 [IOException]。 */
    private suspend fun persistLocked(
        recipes: List<AutomationRecipe> = _recipes.value,
        runs: List<AutomationRun> = _runs.value,
        pending: List<AutomationPendingBlock> = _pending.value,
    ) {
        val text = json.encodeToString(
            Payload.serializer(),
            Payload(recipes = recipes, runs = runs, pending = pending),
        )
        try {
            withContext(Dispatchers.IO) { io.write(text) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            markWriteFailed(error)
            val failure = if (error is IOException) {
                error
            } else {
                IOException("could not write ${io.location}: ${describe(error)}", error)
            }
            throw failure
        }
    }

    /** 账本坏了就不许写：写下去等于把原来那份（可能还有待确认记录）直接抹掉。 */
    private fun requireWritable() {
        if (healthState == AutomationLedgerHealth.UNREADABLE) {
            throw IOException(healthDetail ?: "the automation ledger is unreadable; refusing to overwrite it")
        }
    }

    private fun markUnreadable(reason: String) {
        healthState = AutomationLedgerHealth.UNREADABLE
        healthDetail = "$reason — the file was left untouched; nothing will be executed " +
            "against an unreadable ledger"
    }

    private fun markWriteFailed(error: Throwable) {
        healthState = AutomationLedgerHealth.WRITE_FAILED
        healthDetail = "could not write ${io.location}: ${describe(error)}"
    }

    private fun describe(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).take(200)
}
