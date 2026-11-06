package me.rerere.rikkahub.automation

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
 * 配方、运行历史、待确认副作用的本地持久化：App 私有目录一份 JSON。
 *
 * 三条硬要求：
 * - **原子写**：临时文件 + fsync + rename；rename 失败就保留旧文件，绝不"截断正式文件重写"
 *   （半截 JSON 会让配方和历史全丢，还可能让"未知副作用"记录一起消失）。
 * - **磁盘 IO 只在 IO 线程**：所有读写都包在 withContext(Dispatchers.IO) 里。
 * - **待确认记录独立于历史**：清历史不清 pending，否则用户能靠清历史绕过副作用确认。
 */
class AutomationStore(private val context: Context) {

    @Serializable
    private data class Payload(
        val version: Int = 2,
        val recipes: List<AutomationRecipe> = emptyList(),
        val runs: List<AutomationRun> = emptyList(),
        val pending: List<AutomationPendingBlock> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private val file: File get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val _recipes = MutableStateFlow<List<AutomationRecipe>>(emptyList())
    val recipes: StateFlow<List<AutomationRecipe>> = _recipes.asStateFlow()

    private val _runs = MutableStateFlow<List<AutomationRun>>(emptyList())
    val runs: StateFlow<List<AutomationRun>> = _runs.asStateFlow()

    private val _pending = MutableStateFlow<List<AutomationPendingBlock>>(emptyList())
    val pending: StateFlow<List<AutomationPendingBlock>> = _pending.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        val payload = withContext(Dispatchers.IO) { readFromDisk() }
        val now = System.currentTimeMillis()
        val normalized = AutomationRunGuard.normalizeOnLoad(payload.runs, now)
        _recipes.value = payload.recipes.sortedBy { it.createdAt }
        _runs.value = normalized.runs.take(MAX_RUNS)
        _pending.value = (payload.pending + normalized.pending).distinctBy { it.runId }
        loaded = true
        // 归一后立刻落盘，避免下次读到的还是 RUNNING
        if (normalized.runs != payload.runs || _pending.value != payload.pending) {
            withContext(Dispatchers.IO) { writeToDisk() }
        }
    }

    /** 只给纯 JVM 测试/调试用：直接塞入内存状态，不读盘。 */
    suspend fun seed(recipes: List<AutomationRecipe>, runs: List<AutomationRun> = emptyList()) = mutex.withLock {
        if (loaded) return@withLock
        _recipes.value = recipes
        _runs.value = runs.take(MAX_RUNS)
        _pending.value = emptyList()
        loaded = true
    }

    suspend fun saveRecipe(recipe: AutomationRecipe): AutomationRecipe = mutex.withLock {
        val next = (_recipes.value.filterNot { it.id == recipe.id } + recipe).sortedBy { it.createdAt }
        _recipes.value = next
        withContext(Dispatchers.IO) { writeToDisk() }
        recipe
    }

    suspend fun deleteRecipe(recipeId: String) = mutex.withLock {
        _recipes.value = _recipes.value.filterNot { it.id == recipeId }
        // 历史与待确认记录都保留：用户删了配方，也要看得到上次跑过什么、还有什么没确认
        withContext(Dispatchers.IO) { writeToDisk() }
    }

    suspend fun appendRun(run: AutomationRun) = mutex.withLock {
        _runs.value = (listOf(run) + _runs.value).take(MAX_RUNS)
        AutomationRunGuard.pendingFromRun(run)?.let { block ->
            _pending.value = (_pending.value.filterNot { it.runId == block.runId } + block)
        }
        withContext(Dispatchers.IO) { writeToDisk() }
    }

    suspend fun updateRun(run: AutomationRun) = mutex.withLock {
        _runs.value = _runs.value.map { if (it.id == run.id) run else it }.take(MAX_RUNS)
        // 写步骤变成"可能已生效"时立刻登记待确认，不等进程退出
        AutomationRunGuard.pendingFromRun(run)?.let { block ->
            _pending.value = (_pending.value.filterNot { it.runId == block.runId } + block)
        }
        withContext(Dispatchers.IO) { writeToDisk() }
    }

    suspend fun acknowledgeRun(runId: String) = mutex.withLock {
        _runs.value = AutomationRunGuard.acknowledge(_runs.value, runId)
        _pending.value = AutomationRunGuard.acknowledgePending(_pending.value, runId)
        withContext(Dispatchers.IO) { writeToDisk() }
    }

    /** 清历史：**不清**待确认记录，否则等于绕过副作用确认。 */
    suspend fun clearHistory() = mutex.withLock {
        _runs.value = emptyList()
        withContext(Dispatchers.IO) { writeToDisk() }
    }

    private fun readFromDisk(): Payload {
        val target = file
        if (!target.isFile) return Payload()
        return runCatching {
            json.decodeFromString(Payload.serializer(), target.readText())
        }.getOrElse { error ->
            // 读坏了先当空，但保留坏文件副本，方便排查而不是直接丢
            Log.w(TAG, "read automation store failed", error)
            runCatching { target.copyTo(File(target.parentFile, "$FILE_NAME.corrupt"), overwrite = true) }
            Payload()
        }
    }

    private fun writeToDisk() {
        val target = file
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$FILE_NAME.part")
            FileOutputStream(temp).use { output ->
                output.write(
                    json.encodeToString(
                        Payload.serializer(),
                        Payload(recipes = _recipes.value, runs = _runs.value, pending = _pending.value),
                    ).toByteArray(),
                )
                output.flush()
                output.fd.sync()
            }
            if (!temp.renameTo(target)) {
                Log.w(TAG, "atomic rename failed, keeping the previous automation store")
                temp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "write automation store failed", error) }
    }

    private companion object {
        const val TAG = "AutomationStore"
        const val DIRECTORY = "automation"
        const val FILE_NAME = "automation.json"
        const val MAX_RUNS = 50
    }
}
