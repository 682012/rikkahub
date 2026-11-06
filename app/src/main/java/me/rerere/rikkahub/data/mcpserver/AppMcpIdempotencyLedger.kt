package me.rerere.rikkahub.data.mcpserver

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 写工具的幂等账本：**传输失败不许重放写动作**。
 *
 * 规则（对应需求里的每一条）：
 * 1. 执行前先落盘 `PENDING`（原子写：临时文件 + rename），然后才真正执行；
 * 2. 同一个 key + 同样的参数再来一次 → 返回第一次记录的结果（[AppMcpIdemDecision.Replay]）；
 * 3. 同一个 key + 不同参数（或不同工具）→ 拒绝 `idempotency_key_reuse`；
 * 4. 账本里还是 `PENDING`（上次进程死在中间）→ 拒绝 `unknown_side_effect`，**绝不再执行一次**；
 * 5. 失败也落成终态（`FAILED` + 原因），重试返回同样的失败，不重新执行。
 *
 * 纯 JVM（只用 java.io.File），可以直接跑单测。
 */
@Serializable
enum class AppMcpLedgerStatus {
    /** 已落盘、还没结果：进程如果死在这里，副作用是否发生是未知的。 */
    PENDING,

    /** 执行完成，`resultJson` 就是当时返回给调用方的那份结果。 */
    DONE,

    /** 执行失败（已如实记录），重试返回同样的失败，不重新执行。 */
    FAILED,
}

@Serializable
data class AppMcpLedgerEntry(
    val key: String,
    val tool: String,
    /** 规范化后的参数指纹，用于识别"同一个 key 换了参数"。 */
    val argsHash: String,
    val status: String,
    val resultJson: String = "",
    val error: String? = null,
    val at: Long = 0L,
    /**
     * 归属域（绑定助手 id / 设备域）。同一个 `idempotency_key` 在不同助手之间**不共用**：
     * 否则"换个绑定助手 + 复用同一个 key"就能把上一个助手的执行结果当成自己的回放，属于跨助手泄露。
     */
    val scope: String = DEFAULT_SCOPE,
) {
    companion object {
        /** 未指定归属域时的默认值（历史条目/纯工具测试用）。 */
        const val DEFAULT_SCOPE = ""
    }
}

sealed interface AppMcpIdemDecision {
    /** 头一次见到这个 key：已经落盘 PENDING，可以执行。 */
    data class Fresh(val entry: AppMcpLedgerEntry) : AppMcpIdemDecision

    /** 这个 key 已经完成过：直接返回当时的结果，不再执行。 */
    data class Replay(val resultJson: String) : AppMcpIdemDecision

    /** 拒绝执行。[code] 进错误返回，[message] 给人看。 */
    data class Rejected(val code: String, val message: String) : AppMcpIdemDecision
}

/**
 * @param file 账本文件（App 里放在 `filesDir/r22-mcp/idempotency.json`）
 * @param maxEntries 只保留最近若干条；只裁终态条目，`PENDING` 永不裁剪
 */
