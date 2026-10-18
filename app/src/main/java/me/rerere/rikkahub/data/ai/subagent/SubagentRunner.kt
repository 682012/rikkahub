package me.rerere.rikkahub.data.ai.subagent

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "SubagentRunner"
private const val HARD_MAX_CONCURRENT = 4
private const val HARD_MAX_STEPS = 120

/**
 * 子代理执行器。
 *
 * 与 MemoryConsolidator 同一套路：独立模型、独立工具集、独立 runAgent，
 * 区别是任务异步运行、支持多任务并发与结果落盘。
 */
class SubagentRunner(
    private val context: Application,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, SubagentTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val active = AtomicInteger(0)
    private val released = MutableStateFlow(0)
    private val state = MutableStateFlow<List<SubagentTask>>(emptyList())

    fun observe(): StateFlow<List<SubagentTask>> = state.asStateFlow()

    fun get(id: String): SubagentTask? = tasks[id]

    fun list(): List<SubagentTask> = tasks.values.sortedByDescending { it.createdAt }

    /**
     * 启动一个子代理任务并立即返回。
     *
     * [tools] 由调用方传入，通常已经去掉 subagent_* 自身，避免递归。
     */
    fun start(
        prompt: String,
        tools: List<Tool>,
        modelId: Uuid? = null,
        conversationId: Uuid? = null,
        maxSteps: Int = 40,
        timeoutSec: Int = 900,
        systemPrompt: String? = null,
    ): SubagentTask {
        val id = Uuid.random().toString().take(8)
        val task = SubagentTask(
            id = id,
            prompt = prompt,
            modelId = modelId,
            conversationId = conversationId,
            resultPath = resultFile(id).absolutePath,
            logPath = logFile(id).absolutePath,
        )
        tasks[id] = task
        publish()
        val generationId = Uuid.random()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val settings = settingsStore.awaitLoaded()
            val cfg = settings.subagent
            pruneOld(cfg.keepDays)
            val limit = cfg.maxConcurrent.coerceIn(1, HARD_MAX_CONCURRENT)
            acquirePermit(limit)
            update(id) { it.copy(status = SubagentStatus.RUNNING, startedAt = System.currentTimeMillis()) }
            if (conversationId != null) {
                runCatching {
                    ChatGenerationForegroundService.acquire(context, generationId, conversationId)
                }.onFailure { Log.w(TAG, "acquire foreground service failed", it) }
            }
            try {
                val primaryId = modelId ?: cfg.defaultModelId
                val (finished, usedModelId) = try {
                    runOnce(id, settings, primaryId, prompt, tools, maxSteps, timeoutSec, systemPrompt, cfg.sessionId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val fallbackId = cfg.fallbackModelId
                    if (fallbackId != null && fallbackId != primaryId) {
                        Log.w(TAG, "subagent $id primary model failed, retrying with fallback", e)
                        appendLog(id, "[fallback] ${e.message}")
                        runOnce(id, settings, fallbackId, prompt, tools, maxSteps, timeoutSec, systemPrompt, cfg.sessionId)
                    } else {
                        throw e
                    }
                }
                if (finished == null) {
                    update(id) {
                        it.copy(
                            status = SubagentStatus.TIMEOUT,
                            finishedAt = System.currentTimeMillis(),
                            error = "Subagent timed out after ${timeoutSec}s",
                        )
                    }
                    appendLog(id, "[timeout] ${timeoutSec}s")
                } else {
                    val text = finished.message.toText()
                    resultFile(id).writeText(text)
                    update(id) {
                        it.copy(
                            status = SubagentStatus.COMPLETED,
                            finishedAt = System.currentTimeMillis(),
                            result = text,
                            usage = finished.message.usage,
                            steps = finished.message.getTools().size,
                            modelId = usedModelId,
                        )
                    }
                    appendLog(id, "[done] ${text.length} chars")
                    recordUsage(conversationId, usedModelId, finished.message.usage)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                update(id) {
                    it.copy(
                        status = SubagentStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis(),
                        error = "Cancelled",
                    )
                }
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "subagent $id failed", e)
                update(id) {
                    it.copy(
                        status = SubagentStatus.FAILED,
                        finishedAt = System.currentTimeMillis(),
                        error = e.message ?: e::class.java.simpleName,
                    )
                }
                appendLog(id, "[error] ${e.message}")
            } finally {
                active.decrementAndGet()
                released.value += 1
                jobs.remove(id)
                if (conversationId != null) {
                    runCatching {
                        ChatGenerationForegroundService.release(context, generationId)
                    }
                }
                publish()
            }
        }
        jobs[id] = job
        job.start()
        return task
    }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        update(id) { it.copy(status = SubagentStatus.CANCELLED, finishedAt = System.currentTimeMillis()) }
        publish()
        return true
    }

    suspend fun await(id: String, timeoutSec: Int = 0): SubagentTask? {
        val deadline = if (timeoutSec <= 0) Long.MAX_VALUE else System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val task = tasks[id] ?: return null
            if (task.isFinished) return task
            delay(250)
        }
        return tasks[id]
    }

    private suspend fun runOnce(
        id: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        modelId: Uuid?,
        prompt: String,
        tools: List<Tool>,
        maxSteps: Int,
        timeoutSec: Int,
        systemPrompt: String?,
        sessionId: Uuid,
    ): Pair<AgentEvent.Finished?, Uuid> {
        val cfg = settings.subagent
        val model = settings.findModelById(modelId, cfg.defaultModelId)
            ?: settings.getCurrentChatModel()
            ?: error("No model available for subagent")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.displayName}")
        val parentPrompt = systemPrompt ?: DEFAULT_SUBAGENT_PROMPT
        val params = backgroundTextGenerationParams(model, sessionId, settings.fastModelReasoningLevel)
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                val system = (listOf(parentPrompt) + tools.map { it.systemPrompt(model, messages) })
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                return StepRequest(
                    messages = if (system.isBlank()) messages else listOf(UIMessage.system(system)) + messages,
                    params = params,
                )
            }
        }
        val request = AgentRequest(
            provider = providerManager.getProviderByType(providerSetting),
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(prompt)),
            tools = tools,
            stream = false,
            maxSteps = maxSteps.coerceIn(1, HARD_MAX_STEPS),
            retry = RetryPolicy.NETWORK,
        )
        val timeoutMs = if (timeoutSec <= 0) 0L else timeoutSec * 1000L
        val finished = if (timeoutMs == 0L) {
            runAgent(request, hooks).collectFinished()
        } else {
            withTimeoutOrNull(timeoutMs) { runAgent(request, hooks).collectFinished() }
        }
        return finished to model.id
    }

    private suspend fun acquirePermit(limit: Int) {
        while (true) {
            val current = active.get()
            if (current < limit && active.compareAndSet(current, current + 1)) return
            val version = released.value
            released.first { it > version }
        }
    }

    private suspend fun recordUsage(conversationId: Uuid?, modelId: Uuid, usage: me.rerere.ai.core.TokenUsage?) {
        if (usage == null || usage.totalTokens <= 0) return
        runCatching {
            getKoin().get<UsageRecordDAO>().insert(
                UsageRecordEntity(
                    id = Uuid.random().toString(),
                    conversationId = conversationId?.toString().orEmpty(),
                    nodeId = null,
                    modelId = modelId.toString(),
                    source = "subagent",
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    cachedTokens = usage.cachedTokens,
                    totalTokens = usage.totalTokens,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Log.w(TAG, "record subagent usage failed", it) }
    }

    private fun pruneOld(keepDays: Int) {
        if (keepDays <= 0) return
        runCatching {
            val root = File(context.filesDir, "subagents")
            if (!root.isDirectory) return
            val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
            root.listFiles().orEmpty().forEach { dir ->
                if (dir.isDirectory && dir.lastModified() < cutoff) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun update(id: String, transform: (SubagentTask) -> SubagentTask) {
        tasks.computeIfPresent(id) { _, old -> transform(old) }
        publish()
    }

    private fun publish() {
        state.value = tasks.values.sortedByDescending { it.createdAt }
    }

    private fun dir(id: String): File = File(context.filesDir, "subagents/$id").apply { mkdirs() }

    private fun resultFile(id: String): File = File(dir(id), "result.md")

    private fun logFile(id: String): File = File(dir(id), "events.log")

    private fun appendLog(id: String, line: String) {
        runCatching {
            logFile(id).appendText("[${System.currentTimeMillis()}] $line\n")
        }
    }

    companion object {
        private const val DEFAULT_SUBAGENT_PROMPT = """
You are a focused subagent. You were spawned by a main assistant to complete one concrete task.
Work autonomously: use the provided tools, do not ask the user questions, and do not narrate your process.
When you are done, reply with a compact result: what you did, key findings, and any file paths or commands the parent needs.
If the task cannot be completed, say exactly what blocked you.
"""
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<AgentEvent>.collectFinished(): AgentEvent.Finished? {
    var finished: AgentEvent.Finished? = null
    collect { event ->
        if (event is AgentEvent.Finished) finished = event
    }
    return finished
}
