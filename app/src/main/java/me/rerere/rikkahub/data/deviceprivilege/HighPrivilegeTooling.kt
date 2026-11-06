package me.rerere.rikkahub.data.deviceprivilege

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

/**
 * m1 高权限中心的**工具层**唯一实现（本地助手工具 + 本机 MCP + 本地自动化共用）。
 *
 * 一条硬规则：这里的每个入口都**先自己核一次授权**（调控制器的 `checkAutomation`，现读开关 + 现查
 * Shizuku，非 suspend），再交给 [HighPrivilegeController] 复核一次。两次都不是"缓存里的旧快照"——
 * 用户中途关开关、换绑助手、Shizuku 掉线，当次调用就该失败，而不是沿用上一轮的结果。
 *
 * 不做的事（写死在代码结构里，不靠调用方自觉）：
 * - 不申请授权、不 `requestAuthorization`、不 fallback ROOT/APP —— 唯一授权入口是权限中心 UI；
 * - 不扩大原权限：只用 controller 暴露的六件事（status/settings/packages/diagnostics/shell/readback）；
 * - 未知副作用不重放：写路径先过 [AppMcpIdempotencyLedger]（独立账本），中断的写不会因为
 *   重试同一个 key 被再执行一次；
 * - 不自动外发敏感 setting / logcat：`looks_secret` 的键默认只回标记，值要显式
 *   `include_sensitive=true` 才给。
 */
object HighPrivilegeTooling {
    const val STATUS = "android_privilege_status"
    const val SETTINGS_GET = "android_settings_get"
    const val SETTINGS_PUT = "android_settings_put"
    const val PACKAGES = "android_packages"
    const val DIAGNOSTICS = "android_diagnostics"
    const val SHELL = "android_shell"

    /** 固定六个；新增名字必须同时在这里登记，否则 host 一律 `unknown_tool`。 */
    val all: Set<String> = linkedSetOf(STATUS, SETTINGS_GET, SETTINGS_PUT, PACKAGES, DIAGNOSTICS, SHELL)

    const val IDEMPOTENCY_KEY_FIELD = "idempotency_key"
    const val INCLUDE_SENSITIVE_FIELD = "include_sensitive"

    /** 写（有副作用）：需要权限中心的"写"开关，且必须带 idempotency_key。 */
    fun isWrite(name: String): Boolean = name == SETTINGS_PUT || name == SHELL

    /** 需要审批/日志类敏感：写 + diagnostics。 */
    fun isSensitive(name: String): Boolean = isWrite(name) || name == DIAGNOSTICS

    /**
     * 这一层**额外**要求 Shizuku 现在真的可用。
     *
     * 只有这两件真的离不开 Shizuku：
     * - [SHELL]：唯一的执行通道就是 Shizuku 的 shell UID；
     * - [DIAGNOSTICS]：logcat / dumpsys 只走 Shizuku。
     *
     * 其余几件刻意**不**在这里强绑 Shizuku，否则会把控制器里已有的合法路径挡死：
     * - [SETTINGS_GET] / [SETTINGS_PUT]：由控制器自己决定 `via=app-granted`
     *   （本 App 已持有 `WRITE_SECURE_SETTINGS`，或 system 命名空间有 `Settings.System.canWrite`）
     *   还是 `via=shizuku`，并在真正写之前用 `requireShizuku = !direct` 再核一次。
     *   这里再叠一道"必须有 Shizuku"的硬门禁，等于把用户已经合法拿到的那条路判死。
     * - [PACKAGES]：只读 `PackageManager`（`QUERY_ALL_PACKAGES`），根本不需要 Shizuku。
     * - [STATUS]：读的是控制器自己的状态。
     *
     * 不查 Shizuku 不等于假装成功：真执行拿不到身份会如实回 `not_granted` / `execution_failed`。
     */
    fun needsShizuku(name: String): Boolean = name == SHELL || name == DIAGNOSTICS

    fun description(name: String): String = when (name) {
        STATUS ->
            "Read the on-device privilege center status (Shizuku readiness, per-permission real state, " +
                "automation switch, write switch, bound assistant). Source of truth is the privilege center UI; " +
                "this tool cannot grant, request or widen anything. Only visible to the assistant the user bound there."
        SETTINGS_GET ->
            "Read one Android setting (system/secure/global) through the privilege center's Shizuku shell, " +
                "falling back to the app's own read. Read-only. Values of keys that look secret " +
                "(password/token/secret/credential) are redacted unless include_sensitive=true; never paste them elsewhere."
        SETTINGS_PUT ->
            "Write or delete one Android setting through the privilege center. This is a WRITE: it needs the separate " +
                "write switch in the privilege center, must carry idempotency_key, and is executed exactly once. " +
                "The result carries old_value/new_value/verified from a read-back; verified=false means the value did " +
                "not actually change. Unknown side effects are never replayed. Keys that look secret stay redacted " +
                "unless include_sensitive=true."
        PACKAGES ->
            "List installed packages (packageName, label, uid, system, enabled) with a hard limit. Read-only, " +
                "no app-private data is read."
        DIAGNOSTICS ->
            "Run one fixed diagnostic through the privilege center: kind=logcat (last lines only) or " +
                "activity/windows/battery/package/notification dumpsys. Logs may contain personal data, so this is " +
                "approval-gated and output is truncated. Nothing is searched automatically."
        SHELL ->
            "Run one shell command through the privilege center using the user's Shizuku (shell UID) authorization. " +
                "Fixed SHIZUKU route, no ROOT/APP fallback, no privilege escalation, a single attempt (no auto retry), " +
                "timeout <=600s. Every command counts as having side effects: the privilege center's write switch must " +
                "be on, idempotency_key is required, and an interrupted run is reported as unknown rather than retried."
        else -> "High-privilege tool backed by the on-device privilege center."
    }

