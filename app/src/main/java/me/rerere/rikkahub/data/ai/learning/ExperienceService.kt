package me.rerere.rikkahub.data.ai.learning

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.ai.tools.findForbiddenMemoryContent
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.knowledge.KnowledgeImportResult
import me.rerere.rikkahub.data.knowledge.KnowledgeRejectReason
import me.rerere.rikkahub.data.knowledge.LocalKnowledgeRepository
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult

private const val TAG = "ExperienceService"

/**
 * 每轮任务结束后，让模型把这一轮里「可复用的做法」总结成经验，写进助手记忆库，并按需并入本地知识库。
 *
 * 设计边界（都有对应实现，不只是提示词）：
 * 1. **每轮一次**：[ExperienceTriggers] 判定为终态（成功 / 终止）时由调用方触发一次；
 *    同一轮（会话 + 助手消息 id）由进程内 [ExperienceInFlight] 和落盘记账双重去重，不会因为
 *    多个 tool call、重建页面或重放事件而重复请求模型；
 * 2. **取消不算成功**：取消路径根本不会调用这里；终态枚举只有 SUCCESS / TERMINATED；
 * 3. **失败可见但不阻塞**：[onTurnFinished] 只返回 [Job]；模型请求失败、超时、记忆写入被拒、
 *    知识库写入失败都写进 [ExperienceRunReport] / [lastReport] / 日志，主回复早已返回；
 * 4. **只写有依据的东西**：经验文本过 [ExperienceSanitizer]（脱敏 / 拒推测 / 拒空话），
 *    记忆与知识库各有内容去重，同一段文本不会写第二遍；
 * 5. **不越权**：知识库写入固定为当前助手自己的 scope（仓库内部还会再校验一次），
 *    不写别的助手的记忆，也不改任何设置、权限或系统提示。
 */
