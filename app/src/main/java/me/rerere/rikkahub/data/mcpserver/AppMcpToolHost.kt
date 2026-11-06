package me.rerere.rikkahub.data.mcpserver

import android.content.Context
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.automation.AutomationController
import me.rerere.rikkahub.automation.AutomationRecipe
import me.rerere.rikkahub.automation.AutomationSaveResult
import me.rerere.rikkahub.automation.AutomationScheduler
import me.rerere.rikkahub.automation.AutomationStep
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.devicecontrol.DeviceControlBridge
import me.rerere.rikkahub.data.devicecontrol.DeviceToolCatalog
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeMcpProxy
import me.rerere.rikkahub.data.organization.autofile.AutoFileManualWork
import me.rerere.rikkahub.data.organization.autofile.AutoFileMode
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileFacade
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileWork
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService

/**
 * App 内工具的唯一实现。MCP 服务器（`LocalMcpToolHost`）和本地自动化（`AutomationToolRunner`）
 * 都走同一个 [call]，所以两边的约束天然一致：开关、绑定助手、归属校验、幂等、写序列化。
 *
 * 与并行开发的 `LocalMcp*` 类型**无关**（这样它单独就能编译，接口定稿后再套一层
 * `AppLocalMcpToolHost` 适配，见文件末尾）。
 *
 * 依赖全部用 lazy provider 注入：`ChatService -> McpManager -> host -> ChatService` 和
 * `AutomationController -> runner -> host -> controller` 两个循环都不会在构造期成环。
 *
 * 约定：
 * - 写操作走同一把 [writeMutex] 串行；读操作不排队；
 * - 每个写操作先过 [AppMcpIdempotencyLedger]（`PENDING` 落盘 → 执行 → `DONE`），
 *   传输层重试同 key 同参数只回放结果，绝不重放动作；
 * - 所有 chatId / taskId / batchId 都要能证明归属于绑定助手，否则 `not_owned`；
 * - 未知工具一律 `unknown_tool`，**不会**"把参数丢给所有 App 工具试一遍"。
 */
