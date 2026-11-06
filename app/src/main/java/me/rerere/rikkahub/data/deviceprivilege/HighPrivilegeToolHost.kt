package me.rerere.rikkahub.data.deviceprivilege

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.data.mcpserver.AppMcpIdemDecision
import me.rerere.rikkahub.data.mcpserver.AppMcpIdempotencyLedger

class HighPrivilegeToolHost(
    private val controller: HighPrivilegeController,
    private val ledger: AppMcpIdempotencyLedger,
) : HighPrivilegeMcpProxy {

    private val writeMutex = Mutex()

    override fun handles(name: String): Boolean = name in HighPrivilegeTooling.all

    override fun descriptorOrNull(name: String): HighPrivilegeToolDescriptor? =
        if (handles(name)) descriptor(name) else null

    /** 与传输无关，但和 [automationCatalog] 走**同一道**门禁（控制器 `checkAutomation`）。 */
    override suspend fun exposedTools(assistantId: String): List<HighPrivilegeToolDescriptor> =
        automationCatalog(assistantId)

    /**
     * 非 suspend 版本的门禁：同一条代码路径，只是不查 Shizuku（`requireShizuku = false`）。
     * 未开启 / 未绑定 / 绑的是别的助手 → 空列表；控制器自己抛任何异常也当"现在不可用"（fail-closed）。
     */
    override fun automationCatalog(boundAssistantId: String?): List<HighPrivilegeToolDescriptor> {
        val owner = boundAssistantId?.trim().orEmpty()
        if (owner.isEmpty()) return emptyList()
        return try {
            controller.checkAutomation(owner)
            descriptors()
        } catch (denied: HighPrivilegeDeniedException) {
            emptyList()
        } catch (failure: Exception) {
            emptyList()
        }
    }

    /** 与传输无关的工具目录（本地助手工具用；调用方负责确认 opt-in）。 */
    fun descriptors(): List<HighPrivilegeToolDescriptor> = HighPrivilegeTooling.all.map { name -> descriptor(name) }

    private fun descriptor(name: String): HighPrivilegeToolDescriptor = HighPrivilegeToolDescriptor(
        name = name,
        description = HighPrivilegeTooling.description(name),
        inputSchema = HighPrivilegeTooling.schema(name),
        // 只读 / 审批标记固定由 HighPrivilegeTooling 决定，不接受调用方覆盖：
        // 写（settings_put / shell）与日志（diagnostics）永远 needsApproval。
        readOnly = !HighPrivilegeTooling.isWrite(name),
        needsApproval = HighPrivilegeTooling.isSensitive(name),
    )

    /** 本地助手和 MCP 使用同一账本，重连后复用同一 key 只取回结果，不重放设备写操作。 */
    suspend fun callLocal(assistantId: String, name: String, arguments: JsonObject): JsonObject =
        callAsBoundAssistant(assistantId, name, arguments)

    override suspend fun callAsBoundAssistant(
        assistantId: String,
        name: String,
        arguments: JsonObject,
    ): JsonObject {
        if (!handles(name)) return errorJson("unknown_tool", "No such high-privilege tool: $name")
        denyReason(assistantId, name)?.let { return errorJson("not_granted", it) }
        if (!HighPrivilegeTooling.isWrite(name)) return runSafely { dispatch(assistantId, name, arguments) }
        return withLedger(assistantId, name, arguments) { dispatch(assistantId, name, arguments) }
    }

    // ------------------------------------------------------------------ gate

    /**
     * 唯一门禁。**不再**自己拼一份 snapshot 判断，直接调控制器的
     * [HighPrivilegeController.checkAutomation]，让「权限中心 UI 显示的授权」和
     * 「工具层实际执行的授权」是**同一条**代码路径：
     *
     * 1. 自动化总开关（默认关）；
     * 2. 绑定助手**完全相等**（null ≠ 全部助手）；
     * 3. 写工具（settings_put / shell）再要独立的写开关；
     * 4. 需要 Shizuku 的工具（shell / diagnostics）再要现查 `status=READY`；
     *    其余工具的 Shizuku / app-granted 取舍交回控制器内部（见 [HighPrivilegeTooling.needsShizuku]）。
     *
     * 非 suspend：控制器这一层只读内存里的持久化开关 + 现查 `shizukuStatus()`，
     * 不弹框、不提权、不缓存、不重试。
     */
    private fun denyReason(assistantId: String, name: String): String? = try {
        controller.checkAutomation(
            assistantId = assistantId,
            requiresWrite = HighPrivilegeTooling.isWrite(name),
            requireShizuku = HighPrivilegeTooling.needsShizuku(name),
        )
        null
    } catch (denied: HighPrivilegeDeniedException) {
        denied.message ?: "Denied by the on-device privilege center. Nothing was executed."
    } catch (failure: Exception) {
        "The on-device privilege center is unavailable right now " +
            "(${failure.message ?: failure::class.simpleName}). Nothing was executed."
    }

    // ------------------------------------------------------------------ ledger

    /**
     * 写路径：同一个 key + 同一份参数只回放结果，绝不再执行一次。
     * 中断/异常一律记成"副作用未知"，调用方不该重试同一个 key。
     */
    private suspend fun withLedger(
        assistantId: String,
        name: String,
        arguments: JsonObject,
        block: suspend () -> JsonObject,
    ): JsonObject = writeMutex.withLock {
        val key = arguments.stringOrNull(HighPrivilegeTooling.IDEMPOTENCY_KEY_FIELD)?.trim().orEmpty()
        if (key.isEmpty()) {
            return@withLock errorJson(
                "idempotency_key_required",
                "$name changes device state; pass idempotency_key and reuse it only when retrying the same call.",
            )
        }
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(
            JsonObject(arguments.filterKeys { it != HighPrivilegeTooling.IDEMPOTENCY_KEY_FIELD }),
        )
        when (val decision = ledger.decide(name, key, fingerprint, assistantId)) {
            is AppMcpIdemDecision.Replay -> return@withLock runCatching {
                Json.parseToJsonElement(decision.resultJson) as JsonObject
            }.getOrElse {
                errorJson("previous_result_unreadable", "This key already ran; its recorded result is unreadable.")
            }

            is AppMcpIdemDecision.Rejected -> return@withLock errorJson(decision.code, decision.message)

            is AppMcpIdemDecision.Fresh -> Unit
        }
        // 等锁 / 等账本落盘期间用户可能已经收权：执行前再核一次真实状态。
        denyReason(assistantId, name)?.let { return@withLock errorJson("not_granted", it) }
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            ledger.fail(name, key, "cancelled while running; side effect unknown", assistantId)
            throw cancelled
        } catch (fatal: Error) {
            ledger.fail(name, key, "fatal ${fatal::class.simpleName}; side effect unknown", assistantId)
            throw fatal
        } catch (failure: Exception) {
            ledger.fail(name, key, failure.message ?: failure::class.simpleName.orEmpty(), assistantId)
            return@withLock errorJson(
                "write_outcome_unknown",
                "The write may or may not have happened (${failure.message ?: failure::class.simpleName}). " +
                    "Do not retry with the same key; inspect the actual state first.",
            )
        }
        ledger.complete(name, key, result.toString(), assistantId)
        result
    }

    // -------------------------------------------------------------- dispatch

    private suspend fun dispatch(assistantId: String, name: String, arguments: JsonObject): JsonObject =
        when (name) {
            HighPrivilegeTooling.STATUS -> status()
            HighPrivilegeTooling.SETTINGS_GET -> settingsGet(arguments)
            HighPrivilegeTooling.SETTINGS_PUT -> settingsPut(assistantId, arguments)
            HighPrivilegeTooling.PACKAGES -> packages(arguments)
            HighPrivilegeTooling.DIAGNOSTICS -> diagnostics(assistantId, arguments)
            HighPrivilegeTooling.SHELL -> shell(assistantId, arguments)
            else -> errorJson("unknown_tool", "No such high-privilege tool: $name")
        }

    private suspend fun status(): JsonObject {
        val snapshot = controller.snapshot()
        return buildJsonObject {
            put("ok", true)
            put("source", "on-device privilege center (Shizuku + system settings); this call granted nothing")
            put("app_package", snapshot.appPackage)
            put("app_uid", snapshot.appUid)
            put("shizuku_status", snapshot.shizukuStatus)
            put("shizuku_ready", snapshot.shizukuReady)
            put("automation_enabled", snapshot.automationEnabled)
            put("write_enabled", snapshot.writeEnabled)
            put("bound_assistant", snapshot.authorizedAssistantId != null)
            putNullable("authorized_uid", snapshot.authorizedUid)
            putJsonArray("permissions") {
                snapshot.permissions.forEach { row ->
                    addJsonObject {
                        put("id", row.id)
                        put("state", row.state.name)
                        put("grant_route", row.grantRoute.name)
                    }
                }
            }
            putJsonArray("notes") { snapshot.notes.forEach { add(it) } }
        }
    }

    private suspend fun settingsGet(arguments: JsonObject): JsonObject {
        val namespace = arguments.stringOrNull("namespace")
            ?: return errorJson("bad_argument", "namespace is required: system | secure | global")
        val key = arguments.stringOrNull("key") ?: return errorJson("bad_argument", "key is required")
        val includeSensitive = arguments.booleanOrNull(HighPrivilegeTooling.INCLUDE_SENSITIVE_FIELD) == true
        val read = controller.settingsRead(namespace, key)
        val redact = read.looksSecret && !includeSensitive
        return buildJsonObject {
            put("ok", read.error == null)
            put("namespace", read.namespace)
            put("key", read.key)
            put("via", read.via)
            put("looks_secret", read.looksSecret)
            put("value_redacted", redact)
            putNullable("value", if (redact) null else read.value)
            putNullable("exit_code", read.exitCode)
            putNullable("error", read.error)
        }
    }

    private suspend fun settingsPut(assistantId: String, arguments: JsonObject): JsonObject {
        val namespace = arguments.stringOrNull("namespace")
            ?: return errorJson("bad_argument", "namespace is required: system | secure | global")
        val key = arguments.stringOrNull("key") ?: return errorJson("bad_argument", "key is required")
        val includeSensitive = arguments.booleanOrNull(HighPrivilegeTooling.INCLUDE_SENSITIVE_FIELD) == true
        val value = when (val element = arguments["value"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> return errorJson("bad_argument", "value must be a string (or null to delete)")
        }
        val write = controller.settingsWrite(assistantId, namespace, key, value)
        val redact = write.looksSecret && !includeSensitive
        return buildJsonObject {
            // ok 只跟真实回读一致：命令退出 0 但值没变，就是 false。
            put("ok", write.verified)
            put("namespace", write.namespace)
            put("key", write.key)
            put("via", write.via)
            put("verified", write.verified)
            put("deleted", value == null)
            put("looks_secret", write.looksSecret)
            put("values_redacted", redact)
            putNullable("old_value", if (redact) null else write.oldValue)
            putNullable("new_value", if (redact) null else write.newValue)
            putNullable("exit_code", write.exitCode)
            putNullable("error", write.error)
            put("attempts", 1)
        }
    }

    private suspend fun packages(arguments: JsonObject): JsonObject {
        val includeSystem = arguments.booleanOrNull("include_system") == true
        val limit = (arguments.intOrNull("limit") ?: 300).coerceIn(1, 1000)
        val rows = controller.listInstalledPackages(includeSystem, limit)
        return buildJsonObject {
            put("ok", true)
            put("include_system", includeSystem)
            put("limit", limit)
            put("count", rows.size)
            putJsonArray("packages") {
                rows.forEach { row ->
                    addJsonObject {
                        put("package", row.packageName)
                        putNullable("label", row.label)
                        put("uid", row.uid)
                        put("system", row.system)
                        put("enabled", row.enabled)
                    }
                }
            }
        }
    }

    private suspend fun diagnostics(assistantId: String, arguments: JsonObject): JsonObject {
        val kind = arguments.stringOrNull("kind")
            ?: return errorJson("bad_argument", "kind is required: ${controller.diagnosticsKinds().joinToString(" | ")}")
        val timeout = (arguments.intOrNull("timeout_seconds") ?: 30).let {
            it.coerceIn(1, HighPrivilegePolicy.MAX_TIMEOUT_SECONDS)
        }
        val result = controller.diagnostics(assistantId, kind, timeout)
        val (output, truncated) = HighPrivilegePolicy.bound(result.output, HighPrivilegePolicy.MAX_OUTPUT_CHARS)
        return buildJsonObject {
            put("ok", result.error == null && result.exitCode == 0)
            put("kind", result.kind)
            put("command", result.command)
            putNullable("exit_code", result.exitCode)
            put("truncated", truncated || result.truncated)
            put("output", output)
            putNullable("error", result.error)
            put("note", "Logs can contain personal data; this is what was requested, nothing else was scanned.")
        }
    }

    private suspend fun shell(assistantId: String, arguments: JsonObject): JsonObject {
        val command = arguments.stringOrNull("command")
            ?: return errorJson("bad_argument", "command is required")
        try {
            HighPrivilegePolicy.validateCommand(command)
        } catch (invalid: IllegalArgumentException) {
            return errorJson("bad_argument", invalid.message ?: "invalid command")
        }
        val timeout = (arguments.intOrNull("timeout_seconds") ?: HighPrivilegePolicy.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, HighPrivilegePolicy.MAX_TIMEOUT_SECONDS)
        val outcome = controller.runAuthorizedCommand(assistantId, command, timeout)
        val (stdout, stdoutTruncated) = HighPrivilegePolicy.bound(outcome.result.stdout)
        val (stderr, stderrTruncated) = HighPrivilegePolicy.bound(outcome.result.stderr)
        return buildJsonObject {
            put("ok", outcome.result.exitCode == 0 && !outcome.result.timedOut)
            put("executed", true)
            put("attempts", 1)
            put("command", command)
            put("exit_code", outcome.result.exitCode)
            put("timed_out", outcome.result.timedOut)
            put("uid", outcome.identity.uid)
            put("privilege_mode", outcome.identity.mode.name)
            put("selinux", outcome.identity.selinuxContext)
            put("stdout", stdout)
            put("stdout_truncated", stdoutTruncated)
            put("stderr", stderr)
            put("stderr_truncated", stderrTruncated)
            put("timeout_seconds", timeout)
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun runSafely(block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (denied: HighPrivilegeDeniedException) {
        errorJson("not_granted", denied.message ?: "Denied by the on-device privilege center.")
    } catch (invalid: IllegalArgumentException) {
        errorJson("bad_argument", invalid.message ?: "invalid argument")
    } catch (failure: Exception) {
        // 如实回报失败原因：不假装"权限受限"，也不假装"已成功"。
        errorJson("execution_failed", failure.message ?: failure::class.simpleName.orEmpty())
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Int?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