class ExperienceService(
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val memoryRepository: MemoryRepository,
    private val knowledgeRepository: LocalKnowledgeRepository,
    private val ledger: ExperienceLedger,
    /**
     * 开关来源：由 DI 接 [AssistantLearningRepository.experienceSettings]。
     * 默认全关，未接线时绝不会额外请求模型、也不会写任何文件。
     */
    private val flagsOf: suspend (assistantId: Uuid) -> ExperienceSettings = { ExperienceSettings() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _lastReport = MutableStateFlow<ExperienceRunReport?>(null)

    /** 最近一次总结的结果，给设置页显示「上一次做了什么 / 为什么没写」。 */
    val lastReport: StateFlow<ExperienceRunReport?> = _lastReport.asStateFlow()

    /**
     * 任务完成路径上调用的唯一入口：把这一轮交给经验学习，**立即返回**，绝不阻塞主回复。
     *
     * 调用方只在终态分支调一次；重复调用是安全的（会被去重）。
     */
    fun onTurnFinished(facts: TurnFacts): Job = appScope.launch(Dispatchers.IO) {
        runCatching { reflect(facts) }
            .onFailure { if (it !is CancellationException) Log.w(TAG, "Experience reflection failed", it) }
    }

    /**
     * 真正干活的部分；[onTurnFinished] 是它的后台包装。
     *
     * 返回的报告永远不为空——被跳过、失败、没写出东西都会说明原因。
     */
    suspend fun reflect(facts: TurnFacts): ExperienceRunReport {
        val assistantId = facts.assistantId.toString()
        val key = experienceKey(facts.conversationId.toString(), facts.messageId.toString())

        // 1) 同一轮正在处理中：直接跳过，连记账都不必改
        if (!ExperienceInFlight.tryAcquire(key)) return skip(facts, "这一轮已经在总结中")
        try {
            // 2) 开关：默认关闭，必须由用户显式打开
            val settings = runCatching { settingsStore.awaitLoaded() }.getOrNull()
                ?: return skip(facts, "设置还没加载完")
            val flags = flagsOf(facts.assistantId)
            if (!flags.reflectionEnabled) return skip(facts, "经验学习开关未打开")
            if (!flags.writeToMemory && !flags.writeToKnowledge) return skip(facts, "记忆与知识库都未开启写入")

            // 3) 落盘记账：这一轮处理过就什么都不做（重启/多进程后依然有效）
            val alreadyHandled = ledger.transact(assistantId) { file -> file to (key in file.handled) }
            if (alreadyHandled) return skip(facts, "这一轮已经总结过")

            val model = resolveModel(settings, facts.assistantId) ?: return skip(facts, "没有可用的模型")
            val providerSetting = model.findProvider(settings.providers)
                ?: return skip(facts, "模型没有可用的服务商")
            if (providerSetting is ProviderSetting.WebAccount && !flags.writeToKnowledge) {
                return skip(facts, "该服务商不支持后台总结请求")
            }

            // 4) 请求模型
            val candidates = try {
                requestCandidates(model, providerSetting, facts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return finish(
                    facts,
                    ExperienceRunReport(
                        assistantId = assistantId,
                        conversationId = facts.conversationId.toString(),
                        messageId = facts.messageId.toString(),
                        outcome = facts.outcome,
                        error = "总结经验请求失败：" + (e.message ?: e::class.simpleName.orEmpty()),
                        at = clock(),
                    ),
                    // 请求失败不标记已处理：下一次同一轮再触发时可以重试
                    markHandled = false,
                )
            }

            // 5) 没有可复用经验：正常结果，标记已处理，不写任何文件
            if (candidates.isEmpty()) {
                return finish(
                    facts,
                    ExperienceRunReport(
                        assistantId = assistantId,
                        conversationId = facts.conversationId.toString(),
                        messageId = facts.messageId.toString(),
                        outcome = facts.outcome,
                        at = clock(),
                    ),
                )
            }

            // 6) 落盘：记忆库 +（可配置）知识库
            val write = write(assistantId, facts, flags, candidates)
            return finish(
                facts,
                ExperienceRunReport(
                    assistantId = assistantId,
                    conversationId = facts.conversationId.toString(),
                    messageId = facts.messageId.toString(),
                    outcome = facts.outcome,
                    candidates = candidates,
                    write = write,
                    at = clock(),
                ),
            )
        } finally {
            ExperienceInFlight.release(key)
        }
    }

    /** 清空某助手的总结记账（不动记忆库和知识库里已经写下的内容）。 */
    suspend fun clearRecords(assistantId: String) = ledger.clear(assistantId)

    // ---- 内部实现 ----

    private suspend fun requestCandidates(
        model: Model,
        providerSetting: ProviderSetting,
        facts: TurnFacts,
    ): List<ExperienceCandidate> {
        val params: TextGenerationParams = backgroundTextGenerationParams(
            model = model.copy(tools = emptySet()),
            sessionId = Uuid.random(),
            reasoningLevel = ReasoningLevel.OFF,
        )
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(providerSetting),
            UsageTag(UsageSource.LEARNING, facts.assistantId, facts.conversationId),
        )
        val messages = listOf(
            UIMessage.system(ExperiencePromptBuilder.systemPrompt()),
            UIMessage.user(ExperiencePromptBuilder.userPrompt(facts)),
        )
        val result = withTimeoutOrNull(LearningExperienceLimits.TIMEOUT_MS) {
            provider.generateText(providerSetting, messages, params)
        } ?: throw TimeoutException("总结经验超时")
        val text = result.message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        // 解析时已经脱敏；这里再用记忆写入的硬拦截兜一层（证件号/卡号不能只靠提示词）
        return ExperiencePromptBuilder.parse(text).filterNot { findForbiddenMemoryContent(it.text) != null }
    }

    private suspend fun write(
        assistantId: String,
        facts: TurnFacts,
        flags: ExperienceSettings,
        candidates: List<ExperienceCandidate>,
    ): ExperienceWriteReport {
        var dedupe = ExperienceDedupe.NEW
        var memoryPath: String? = null
        var memoryRejected: String? = null
        var knowledgeDocId: String? = null
        var knowledgeRejected: String? = null

        // 记忆库：写进 `/lessons/workflow.md` 的一行；内容与指纹双重去重，不塞进用户的资料文件
        if (flags.writeToMemory) {
            val path = lessonPath()
            for (candidate in candidates) {
                if (claimFingerprint(assistantId, candidate.text, knowledge = false)) {
                    dedupe = ExperienceDedupe.ALREADY_IN_MEMORY
                    continue
                }
                val line = lessonLine(candidate)
                val result = runCatching {
                    val current = memoryRepository.getFile(assistantId, path)
                    if (current == null) {
                        memoryRepository.writeFile(assistantId, path,
                            "---\nname: workflow\ntitle: 任务经验\ndescription: 已完成任务中提炼的做法，区别于用户事实\n---\n\n$line\n",
                            ifVersion = MemoryRepository.NEW_FILE_VERSION)
                    } else {
                        memoryRepository.appendToFile(assistantId, path, line, ifVersion = current.version)
                    }
                }.getOrElse { MemoryWriteResult.Rejected(it.message ?: "记忆写入失败") }
                when (result) {
                    is MemoryWriteResult.Success ->
                        if (result.unchanged) dedupe = ExperienceDedupe.ALREADY_IN_MEMORY else memoryPath = path
                    is MemoryWriteResult.Rejected -> {
                        memoryRejected = result.reason
                        releaseFingerprint(assistantId, candidate.text, knowledge = false)
                    }
                    is MemoryWriteResult.Conflict -> {
                        memoryRejected = "记忆文件版本冲突，已跳过"
                        releaseFingerprint(assistantId, candidate.text, knowledge = false)
                    }
                }
            }
        } else {
            memoryRejected = "用户关闭了记忆写入"
        }

        // 知识库：只放做法类（事实类属于用户/项目资料，归记忆库），scope 固定当前助手
        if (flags.writeToKnowledge) {
            val workflow = candidates.filter { it.kind == ExperienceKind.WORKFLOW }
            if (workflow.isEmpty()) {
                knowledgeRejected = "本轮经验不是做法类，不写入知识库"
            } else {
                for (candidate in workflow) {
                    if (claimFingerprint(assistantId, candidate.text, knowledge = true)) {
                        if (dedupe == ExperienceDedupe.NEW) dedupe = ExperienceDedupe.ALREADY_IN_MEMORY
                        continue
                    }
                    val result = runCatching {
                        knowledgeRepository.importText(
                            assistantId = facts.assistantId,
                            title = knowledgeTitle(),
                            text = knowledgeBody(facts, candidate),
                            sourceName = "经验学习",
                        )
                    }.getOrElse { KnowledgeImportResult.Rejected(KnowledgeRejectReason.Failed(it.message ?: "知识库写入失败")) }
                    when (result) {
                        is KnowledgeImportResult.Imported -> knowledgeDocId = result.doc.id
                        is KnowledgeImportResult.Duplicate -> knowledgeDocId = result.doc.id
                        is KnowledgeImportResult.Rejected -> {
                            knowledgeRejected = describeReject(result)
                            releaseFingerprint(assistantId, candidate.text, knowledge = true)
                        }
                    }
                }
            }
        } else {
            knowledgeRejected = "用户关闭了知识库写入"
        }

        return ExperienceWriteReport(
            dedupe = dedupe,
            memoryPath = memoryPath,
            memoryRejected = memoryRejected,
            knowledgeDocId = knowledgeDocId,
            knowledgeRejected = knowledgeRejected,
        )
    }

    /**
     * 抢占一条经验文本的指纹。
     *
     * @return true 表示这条已经写过（指纹已在记录里）；false 表示这次抢到，可以写。
     */
    private suspend fun releaseFingerprint(assistantId: String, text: String, knowledge: Boolean) {
        val fingerprint = ExperienceSanitizer.fingerprint(text)
        ledger.transact(assistantId) { file ->
            val next = if (knowledge) file.copy(knowledgeFingerprints = file.knowledgeFingerprints - fingerprint)
                else file.copy(memoryFingerprints = file.memoryFingerprints - fingerprint)
            next to Unit
        }
    }

    private suspend fun claimFingerprint(assistantId: String, text: String, knowledge: Boolean): Boolean {
        val fingerprint = ExperienceSanitizer.fingerprint(text)
        return ledger.transact(assistantId) { file ->
            val existing = if (knowledge) file.knowledgeFingerprints else file.memoryFingerprints
            if (fingerprint in existing) {
                file to true
            } else {
                val updated = if (knowledge) {
                    file.copy(
                        knowledgeFingerprints = existing.plusCapped(
                            fingerprint,
                            LearningExperienceLimits.MAX_FINGERPRINTS,
                        )
                    )
                } else {
                    file.copy(
                        memoryFingerprints = existing.plusCapped(
                            fingerprint,
                            LearningExperienceLimits.MAX_FINGERPRINTS,
                        )
                    )
                }
                updated to false
            }
        }
    }

    /** 收尾：记账、更新 [lastReport]、返回报告。记账失败只记日志，不影响已写下的经验。 */
    private suspend fun finish(
        facts: TurnFacts,
        report: ExperienceRunReport,
        markHandled: Boolean = true,
    ): ExperienceRunReport {
        val assistantId = facts.assistantId.toString()
        val key = experienceKey(facts.conversationId.toString(), facts.messageId.toString())
        runCatching {
            ledger.transact(assistantId) { file ->
                val handled = if (markHandled) file.handled.plusCapped(key, LearningExperienceLimits.MAX_HANDLED)
                else file.handled
                val record = ExperienceRunRecord(
                    at = report.at,
                    conversationId = report.conversationId,
                    messageId = report.messageId,
                    outcome = report.outcome.name,
                    skipped = report.skipped,
                    error = report.error,
                    wrote = report.write?.wroteAnything == true,
                    memoryPath = report.write?.memoryPath,
                    knowledgeDocId = report.write?.knowledgeDocId,
                    rejected = report.write?.memoryRejected ?: report.write?.knowledgeRejected,
                )
                file.copy(
                    handled = handled,
                    recent = (file.recent + record).takeLast(LearningExperienceLimits.MAX_RUN_RECORDS),
                ) to Unit
            }
        }.onFailure { Log.w(TAG, "Could not record experience run", it) }
        _lastReport.value = report
        return report
    }

    private fun skip(facts: TurnFacts, reason: String): ExperienceRunReport = ExperienceRunReport(
        assistantId = facts.assistantId.toString(),
        conversationId = facts.conversationId.toString(),
        messageId = facts.messageId.toString(),
        outcome = facts.outcome,
        skipped = reason,
        at = clock(),
    )

    private fun describeReject(result: KnowledgeImportResult.Rejected): String = when (val reason = result.reason) {
        is KnowledgeRejectReason.Failed -> reason.message
        is KnowledgeRejectReason.UnsupportedFormat -> "格式不支持"
        KnowledgeRejectReason.TooLarge -> "内容过大"
        KnowledgeRejectReason.EmptyText -> "内容为空"
        KnowledgeRejectReason.EmptyDocument -> "清洗后为空"
        KnowledgeRejectReason.Cancelled -> "已取消"
    }

    /**
     * 选用来做总结的模型：记忆模型 → 快速模型 → 助手对话模型 → 全局对话模型。
     * 这里只是一次纯文本补全，所以不要求模型具备工具能力。
     */
    private fun resolveModel(settings: Settings, assistantId: Uuid): Model? {
        val assistant = settings.getAssistantById(assistantId)
        val candidates = listOfNotNull(
            settings.memoryModelId?.let(settings::findModelById),
            settings.fastModelId?.let(settings::findModelById),
            assistant?.chatModelId?.let(settings::findModelById),
            settings.findModelById(settings.chatModelId),
        ).distinctBy { it.id }
        return candidates.firstOrNull { it.findProvider(settings.providers) != null }
    }

    /** 经验写进哪个记忆文件：单独一个文件，不往用户的资料文件里塞。 */
    private fun lessonPath(): String = MemoryFile.normalizePath("/lessons/workflow.md") ?: "/lessons/workflow.md"

    /** 记忆库里的一行：带来源标记，便于用户识别和清理。 */
    private fun lessonLine(candidate: ExperienceCandidate): String =
        "- [经验·" + candidate.kind.label + "] " + candidate.text

    private fun knowledgeTitle(): String = "任务经验 " + DAY.format(Instant.ofEpochMilli(clock()))

    private fun knowledgeBody(facts: TurnFacts, workflow: ExperienceCandidate): String = buildString {
        appendLine("# 任务经验")
        appendLine()
        appendLine("- 结果：${facts.outcome.name}")
        if (facts.toolNames.isNotEmpty()) appendLine("- 用到的工具：${facts.toolNames.joinToString(", ")}")
        if (facts.failedToolNames.isNotEmpty()) {
            appendLine("- 失败的工具：${facts.failedToolNames.joinToString(", ")}")
        }
        appendLine()
        appendLine("## 可复用的做法")
        appendLine("- ${workflow.text}")
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }
}

/**
 * 从本助手的经验库状态读出学习开关。
 *
 * 沿用已有的每助手自我学习开关 [LearningState.enabled]，再叠加单独的经验总结开关
 * [LearningState.experienceReflection]（默认关闭，因为每轮会多一次模型请求，会计费）。
 * 不新建一套全局设置：开关就存在那个助手自己的经验库里。
 */
fun experienceSettingsOf(state: LearningState): ExperienceSettings = ExperienceSettings(
    reflectionEnabled = state.enabled && state.experienceReflection,
    writeToKnowledge = true,
    writeToMemory = true,
)