class AppMcpToolHost(
    context: Context,
    private val gatesSource: AppMcpGatesSource,
    private val conversations: ConversationRepository,
    private val folders: FolderRepository,
    private val settingsStore: SettingsStore,
    private val chatService: () -> ChatService,
    private val subagentRunner: () -> SubagentRunner,
    private val autoFileFacade: () -> ConversationAutoFileFacade,
    private val deviceBridge: () -> DeviceControlBridge,
    private val automationController: () -> AutomationController,
    private val ledger: AppMcpIdempotencyLedger =
        AppMcpIdempotencyLedger(File(context.filesDir, "r22-mcp/idempotency.json")),
    /** 配方归属：`AutomationRecipe` 自己没有 assistantId 字段，归属记在 MCP 这一侧，换助手即失效。 */
    private val recipeOwners: AppMcpRecipeOwnership =
        AppMcpRecipeOwnership(File(context.filesDir, "r22-mcp/recipe-owners.json")),
    /**
     * m1 高权限中心工具（deviceprivilege）的代理面。**只认名字白名单**，别的一律不碰；
     * 这里刻意**不**在 [tools] 里列一遍——MCP 目录由 `AppLocalMcpToolHost` 统一追加，
     * 而本 hook 负责让本地自动化（`AppAutomationToolRunner` → [call]）也能到达同一批工具。
     * 授权完全由权限中心自己核（开关 + 绑定助手 + 写开关 + Shizuku 现查）。
     */
    private val privilegedTools: (() -> HighPrivilegeMcpProxy)? = null,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val writeMutex = Mutex()

    // ------------------------------------------------------------------ tools

    /** tools/list。随开关过滤；设备工具只在 `allowDevice` 打开时才出现。 */
    suspend fun tools(): List<AppMcpExposedTool> {
        val gates = gatesSource.current()
        val appTools = AppMcpToolCatalog.visible(gates).map { spec ->
            AppMcpExposedTool(
                name = spec.name,
                description = spec.description,
                inputSchema = spec.inputSchema,
                readOnly = spec.readOnly,
            )
        }
        if (!gates.grants(AppMcpGate.DEVICE)) return appTools
        // 取消/Error 不吞：只有普通异常才当成"暂时没有设备工具"。
        val deviceTools = try {
            deviceBridge().tools()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            emptyList()
        }.map { descriptor ->
            AppMcpExposedTool(
                name = descriptor.name,
                description = descriptor.description,
                inputSchema = if (descriptor.readOnly) {
                    descriptor.inputSchema
                } else {
                    // 写动作必须带 idempotency_key，schema 里真的写出来。
                    AppMcpToolCatalog.withIdempotencyKey(descriptor.inputSchema)
                },
                readOnly = descriptor.readOnly,
            )
        }
        return appTools + deviceTools
    }

    // ------------------------------------------------------------------- call

    /**
     * 执行一次调用。永不把异常抛给传输层：失败一律 `{"ok":false,"error":<code>,"message":...}`。
     */
    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val gates = gatesSource.current()

        // 参数层面的越权尝试：直接拒绝，而不是悄悄忽略。
        if (arguments.keys.any { it.equals("assistant_id", ignoreCase = true) }) {
            return error(
                "bad_argument",
                "assistant_id is not a parameter. This endpoint is bound to one assistant in local settings.",
            )
        }

        val spec = AppMcpToolCatalog.find(name)
        if (spec != null) {
            if (!gates.allows(spec)) return notGranted(spec)
            // 只有真按助手隔离的工具才要求绑定助手。automation_* 不需要（否则 tools/list 里列出来
            // 却永远调不通 = 公开能力与实现不一致）。app_status 也不需要。
            val assistantId = if (AppMcpGate.BOUND_ASSISTANT in spec.gates) {
                boundAssistant(gates) ?: return error(
                    "no_bound_assistant",
                    "No assistant is bound to this endpoint, so chat/auto-file/task access is disabled.",
                )
            } else {
                null
            }
            return execute(spec, arguments, assistantId, gates)
        }

        // 设备代理：桥自己还有一层授权（grants + 包白名单 + 无障碍服务状态）。
        val deviceTool = deviceDescriptor(name)
        if (deviceTool != null) {
            if (!gates.grants(AppMcpGate.DEVICE)) {
                return error("not_granted", "Device control is turned off in the local MCP settings.")
            }
            return executeDevice(deviceTool.name, deviceTool.readOnly, arguments)
        }

        // 高权限中心工具（deviceprivilege）：名字白名单之外一律不碰，仍然 unknown_tool。
        privilegedCall(name, arguments, gates)?.let { return it }

        return error("unknown_tool", "No such tool: $name")
    }

    /** 高权限中心工具的入口：名字白名单 + 本机开关 + 绑定助手；其余名字返回 null 表示"不是我的"。 */
    private suspend fun privilegedCall(name: String, arguments: JsonObject, gates: AppMcpGates): JsonObject? {
        val privileged = privilegedTools?.invoke() ?: return null
        if (!privileged.handles(name)) return null
        // 与 AppLocalMcpToolHost / 自动化步骤下拉同一口径：`read` 是底座，`device` 必须显式打开。
        // 少了这一道，关掉 allowDevice 之后本地自动化仍然能按名字跑到 android_*。
        if (!gates.read || !gates.grants(AppMcpGate.DEVICE)) {
            return error(
                "not_granted",
                "On-device privileges need both the read switch and the device switch in the local " +
                    "MCP/automation settings; they are currently off.",
            )
        }
        val bound = gates.boundAssistantId?.trim().orEmpty()
        if (bound.isEmpty()) {
            return error(
                "no_bound_assistant",
                "No assistant is bound to this endpoint, so on-device privileges are disabled.",
            )
        }
        return privileged.callAsBoundAssistant(bound, name, arguments)
    }

    /**
     * 高权限中心代理（可能为 null）。给 `AppAutomationToolRunner` 做名字白名单用：
     * 自动化步骤按名字运行 `android_*` 也走同一个 [call]，授权仍由权限中心自己核。
     */
    fun privilegedProxy(): HighPrivilegeMcpProxy? = privilegedTools?.invoke()

    // -------------------------------------------------------------- execution

    private suspend fun execute(
        spec: AppMcpToolSpec,
        arguments: JsonObject,
        assistantId: Uuid?,
        gates: AppMcpGates,
    ): JsonObject {
        // 幂等域：绑定了助手就按助手隔离，否则按"未绑定"域（device 工具单独一域）。
        val scope = assistantId?.toString() ?: boundScope(gates)
        if (!spec.requiresIdempotencyKey) {
            return runTool { dispatch(spec.name, arguments, assistantId) }
        }
        val key = arguments.stringField(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD)?.trim().orEmpty()
        if (key.isEmpty()) {
            return error(
                "idempotency_key_required",
                "${spec.name} changes state; pass idempotency_key and reuse it when retrying a transport failure.",
            )
        }
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(
            JsonObject(arguments.filterKeys { it != AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD }),
        )
        return writeLocked(
            tool = spec.name,
            key = key,
            fingerprint = fingerprint,
            scope = scope,
            recheck = { if (assistantId != null && !stillBound(assistantId)) scopeChangedError() else null },
        ) {
            dispatch(spec.name, arguments, assistantId)
        }
    }

    /**
     * 只把普通异常变成 `ok=false`；`CancellationException` 和 `Error` 原样抛出。
     * `runCatching` 会把"协程被取消"和"OOM"一起当成工具失败吞掉，那是错的。
     */
    private suspend fun runTool(block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (failure: Exception) {
        error("internal_error", failure.message ?: failure::class.simpleName.orEmpty())
    }

    private suspend fun executeDevice(name: String, readOnly: Boolean, arguments: JsonObject): JsonObject {
        val bridgeCall: suspend () -> JsonObject = {
            runTool { deviceBridge().call(name, arguments) }
        }
        if (readOnly) return bridgeCall()
        val key = arguments.stringField(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD)?.trim().orEmpty()
        if (key.isEmpty()) {
            return error(
                "idempotency_key_required",
                "$name changes device state; pass idempotency_key and reuse it when retrying.",
            )
        }
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(
            JsonObject(arguments.filterKeys { it != AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD }),
        )
        return writeLocked(name, key, fingerprint, DEVICE_SCOPE, null, bridgeCall)
    }

    /**
     * 写操作的统一外壳：全局串行 + 幂等账本 + "已派发但结果未知"不当成失败可重试。
     */
    private suspend fun writeLocked(
        tool: String,
        key: String,
        fingerprint: String,
        scope: String,
        recheck: (() -> JsonObject?)? = null,
        block: suspend () -> JsonObject,
    ): JsonObject = writeMutex.withLock {
        when (val decision = ledger.decide(tool, key, fingerprint, scope)) {
            is AppMcpIdemDecision.Replay -> return@withLock runCatching {
                Json.parseToJsonElement(decision.resultJson) as JsonObject
            }.getOrElse {
                error("previous_result_unreadable", "This key already ran; its recorded result is unreadable.")
            }

            is AppMcpIdemDecision.Rejected -> return@withLock error(decision.code, decision.message)

            is AppMcpIdemDecision.Fresh -> Unit
        }
        // 等锁 / 等账本落盘的这段时间里，绑定助手与授权可能已经变了：执行前再核一次。
        recheck?.invoke()?.let { return@withLock it }
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            // 传输层取消 / 连接断：动作可能已经发生，绝不能记成"失败可重试"。
            ledger.fail(tool, key, "cancelled while running; side effect unknown", scope)
            throw cancelled
        } catch (fatal: Error) {
            // Error 同样不吞：记成"副作用未知"后原样抛出，进程还活着时至少不会重放。
            ledger.fail(tool, key, "fatal ${fatal::class.simpleName}; side effect unknown", scope)
            throw fatal
        } catch (failure: Exception) {
            ledger.fail(tool, key, failure.message ?: failure::class.simpleName.orEmpty(), scope)
            return@withLock error(
                "write_outcome_unknown",
                "The write may or may not have happened (${failure.message ?: failure::class.simpleName}). " +
                    "Do not retry with the same key; inspect the actual state first.",
            )
        }
        ledger.complete(tool, key, result.toString(), scope)
        result
    }

    private suspend fun dispatch(tool: String, args: JsonObject, assistantId: Uuid?): JsonObject {
        if (tool == "app_status") return appStatus()
        if (tool.startsWith("automation_")) {
            // 自动化工具的隔离域来自当前绑定助手；没有绑定就是"未绑定"域，只碰同样未绑定时建的配方。
            return dispatchAutomation(tool, args, boundScope(gatesSource.current()))
        }
        val bound = assistantId ?: return error(
            "no_bound_assistant",
            "No assistant is bound to this endpoint, so chat/auto-file/task tools are disabled.",
        )
        return dispatchBound(tool, args, bound)
    }

    private suspend fun dispatchBound(tool: String, args: JsonObject, assistantId: Uuid): JsonObject {
        return when (tool) {
            "chat_list" -> chatList(args, assistantId)
            "chat_search" -> chatSearch(args, assistantId)
            "chat_read" -> chatRead(args, assistantId)
            "chat_send" -> chatSend(args, assistantId)
            "chat_rename" -> chatRename(args, assistantId)
            "chat_move_folder" -> chatMoveFolder(args, assistantId)
            "chat_stop" -> chatStop(args, assistantId)
            "compression_status" -> compressionStatus(args, assistantId)
            "compression_start" -> compressionStart(args, assistantId)
            "compression_cancel" -> compressionCancel(args, assistantId)
            "autofile_status" -> autofileStatus(assistantId)
            "autofile_history" -> autofileHistory(args, assistantId)
            "autofile_details" -> autofileDetails(args, assistantId)
            "autofile_run" -> autofileRun(args, assistantId)
            "autofile_cancel" -> autofileCancel(assistantId)
            "autofile_undo" -> autofileUndo(assistantId)
            "subagent_list" -> subagentList(args, assistantId)
            "subagent_result" -> subagentResult(args, assistantId)
            "subagent_cancel" -> subagentCancel(args, assistantId)
            else -> error("not_implemented", "Tool $tool is declared but has no implementation.")
        }
    }

    private suspend fun dispatchAutomation(tool: String, args: JsonObject, scope: String): JsonObject {
        return when (tool) {
            "automation_list" -> automationList(scope)
            "automation_recipe" -> automationRecipe(args, scope)
            "automation_history" -> automationHistory(args, scope)
            "automation_save" -> automationSave(args, scope)
            "automation_schedule" -> automationSchedule(args, scope)
            "automation_run" -> automationRun(args, scope)
            "automation_cancel" -> automationCancel(scope)
            else -> error("not_implemented", "Tool $tool is declared but has no implementation.")
        }
    }

    // ------------------------------------------------------------------ status

    private suspend fun appStatus(): JsonObject {
        val gates = gatesSource.current()
        val assistantName = boundAssistant(gates)?.let { id ->
            runCatching {
                settingsStore.awaitLoaded().assistants.firstOrNull { it.id == id }?.name
            }.getOrNull()
        }
        return buildJsonObject {
            put("ok", true)
            putNullable("bound_assistant", gates.boundAssistantId)
            putNullable("bound_assistant_name", assistantName)
            put("allow_read", gates.read)
            put("allow_mutations", gates.mutations)
            put("allow_model_requests", gates.modelRequests)
            put("allow_device", gates.grants(AppMcpGate.DEVICE))
            put("allow_automation", gates.grants(AppMcpGate.AUTOMATION))
            put("tools", AppMcpToolCatalog.visible(gates).size)
            put("pending_writes", ledger.snapshot().count { it.status == AppMcpLedgerStatus.PENDING.name })
        }
    }

    // -------------------------------------------------------------- chat: read

    private suspend fun chatList(args: JsonObject, assistantId: Uuid): JsonObject {
        val offset = AppMcpLimits.clampOffset(args.intField("offset"))
        val limit = AppMcpLimits.clampPage(args.intField("limit"))
        val page = if (args.booleanField("unfiled_only") == true) {
            conversations.getUnfiledConversationsOfAssistantPage(assistantId, offset, limit)
        } else {
            conversations.getConversationsOfAssistantPage(assistantId, offset, limit)
        }
        return buildJsonObject {
            put("ok", true)
            put("offset", offset)
            putJsonArray("items") {
                page.items.forEach { conversation ->
                    add(buildJsonObject {
                        put("conversation_id", conversation.id.toString())
                        put("title", conversation.title.take(AppMcpLimits.MAX_TITLE_CHARS))
                        put("pinned", conversation.isPinned)
                        put("updated_at", conversation.updateAt.toEpochMilli())
                        putNullable("folder_id", conversation.folderId?.toString())
                    })
                }
            }
            putNullable("next_offset", page.nextOffset)
        }
    }

    private suspend fun chatSearch(args: JsonObject, assistantId: Uuid): JsonObject {
        val query = args.stringField("query")?.trim().orEmpty()
        if (query.isEmpty() || query.length > 80) return error("bad_argument", "query must be 1..80 chars")
        val offset = AppMcpLimits.clampOffset(args.intField("offset"))
        val limit = AppMcpLimits.clampPage(args.intField("limit"))
        val page = conversations.searchConversationsOfAssistantPage(assistantId, query, offset, limit)
        return buildJsonObject {
            put("ok", true)
            put("offset", offset)
            putJsonArray("items") {
                page.items.forEach { conversation ->
                    add(buildJsonObject {
                        put("conversation_id", conversation.id.toString())
                        put("title", conversation.title.take(AppMcpLimits.MAX_TITLE_CHARS))
                        put("updated_at", conversation.updateAt.toEpochMilli())
                    })
                }
            }
            putNullable("next_offset", page.nextOffset)
        }
    }

    /**
     * 会话读取：条数与字数双上限。工具调用原文保留（不完整的也不要丢），但**只当文本**，绝不执行；
     * system 消息整体省略，避免把完整系统提示词/注入规则导出去。
     */
    private suspend fun chatRead(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) {
            return error("not_owned", "That conversation does not belong to the bound assistant.")
        }
        val limit = AppMcpLimits.clampPage(args.intField("limit"))
        val budget = AppMcpLimits.clampChars(args.intField("max_chars"))
        val tail = runCatching {
            conversations.readConversationTail(conversationId.toString(), limit)
        }.getOrElse { return error("read_failed", it.message ?: "conversation read failed") }

        val rendered = mutableListOf<JsonObject>()
        var used = 0
        var omittedSystem = 0
        var truncated = false
        for ((role, text) in tail) {
            if (role.equals("system", ignoreCase = true)) {
                omittedSystem++
                continue
            }
            val remaining = budget - used
            if (remaining <= 0) {
                truncated = true
                break
            }
            val capped = if (text.length > AppMcpLimits.MAX_MESSAGE_CHARS) {
                truncated = true
                text.take(AppMcpLimits.MAX_MESSAGE_CHARS)
            } else {
                text
            }
            val body = if (capped.length > remaining) {
                truncated = true
                capped.take(remaining)
            } else {
                capped
            }
            used += body.length
            rendered += buildJsonObject {
                put("role", role)
                put("text", body)
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("conversation_id", conversationId.toString())
            put("messages", JsonArray(rendered))
            put("chars_used", used)
            put("system_messages_omitted", omittedSystem)
            put("truncated", truncated)
            put("note", "Tool calls appear as plain text and are never executed by this endpoint.")
        }
    }

    // ------------------------------------------------------------- chat: write

    private suspend fun chatSend(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        val text = args.stringField("text")?.trim().orEmpty()
        if (text.isEmpty()) return error("bad_argument", "text is required")
        if (text.length > AppMcpLimits.MAX_SEND_CHARS) {
            return error("bad_argument", "text is longer than ${AppMcpLimits.MAX_SEND_CHARS} chars")
        }
        // 已存在的会话必须属于绑定助手；不存在的 id 视为这个助手的新草稿（仍绑定到同一助手）。
        if (conversations.existsConversationById(conversationId) && !ownsConversation(conversationId, assistantId)) {
            return error("not_owned", "That conversation does not belong to the bound assistant.")
        }
        val answer = args.booleanField("answer") ?: true
        // 从"读到绑定助手"到真正投递之间有一段等待（等写锁、等幂等账本落盘）：绑定/授权
        // 中途被改掉的话，绝不能拿旧助手身份继续发消息或写别的助手的数据。
        if (!stillBound(assistantId)) return scopeChangedError()
        if (conversations.existsConversationById(conversationId) && !ownsConversation(conversationId, assistantId)) {
            return error("not_owned", "That conversation does not belong to the bound assistant.")
        }
        val receipt = chatService().sendMessageWithReceipt(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(text)),
            answer = answer,
            assistantId = assistantId,
        ) ?: return error("bad_argument", "empty message")
        val accepted = withTimeoutOrNull(ENQUEUE_RECEIPT_TIMEOUT_MS) { receipt.await() }
        return when (accepted) {
            true -> buildJsonObject {
                put("ok", true)
                put("status", "queued")
                put("conversation_id", conversationId.toString())
                put("note", "Durably accepted by the message queue. Poll chat_read for the reply.")
            }

            false -> error("queue_rejected", "The message queue refused this message; nothing was sent.")

            // 已经交出去了，只是没等到回执：不许重发，让调用方去查真实状态。
            null -> buildJsonObject {
                put("ok", true)
                put("status", "unknown")
                put("conversation_id", conversationId.toString())
                put("note", "Enqueue receipt timed out after ${ENQUEUE_RECEIPT_TIMEOUT_MS}ms. Do NOT resend; " +
                    "check chat_read / the conversation state first.")
            }
        }
    }

    private suspend fun chatRename(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        val title = args.stringField("title")?.trim().orEmpty()
        if (title.isEmpty()) return error("bad_argument", "title is required")
        if (title.length > AppMcpLimits.MAX_TITLE_CHARS) {
            return error("bad_argument", "title is longer than ${AppMcpLimits.MAX_TITLE_CHARS} chars")
        }
        conversations.updateTitle(conversationId, title)
        return ok("conversation_id" to JsonPrimitive(conversationId.toString()), "title" to JsonPrimitive(title))
    }

    private suspend fun chatMoveFolder(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        val unfile = args.booleanField("unfile") == true
        val rawFolder = args.stringField("folder_id")?.trim().orEmpty()
        val target: Uuid? = when {
            unfile && rawFolder.isEmpty() -> null
            rawFolder.isEmpty() -> return error(
                "bad_argument",
                "folder_id is required (or unfile=true to remove it from its folder).",
            )

            else -> runCatching { Uuid.parse(rawFolder) }.getOrElse {
                return error("bad_argument", "folder_id is not a valid id")
            }
        }
        if (target != null) {
            val folder = runCatching { folders.getFolderById(target) }.getOrNull()
                ?: return error("folder_not_found", "That folder does not exist.")
            if (folder.assistantId != assistantId) {
                return error("not_owned", "That folder belongs to another assistant.")
            }
        }
        conversations.updateConversationFolderId(conversationId, target)
        return ok(
            "conversation_id" to JsonPrimitive(conversationId.toString()),
            "folder_id" to (target?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        )
    }

    private suspend fun chatStop(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        if (!stillBound(assistantId)) return scopeChangedError()
        chatService().stopGeneration(conversationId)
        return ok("conversation_id" to JsonPrimitive(conversationId.toString()), "status" to JsonPrimitive("stopped"))
    }

    // ------------------------------------------------------------- compression

    private suspend fun compressionStatus(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        val state = chatService().compressionStates.value[conversationId]
            ?: return ok(
                "conversation_id" to JsonPrimitive(conversationId.toString()),
                "status" to JsonPrimitive("idle"),
            )
        return buildJsonObject {
            put("ok", true)
            put("conversation_id", conversationId.toString())
            put("status", if (state.isRunning) "running" else "finished")
            put("phase", state.phase.name.lowercase())
            putNullable("outcome", state.outcome?.name?.lowercase())
            put("generated_chars", state.generatedChars)
            put("chunks", state.chunks)
            put("keep_recent_messages", state.keepRecentMessages)
            put("started_at", state.startedAt)
            putNullable("finished_at", state.finishedAt)
            put("cancellable", state.cancellable)
            putNullable("message", state.message)
        }
    }

    private suspend fun compressionStart(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        val prompt = args.stringField("additional_prompt")?.trim().orEmpty()
        if (prompt.length > AppMcpLimits.MAX_PROMPT_CHARS) {
            return error("bad_argument", "additional_prompt is too long")
        }
        val keepRecent = (args.intField("keep_recent_messages") ?: 0).coerceIn(0, 200)
        if (!stillBound(assistantId)) return scopeChangedError()
        val alreadyRunning = chatService().compressionStates.value[conversationId]?.isRunning == true
        chatService().startBackgroundCompression(conversationId, prompt, keepRecent)
        return buildJsonObject {
            put("ok", true)
            put("conversation_id", conversationId.toString())
            put("status", if (alreadyRunning) "already_running" else "started")
            put("note", "Poll compression_status for real progress.")
        }
    }

    private suspend fun compressionCancel(args: JsonObject, assistantId: Uuid): JsonObject {
        val conversationId = args.requireConversationId() ?: return error("bad_argument", "conversation_id is required")
        if (!ownsConversation(conversationId, assistantId)) return error("not_owned", "Not this assistant's conversation.")
        val cancelled = chatService().cancelBackgroundCompression(conversationId)
        return ok(
            "conversation_id" to JsonPrimitive(conversationId.toString()),
            "status" to JsonPrimitive(if (cancelled) "cancelled" else "not_running"),
        )
    }

    // --------------------------------------------------------------- autofile

    private suspend fun autofileStatus(assistantId: Uuid): JsonObject {
        val facet = autoFileFacade().status(assistantId)
        return runCatching { facet.toJson() }.getOrElse { error("autofile_unavailable", it.message ?: "autofile failed") }
    }

    private suspend fun autofileHistory(args: JsonObject, assistantId: Uuid): JsonObject {
        val limit = (args.intField("limit") ?: 5).coerceIn(1, 10)
        val batches = autoFileFacade().history(assistantId, limit)
        return buildJsonObject {
            put("ok", true)
            putJsonArray("batches") {
                batches.forEach { batch ->
                    add(buildJsonObject {
                        put("batch_id", batch.id)
                        put("created_at", batch.createdAt)
                        putNullable("finished_at", batch.finishedAt)
                        put("mode", batch.mode)
                        put("status", batch.status)
                        put("total", batch.total)
                        put("moved", batch.moved)
                        put("failed", batch.failed)
                        put("skipped", batch.skipped)
                        put("undone", batch.undone)
                        put("can_undo", batch.canUndo)
                    })
                }
            }
        }
    }

    private suspend fun autofileDetails(args: JsonObject, assistantId: Uuid): JsonObject {
        val batchId = args.stringField("batch_id")?.trim().orEmpty()
        if (batchId.isEmpty()) return error("bad_argument", "batch_id is required")
        val detail = autoFileFacade().details(assistantId, batchId)
            ?: return error("not_found", "No such batch for the bound assistant.")
        return buildJsonObject {
            put("ok", true)
            put("batch_id", detail.batch.id)
            put("status", detail.batch.status)
            put("mode", detail.batch.mode)
            putJsonArray("moves") {
                detail.moves.forEach { move ->
                    add(buildJsonObject {
                        put("conversation_id", move.conversationId)
                        putNullable("title", move.title)
                        putNullable("folder_name", move.folderName)
                        put("status", move.status)
                        putNullable("reason", move.reason)
                        putNullable("error", move.error)
                    })
                }
            }
        }
    }

    /**
     * 归档立即返回：真正的批次交给 WorkManager 唯一一次性任务跑，避免 HTTP 连接一断就把
     * "已经开始的整理"当成失败再触发一次。
     *
     * 外发同意不由 `manual` 这个 flag 绕过：AI 模式且自动开关关着时，仍然必须先带
     * `confirm_external_model_usage=true`，否则直接拒绝、不入队。
     */
    private suspend fun autofileRun(args: JsonObject, assistantId: Uuid): JsonObject {
        val facet = autoFileFacade()
        val status = facet.status(assistantId)
        val needsConfirmation = status.mode == AutoFileMode.AI.name.lowercase() && !status.enabled
        val confirmed = args.booleanField("confirm_external_model_usage") == true
        if (needsConfirmation && !confirmed) {
            return error(
                "confirmation_required",
                "Auto-file is off in AI mode. Turning it on sends a bounded summary to the fast model and spends " +
                    "quota; pass confirm_external_model_usage=true to accept that.",
            )
        }
        if (needsConfirmation) {
            facet.configure(
                assistantId = assistantId,
                enabled = true,
                mode = AutoFileMode.AI,
                confirmExternalModelUsage = true,
            )
        }
        // 手动任务带 KEY_MANUAL=true，Worker 走 organizeNow：自动开关关着也真的执行，
        // 不再像以前那样复用周期入口、变成 no-op 还回 ok。
        val work = try {
            AppAutoFileOnce.enqueue(appContext, assistantId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 排不上就说排不上，并把原因写进可查的状态，不假装成功。
            return error("work_not_scheduled", failure.message ?: "autofile run could not be scheduled")
        }
        return buildJsonObject {
            put("ok", true)
            put(
                "status",
                when {
                    status.running -> "already_running"
                    work.enqueued -> "queued"
                    // 同名任务还在队列/运行中（KEEP）：这次没有新入队，别报成 queued。
                    else -> "already_queued"
                },
            )
            put("work_name", work.workName)
            putNullable("work_id", work.workId)
            put("work_state", work.state)
            put("queued", work.enqueued)
            put("note", "Runs in the background; poll autofile_status. Pinned/manual/locked items stay untouched.")
        }
    }

    private suspend fun autofileCancel(assistantId: Uuid): JsonObject =
        runCatching { autoFileFacade().cancel(assistantId).toJson() }
            .getOrElse { error("autofile_unavailable", it.message ?: "autofile cancel failed") }

    private suspend fun autofileUndo(assistantId: Uuid): JsonObject =
        runCatching { autoFileFacade().undo(assistantId).toJson() }
            .getOrElse { error("autofile_unavailable", it.message ?: "autofile undo failed") }

    // --------------------------------------------------------------- subagents

    private suspend fun subagentList(args: JsonObject, assistantId: Uuid): JsonObject {
        val limit = AppMcpLimits.clampPage(args.intField("limit"))
        val runner = subagentRunner()
        val tasks = try {
            runner.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            emptyList()
        }
        val owned = ownedConversationIds(tasks.mapNotNull { it.conversationId }, assistantId)
        val visible = tasks
            .filter { task -> task.conversationId?.let { owned.contains(it.toString()) } == true }
            .take(limit)
        return buildJsonObject {
            put("ok", true)
            putJsonArray("tasks") {
                visible.forEach { task ->
                    add(buildJsonObject {
                        put("task_id", task.id)
                        put("conversation_id", task.conversationId.toString())
                        put("status", task.status.name.lowercase())
                        put("created_at", task.createdAt)
                        putNullable("finished_at", task.finishedAt)
                        put("steps", task.steps)
                        put("attempts", task.attempts)
                        putNullable("error", task.error)
                        put("result_chars", task.result?.length ?: 0)
                    })
                }
            }
        }
    }

    private suspend fun subagentResult(args: JsonObject, assistantId: Uuid): JsonObject {
        val taskId = args.stringField("task_id")?.trim().orEmpty()
        if (taskId.isEmpty()) return error("bad_argument", "task_id is required")
        val task = subagentRunner().get(taskId) ?: return error("not_found", "No such task.")
        // 必须按任务所属会话再查一次归属：任务本身不带 assistantId，不能混其他助手的任务。
        val owner = task.conversationId?.toString()
        if (owner == null || !ownsConversation(task.conversationId, assistantId)) {
            return error("not_owned", "That task does not belong to the bound assistant.")
        }
        val offset = AppMcpLimits.clampOffset(args.intField("offset"))
        val limit = AppMcpLimits.clampChars(args.intField("limit"))
        val text = task.result ?: ""
        val slice = if (offset >= text.length) "" else text.substring(offset, minOf(text.length, offset + limit))
        return buildJsonObject {
            put("ok", true)
            put("task_id", task.id)
            put("status", task.status.name.lowercase())
            put("offset", offset)
            put("limit", limit)
            put("returned_chars", slice.length)
            put("total_chars", text.length)
            put("has_more", offset + slice.length < text.length)
            putNullable("next_offset", if (offset + slice.length < text.length) offset + slice.length else null)
            put("text", slice)
            putNullable("error", task.error)
        }
    }

    private suspend fun subagentCancel(args: JsonObject, assistantId: Uuid): JsonObject {
        val taskId = args.stringField("task_id")?.trim().orEmpty()
        if (taskId.isEmpty()) return error("bad_argument", "task_id is required")
        val task = subagentRunner().get(taskId) ?: return error("not_found", "No such task.")
        if (task.conversationId == null || !ownsConversation(task.conversationId, assistantId)) {
            return error("not_owned", "That task does not belong to the bound assistant.")
        }
        val cancelled = subagentRunner().cancel(taskId)
        return ok(
            "task_id" to JsonPrimitive(taskId),
            "status" to JsonPrimitive(if (cancelled) "cancelled" else "not_running"),
        )
    }

    // -------------------------------------------------------------- automation

    private fun automationList(scope: String): JsonObject {
        val controller = automationController()
        val visibleIds = visibleRecipeIds(scope)
        val recipes = controller.recipes.value.filter { it.id in visibleIds }
        val active = controller.activeRun.value?.takeIf { it.recipeId in visibleIds }
        return buildJsonObject {
            put("ok", true)
            putJsonArray("recipes") { recipes.forEach { add(it.toJson()) } }
            put("active_run", active?.let { run ->
                buildJsonObject {
                    put("run_id", run.id)
                    put("recipe_id", run.recipeId)
                    put("status", run.status.name.lowercase())
                    put("started_at", run.startedAt)
                }
            } ?: JsonNull)
        }
    }

    private fun automationRecipe(args: JsonObject, scope: String): JsonObject {
        val recipeId = args.stringField("recipe_id")?.trim().orEmpty()
        if (recipeId.isEmpty()) return error("bad_argument", "recipe_id is required")
        // 归属不符按"不存在"处理：不暴露别的助手有哪些配方。
        val recipe = recipeOrNull(recipeId, scope) ?: return error("not_found", "No such recipe.")
        return buildJsonObject {
            put("ok", true)
            put("recipe", recipe.toJson())
        }
    }

    private fun automationHistory(args: JsonObject, scope: String): JsonObject {
        val limit = (args.intField("limit") ?: 10).coerceIn(1, AppMcpLimits.MAX_HISTORY)
        val visibleIds = visibleRecipeIds(scope)
        // 历史同样按归属过滤：换绑助手之后看不到上一任助手的运行记录与步骤细节。
        val runs = automationController().runs.value.filter { it.recipeId in visibleIds }.take(limit)
        return buildJsonObject {
            put("ok", true)
            putJsonArray("runs") {
                runs.forEach { run ->
                    add(buildJsonObject {
                        put("run_id", run.id)
                        put("recipe_id", run.recipeId)
                        put("recipe_name", run.recipeName)
                        put("trigger", run.trigger.name.lowercase())
                        put("status", run.status.name.lowercase())
                        put("started_at", run.startedAt)
                        putNullable("finished_at", run.finishedAt)
                        put("message", run.message)
                        putJsonArray("steps") {
                            run.steps.forEach { step ->
                                add(buildJsonObject {
                                    put("index", step.index)
                                    put("tool", step.tool)
                                    put("status", step.status.name.lowercase())
                                    put("side_effect", step.sideEffect)
                                    put("detail", step.detail.take(400))
                                })
                            }
                        }
                    })
                }
            }
        }
    }

    private suspend fun automationSave(args: JsonObject, scope: String): JsonObject {
        val controller = automationController()
        val name = args.stringField("name")?.trim().orEmpty()
        if (name.isEmpty()) return error("bad_argument", "name is required")
        val stepsJson = args["steps"] as? JsonArray
            ?: return error("bad_argument", "steps must be an array")
        if (stepsJson.isEmpty()) return error("bad_argument", "steps must not be empty")
        if (stepsJson.size > AppMcpLimits.MAX_RECIPE_STEPS) {
            return error("bad_argument", "too many steps (max ${AppMcpLimits.MAX_RECIPE_STEPS})")
        }
        val steps = mutableListOf<AutomationStep>()
        for (element in stepsJson) {
            val obj = element as? JsonObject ?: return error("bad_argument", "each step must be an object")
            val toolName = obj.stringField("tool")?.trim().orEmpty()
            if (toolName.isEmpty()) return error("bad_argument", "each step needs a tool name")
            val stepArgs = when (val raw = obj["arguments"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> return error("bad_argument", "step arguments must be an object")
            }
            steps += AutomationStep(
                tool = toolName,
                arguments = stepArgs,
                note = obj.stringField("note")?.take(200) ?: "",
            )
        }
        val existingId = args.stringField("recipe_id")?.trim().orEmpty()
        val existing = existingId.takeIf { it.isNotEmpty() }?.let { id ->
            controller.recipes.value.firstOrNull { it.id == id } ?: return error("not_found", "No such recipe.")
        }
        if (existing != null && !recipeOwners.isOwnedBy(existing.id, scope)) {
            return error("not_found", "No such recipe.")
        }
        val base = existing ?: controller.newRecipe()
        val recipe = base.copy(
            name = name,
            steps = steps,
            intervalMinutes = args.intField("interval_minutes") ?: base.intervalMinutes,
            enabled = args.booleanField("enabled") ?: base.enabled,
        )
        // 先落归属再落配方：归属写不进去就不建配方——否则换助手后这条配方要么跑不了、
        // 要么（更糟）落到"未知归属"里，谁都能看见。
        if (!recipeOwners.assign(recipe.id, scope)) {
            return error(
                "ownership_not_persisted",
                "The recipe was not saved: its owner could not be persisted, so it could not be isolated " +
                    "per assistant afterwards.",
            )
        }
        return when (val saved = controller.save(recipe)) {
            is AutomationSaveResult.Saved -> buildJsonObject {
                put("ok", true)
                put("recipe", saved.recipe.toJson())
            }

            is AutomationSaveResult.Invalid -> error(
                "invalid_recipe",
                saved.issues.joinToString("; ") { issue ->
                    "${issue.code.name.lowercase()}${issue.stepIndex?.let { " @$it" } ?: ""}: ${issue.message}"
                },
            )
        }
    }

    private suspend fun automationSchedule(args: JsonObject, scope: String): JsonObject {
        val recipeId = args.stringField("recipe_id")?.trim().orEmpty()
        if (recipeId.isEmpty()) return error("bad_argument", "recipe_id is required")
        val enabled = args.booleanField("enabled") ?: return error("bad_argument", "enabled is required")
        val controller = automationController()
        val recipe = recipeOrNull(recipeId, scope) ?: return error("not_found", "No such recipe.")
        if (enabled && recipe.intervalMinutes < 15) {
            return error("interval_too_short", "Set interval_minutes >= 15 before enabling a schedule.")
        }
        // 排不上就是没排上：WorkManager 拒绝时明确回错误，不谎报 scheduled=true
        val outcome = controller.setScheduled(recipeId, enabled)
        if (!outcome.ok) {
            return error("schedule_failed", outcome.failure ?: "WorkManager rejected the schedule; nothing was scheduled.")
        }
        return ok(
            "recipe_id" to JsonPrimitive(recipeId),
            "scheduled" to JsonPrimitive(outcome.enabled),
            "work_state" to JsonPrimitive(outcome.receipt?.state ?: "CANCELLED"),
        )
    }

    /** 立刻跑一次：交给 WorkManager 一次性任务（KEEP，不打断正在跑的那次），拿到真实回执才回 ok。 */
    private suspend fun automationRun(args: JsonObject, scope: String): JsonObject {
        val recipeId = args.stringField("recipe_id")?.trim().orEmpty()
        if (recipeId.isEmpty()) return error("bad_argument", "recipe_id is required")
        // 换绑助手后不能跑"上一个助手留下的配方"：归属不符就是不存在。
        val recipe = recipeOrNull(recipeId, scope) ?: return error("not_found", "No such recipe.")
        val receipt = try {
            AutomationScheduler(appContext).runOnce(recipe.id, manual = true)
        } catch (failure: me.rerere.rikkahub.automation.AutomationScheduleException) {
            return error("schedule_failed", failure.message ?: "WorkManager rejected the run; nothing was queued.")
        }
        return buildJsonObject {
            put("ok", true)
            put("status", "queued")
            // 真实的 WorkManager 回执：work id 来自 WorkInfo，state 是排完之后查到的状态
            put("work_id", receipt.workIds.firstOrNull() ?: receipt.workName)
            put("work_name", receipt.workName)
            put("work_state", receipt.state)
            put("trigger", "manual")
            put("note", "Accepted by WorkManager (${receipt.state}); poll automation_history. Write steps are never replayed.")
        }
    }

    private fun automationCancel(scope: String): JsonObject {
        val controller = automationController()
        val active = controller.activeRun.value
        if (active != null && !recipeOwners.isOwnedBy(active.recipeId, scope)) {
            return error("not_found", "No such recipe.")
        }
        controller.cancelNow()
        return ok("status" to JsonPrimitive("cancel_requested"))
    }

    // ------------------------------------------------------------------ helpers

    private fun boundAssistant(gates: AppMcpGates): Uuid? =
        gates.boundAssistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    /** 幂等/自动化归属域：绑定助手 id，没有绑定就是"未绑定"域。 */
    private fun boundScope(gates: AppMcpGates): String =
        gates.boundAssistantId?.trim()?.takeIf { it.isNotEmpty() } ?: AppMcpRecipeOwnership.UNBOUND_SCOPE

    /** 这一瞬间的绑定助手是否还是调用开始时那个；绑定变了就不许继续写。 */
    private fun stillBound(assistantId: Uuid): Boolean = boundAssistant(gatesSource.current()) == assistantId

    private fun scopeChangedError(): JsonObject = error(
        "bound_assistant_changed",
        "The bound assistant (or its authorisation) changed while this call was in flight; nothing was " +
            "executed for the old binding. Re-check app_status and retry under the current one.",
    )

    /** 当前归属域能看见的配方 id：没有归属记录的（本地 UI 建的 / 升级前建的）一律不暴露。 */
    private fun visibleRecipeIds(scope: String): Set<String> =
        automationController().recipes.value
            .mapNotNull { recipe -> recipe.id.takeIf { recipeOwners.isOwnedBy(it, scope) } }
            .toSet()

    private fun recipeOrNull(recipeId: String, scope: String): AutomationRecipe? =
        automationController().recipes.value.firstOrNull { it.id == recipeId }
            ?.takeIf { recipeOwners.isOwnedBy(it.id, scope) }

    private suspend fun ownsConversation(conversationId: Uuid?, assistantId: Uuid): Boolean {
        if (conversationId == null) return false
        val owner = runCatching { conversations.getConversationAssistantId(conversationId.toString()) }.getOrNull()
        return owner != null && owner == assistantId.toString()
    }

    private suspend fun ownedConversationIds(ids: List<Uuid>, assistantId: Uuid): Set<String> {
        val result = mutableSetOf<String>()
        for (id in ids.distinct()) {
            if (ownsConversation(id, assistantId)) result += id.toString()
        }
        return result
    }

    private fun deviceDescriptor(name: String): me.rerere.rikkahub.data.devicecontrol.DeviceToolDescriptor? =
        runCatching { DeviceToolCatalog.descriptors().firstOrNull { it.name == name } }.getOrNull()

    private fun notGranted(spec: AppMcpToolSpec): JsonObject = error(
        "not_granted",
        "${spec.name} is disabled in the local MCP settings (${spec.gates.joinToString(",") { it.name.lowercase() }}).",
    )

    private fun ok(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject {
            put("ok", true)
            pairs.forEach { (key, value) -> put(key, value) }
        }

    internal fun error(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }

    private fun AutomationRecipe.toJson(): JsonObject = buildJsonObject {
        put("recipe_id", id)
        put("name", name)
        put("interval_minutes", intervalMinutes)
        put("enabled", enabled)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        putJsonArray("steps") {
            steps.forEach { step ->
                add(buildJsonObject {
                    put("tool", step.tool)
                    put("arguments", step.arguments)
                    put("note", step.note)
                })
            }
        }
    }

    private fun me.rerere.rikkahub.data.organization.autofile.AutoFileFacadeStatus.toJson(): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("assistant_id", assistantId)
            put("enabled", enabled)
            put("mode", mode)
            put("idle_hours", idleHours)
            put("running", running)
            put("moved", moved)
            put("can_undo", canUndo)
            putNullable("error", error)
        }

    /** Kotlin 的 `Long? ?: JsonNull` 会推成 Any，匹配不到 put 重载，这里统一收口。 */
    private fun JsonObjectBuilder.putNullable(key: String, value: Any?) {
        when (value) {
            null -> put(key, JsonNull)
            is JsonElement -> put(key, value)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanField(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.requireConversationId(): Uuid? {
        val raw = stringField("conversation_id")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Uuid.parse(raw) }.getOrNull()
    }

    companion object {
        /** 入队回执的最长等待；超时不当失败（消息可能已经进队列）。 */
        const val ENQUEUE_RECEIPT_TIMEOUT_MS = 5_000L

        /** 设备工具的幂等域：设备和助手无关，单独一域，避免和助手域互相回放。 */
        const val DEVICE_SCOPE = "device"
    }
}

/**
 * 自动归档的**手动**一次性后台任务。
 *
 * 和周期任务用同一个 Worker 类（内部会重读设置、有批次锁），但：
 * - 用固定的 manual 唯一名字（每助手一个），`ExistingWorkPolicy.KEEP` 去重；
 * - 入参带 `KEY_MANUAL=true`，Worker 走 organizeNow —— 自动开关关着也真实执行；
 * - 返回的是查询到的**真实** work id/状态；同名任务已在队列里时 `enqueued=false`，
 *   不会回一个没入队的新 uuid 冒充 taskId。
 */
object AppAutoFileOnce {
    suspend fun enqueue(context: Context, assistantId: Uuid): AutoFileManualWork =
        ConversationAutoFileWork.enqueueManual(context.applicationContext ?: context, assistantId)
}

/**
 * 一条"这个配方属于哪个归属域"的记录。归属域 = 建配方时的绑定助手 id，或 [AppMcpRecipeOwnership.UNBOUND_SCOPE]。
 */
@Serializable
data class AppMcpRecipeOwner(val recipeId: String, val scope: String = "")

@Serializable
private data class AppMcpRecipeOwnerFile(val owners: List<AppMcpRecipeOwner> = emptyList())

/**
 * 本地自动化配方的**归属域**（纯 JVM，只用 java.io.File，可单测）。
 *
 * 为什么需要它：`AutomationRecipe` 在 automation 模块里、没有 assistantId 字段（那个模块的契约不动），
 * 所以"这条配方是哪个助手建的"只能记在 MCP 这一侧。没有这一层时，MCP 建的配方是全局可见的：
 * 用户把绑定助手从 A 换成 B，B 的客户端照样能列出/运行 A 留下的配方（跨助手执行）。
 *
 * 规则：
 * - MCP 建/改配方时记下当时的归属域；换助手后归属不符 → 当作"不存在"（不暴露存在性）；
 * - **没有归属记录**的配方（用户在本地 UI 建的、升级前建的）不通过 MCP 暴露，保守地只留在本地 UI；
 * - 归属写盘失败就拒绝建配方（fail closed）：写不进去的归属等于没有隔离。
 */
class AppMcpRecipeOwnership(private val file: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val owners = mutableMapOf<String, String>()

    fun ownerOf(recipeId: String): String? = synchronized(lock) {
        ensureLoaded()
        owners[recipeId]
    }

    fun isOwnedBy(recipeId: String, scope: String): Boolean = synchronized(lock) {
        ensureLoaded()
        owners[recipeId] == scope
    }

    /** @return 是否落盘成功；调用方在失败时必须放弃建配方。 */
    fun assign(recipeId: String, scope: String): Boolean = synchronized(lock) {
        ensureLoaded()
        owners[recipeId] = scope
        persist()
    }

    fun forget(recipeId: String) = synchronized(lock) {
        ensureLoaded()
        if (owners.remove(recipeId) != null) persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        owners.clear()
        val text = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return
        val decoded = runCatching { json.decodeFromString(AppMcpRecipeOwnerFile.serializer(), text) }
            .getOrNull() ?: return
        decoded.owners.forEach { owner -> owners[owner.recipeId] = owner.scope }
    }

    private fun persist(): Boolean = runCatching {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val text = json.encodeToString(
            AppMcpRecipeOwnerFile.serializer(),
            AppMcpRecipeOwnerFile(owners.map { (recipeId, scope) -> AppMcpRecipeOwner(recipeId, scope) }),
        )
        // 和幂等账本一样用 tmp + rename，避免半截 JSON 把整份归属表作废。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        true
    }.getOrElse { false }

    companion object {
        /** 没有绑定助手时的归属域：只有同样"未绑定"状态下建的配方才可见。 */
        const val UNBOUND_SCOPE = ""
    }
}