    fun schema(name: String): JsonObject = when (name) {
        STATUS -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        }
        SETTINGS_GET -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                namespaceProperty()
                putJsonObject("key") {
                    put("type", "string"); put("description", "Setting key, [A-Za-z0-9_.:-]{1,128}")
                }
                putJsonObject(INCLUDE_SENSITIVE_FIELD) {
                    put("type", "boolean")
                    put("description", "Return the real value even when the key looks secret. Default false = redacted.")
                }
            }
            putJsonArray("required") { add("namespace"); add("key") }
            put("additionalProperties", false)
        }
        SETTINGS_PUT -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                namespaceProperty()
                putJsonObject("key") {
                    put("type", "string"); put("description", "Setting key, [A-Za-z0-9_.:-]{1,128}")
                }
                putJsonObject("value") {
                    put("type", "string")
                    put("description", "New value (<=2048 chars). Omit or null to delete the key.")
                }
                putJsonObject(INCLUDE_SENSITIVE_FIELD) {
                    put("type", "boolean")
                    put("description", "Return old/new values even when the key looks secret. Default false = redacted.")
                }
                idempotencyKeyProperty()
            }
            putJsonArray("required") { add("namespace"); add("key"); add(IDEMPOTENCY_KEY_FIELD) }
            put("additionalProperties", false)
        }
        PACKAGES -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("include_system") {
                    put("type", "boolean"); put("description", "Include system packages. Default false.")
                }
                putJsonObject("limit") {
                    put("type", "integer"); put("description", "Max rows, clamped 1..1000. Default 300.")
                }
            }
            put("additionalProperties", false)
        }
        DIAGNOSTICS -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") {
                    put("type", "string")
                    put("description", "logcat | activity | windows | battery | package | notification")
                }
                timeoutProperty()
            }
            putJsonArray("required") { add("kind") }
            put("additionalProperties", false)
        }
        SHELL -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to run as the user's Shizuku identity (shell UID, or 0 if Shizuku runs as root). No NUL, <=32K chars.")
                }
                timeoutProperty()
                idempotencyKeyProperty()
            }
            putJsonArray("required") { add("command"); add(IDEMPOTENCY_KEY_FIELD) }
            put("additionalProperties", false)
        }
        else -> JsonObject(emptyMap())
    }

    private fun JsonObjectBuilder.namespaceProperty() {
        putJsonObject("namespace") {
            put("type", "string")
            put("description", "system | secure | global")
        }
    }

    private fun JsonObjectBuilder.timeoutProperty() {
        putJsonObject("timeout_seconds") {
            put("type", "integer"); put("description", "Seconds, clamped 1..600. Default 30.")
        }
    }

    private fun JsonObjectBuilder.idempotencyKeyProperty() {
        putJsonObject(IDEMPOTENCY_KEY_FIELD) {
            put("type", "string")
            put("description", "Caller-generated key. Reuse the SAME key only when retrying the same call after a transport failure; a different change needs a new key.")
        }
    }
}

/** 一条暴露给工具目录 / MCP 的工具描述（与传输层无关）。 */
data class HighPrivilegeToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val needsApproval: Boolean,
)

/**
 * 传输层需要的最小代理面：**只认名字白名单**，其余名字原样交回给原来的 host。
 * 这样 MCP / 自动化都不必知道高权限工具的内部实现，也不会被顺手扩权。
 */
interface HighPrivilegeMcpProxy {
    fun handles(name: String): Boolean

    /** 这个名字是不是写（必须带 idempotency_key）。非 suspend，供自动化 runner 预填 key。 */
    fun requiresIdempotencyKey(name: String): Boolean = HighPrivilegeTooling.isWrite(name)

    /**
     * 静态工具元数据（名字/描述/schema/readOnly/needsApproval）。
     *
     * **不做任何授权判断**：它只用来给结果分类（只读 vs 写）和拼描述，
     * 能不能跑一律由 [exposedTools] / [automationCatalog] / [callAsBoundAssistant] 自己核。
     * 默认 null：没实现这一层的传输不会凭空多出工具。
     */
    fun descriptorOrNull(name: String): HighPrivilegeToolDescriptor? = null

    /**
     * 非 suspend 的工具目录：只给拿不到协程的地方用（自动化步骤下拉 `AutomationToolRunner.tools()`）。
     *
     * 过滤条件 = **当下**的真实授权（控制器 `checkAutomation`：非 suspend、不弹框、不提权、不缓存）：
     * 自动化总开关已开 **且** 绑定助手与 [boundAssistantId] 完全相等（null/空白 = 谁都没绑定）。
     *
     * 写开关与 Shizuku 就绪**不**在这里过滤：它们是每次调用都要重核的门禁，列表里保留是为了让用户
     * 能先把配方写出来；真跑的时候该拒的照拒（`not_granted`，不重试、不降级、不 fallback）。
     *
     * 默认空列表：fail-closed，不实现就等于没有。
     */
    fun automationCatalog(boundAssistantId: String?): List<HighPrivilegeToolDescriptor> = emptyList()

    /** 现查授权；未开启 / 未绑定 / 绑的是别的助手 → 空列表（工具直接不出现）。 */
    suspend fun exposedTools(assistantId: String): List<HighPrivilegeToolDescriptor>

    /** 以绑定助手身份执行；写路径强制 idempotency_key。永不抛异常，失败返回 `ok=false`。 */
    suspend fun callAsBoundAssistant(assistantId: String, name: String, arguments: JsonObject): JsonObject
}