class AppMcpIdempotencyLedger(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(AppMcpLedgerEntry.serializer())
    private var loaded = false
    private val entries = LinkedHashMap<String, AppMcpLedgerEntry>()

    /**
     * 同 key 同参数 → Replay；同 key 不同参数/工具 → Rejected；
     * 上次中断在 PENDING → Rejected(unknown_side_effect)；否则落盘 PENDING 并返回 Fresh。
     */
    fun decide(
        tool: String,
        key: String,
        argumentsFingerprint: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): AppMcpIdemDecision =
        synchronized(lock) {
            ensureLoaded()
            val id = idOf(tool, key, scope)
            val existing = entries[id]
            when {
                existing == null -> {
                    val entry = AppMcpLedgerEntry(
                        key = key,
                        tool = tool,
                        argsHash = argumentsFingerprint,
                        status = AppMcpLedgerStatus.PENDING.name,
                        at = clock(),
                        scope = scope,
                    )
                    entries[id] = entry
                    // PENDING 必须先落盘才允许执行。落盘失败就不能执行：
                    // 内存里记着、盘上没有，进程一死这条"进行中"就消失了，重放保护直接失效。
                    if (persist()) {
                        AppMcpIdemDecision.Fresh(entry)
                    } else {
                        entries.remove(id)
                        AppMcpIdemDecision.Rejected(
                            code = "ledger_unavailable",
                            message = "The idempotency ledger could not be persisted, so nothing was executed. " +
                                "This tool changes state and must not run without replay protection.",
                        )
                    }
                }

                existing.argsHash != argumentsFingerprint || existing.tool != tool ->
                    AppMcpIdemDecision.Rejected(
                        code = "idempotency_key_reuse",
                        message = "This idempotency_key was already used with different arguments; nothing was " +
                            "executed. Use a new key for a new operation.",
                    )

                existing.status == AppMcpLedgerStatus.PENDING.name ->
                    // 上一次执行到一半进程就没了：副作用是否发生未知，不能"再试一次"。
                    AppMcpIdemDecision.Rejected(
                        code = "unknown_side_effect",
                        message = "A previous call with this idempotency_key started but never finished. Its side " +
                            "effect is unknown, so it is NOT replayed. Inspect the real state first.",
                    )

                existing.status == AppMcpLedgerStatus.DONE.name ->
                    AppMcpIdemDecision.Replay(existing.resultJson)

                else -> AppMcpIdemDecision.Rejected(
                    code = "unknown_side_effect",
                    message = "A previous call with this idempotency_key failed and is not replayed automatically. " +
                        "Re-run with a new key once the actual state is known.",
                )
            }
        }

    /** 记录成功结果；之后同 key 同参数返回这一份。 */
    fun complete(
        tool: String,
        key: String,
        resultJson: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ) = synchronized(lock) {
        ensureLoaded()
        val id = idOf(tool, key, scope)
        val current = entries[id]
        if (current != null) {
            entries[id] = current.copy(
                status = AppMcpLedgerStatus.DONE.name,
                resultJson = resultJson,
                error = null,
                at = clock(),
            )
            prune()
            persist()
        }
    }

    /** 记录失败：同样是终态，重试只回放失败，不重新执行。 */
    fun fail(
        tool: String,
        key: String,
        error: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ) = synchronized(lock) {
        ensureLoaded()
        val id = idOf(tool, key, scope)
        val current = entries[id]
        if (current != null) {
            entries[id] = current.copy(
                status = AppMcpLedgerStatus.FAILED.name,
                error = error,
                at = clock(),
            )
            prune()
            persist()
        }
    }

    fun entryOf(
        tool: String,
        key: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): AppMcpLedgerEntry? = synchronized(lock) {
        ensureLoaded()
        entries[idOf(tool, key, scope)]
    }

    fun snapshot(): List<AppMcpLedgerEntry> = synchronized(lock) {
        ensureLoaded()
        entries.values.toList()
    }

    fun forget(tool: String, key: String, scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE) =
        synchronized(lock) {
            ensureLoaded()
            entries.remove(idOf(tool, key, scope))
            persist()
        }

    /** 只清终态，保留 PENDING（否则一次清理就把"未知副作用"洗白了）。 */
    fun clearFinished() = synchronized(lock) {
        ensureLoaded()
        val ids = entries.entries.filter { it.value.status != AppMcpLedgerStatus.PENDING.name }.map { it.key }
        ids.forEach { entries.remove(it) }
        persist()
    }

    /**
     * 账本身份 = 归属域 + `idempotency_key`。**工具名故意不参与**：一个 key 在一个域里只能指向
     * 一笔操作，换工具就是换了操作，必须在 `decide` 里撞上同一条记录并被拒成
     * `idempotency_key_reuse`。把 tool 拼进 id 会让"同一个 key 换个工具"直接落成一条全新的
     * PENDING 并执行，规则 3 的 `existing.tool != tool` 分支也就永远走不到——即一次误用同一个 key
     * 的调用会把第二个写动作真的执行掉。
     *
     * 参数 `tool` 保留是为了所有调用点的可读性（身份不来自它，见上）。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun idOf(tool: String, key: String, scope: String): String = "$scope#$key"

    private fun prune() {
        if (entries.size <= maxEntries) return
        val removable = entries.entries
            .filter { it.value.status != AppMcpLedgerStatus.PENDING.name }
            .sortedBy { it.value.at }
        val overflow = entries.size - maxEntries
        removable.take(overflow.coerceAtLeast(0)).forEach { entries.remove(it.key) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entries.clear()
        val text = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return
        val decoded = runCatching { json.decodeFromString(serializer, text) }.getOrNull() ?: return
        decoded.forEach { entry -> entries[idOf(entry.tool, entry.key, entry.scope)] = entry }
    }

    /** @return 是否真的写进了磁盘。调用方决定"写不进去还能不能执行"（PENDING 必须 fail closed）。 */
    private fun persist(): Boolean = runCatching {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val text = json.encodeToString(serializer, entries.values.toList())
        // 原子写：先写临时文件再 rename，避免半截 JSON 把整本账作废。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        true
    }.getOrElse { false }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200

        /** 参数指纹：键排序后的稳定 JSON，保证同一份参数得到同一把指纹。 */
        fun fingerprint(arguments: JsonObject): String =
            JsonObject(arguments.entries.sortedBy { it.key }.associate { it.key to it.value }).toString()
    }
}
