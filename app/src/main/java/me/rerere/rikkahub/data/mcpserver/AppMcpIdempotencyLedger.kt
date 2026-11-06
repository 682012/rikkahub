package me.rerere.rikkahub.data.mcpserver

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * 5. 失败也落成终态（`FAILED` + 原因），重试返回同样的失败，不重新执行；
 * 6. **账本读不出来就不许写**：文件存在但不可读 / 是空文件 / 是坏 JSON / 根本不是普通文件
 *    （比如被一个目录占住了名字）时，账本进入 [AppMcpLedgerLoad.UNTRUSTED]，
 *    所有写操作一律拒绝（`ledger_unreadable`），而且这个对象**永不覆盖**那个文件——
 *    坏文件留着给人看，绝不用一本"空账"把旧的 `PENDING` 洗掉。
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

/**
 * 账本文件的信任状态。**关键点：加载失败是显式状态，不是"当成空账本"**。
 *
 * 旧实现把"文件不存在"和"文件读不出来 / 是空的 / 是坏 JSON"一起当成空账本，
 * 于是下一次写会拿到 `Fresh` 许可——如果盘上原本有一条 `PENDING`，这次就会把同一个
 * 设备写动作**真的执行第二遍**。所以这里把三种情况彻底分开：
 *
 * - 文件确实不存在（首次运行）→ [TRUSTED] 空账本，允许第一笔写；
 * - 文件存在且能完整解析（包括合法的 `[]`）→ [TRUSTED]，按内容记账；
 * - 文件存在但读失败 / 空 / 坏 JSON / 截断 / 不是普通文件 → [UNTRUSTED]，只读拒绝。
 */
enum class AppMcpLedgerLoad {
    /** 还没读过盘。 */
    UNLOADED,

    /** 内容可信：要么文件不存在（首次运行），要么完整解析成功。 */
    TRUSTED,

    /** 文件在、但内容不可信：账本记过什么已经不知道了，只许拒绝，不许写。 */
    UNTRUSTED,
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
    private var load = AppMcpLedgerLoad.UNLOADED
    private var loadFailure: String? = null
    private val entries = LinkedHashMap<String, AppMcpLedgerEntry>()

    /** 当前加载状态（诊断用）。[AppMcpLedgerLoad.UNTRUSTED] 时所有写操作都被拒绝。 */
    val loadState: AppMcpLedgerLoad
        get() = synchronized(lock) {
            ensureLoaded()
            load
        }

    /** 加载不可信的原因（诊断用，给人看的一句话）；可信时为 `null`。 */
    val loadError: String?
        get() = synchronized(lock) {
            ensureLoaded()
            loadFailure
        }

    /**
     * 同 key 同参数 → Replay；同 key 不同参数/工具 → Rejected；
     * 上次中断在 PENDING → Rejected(unknown_side_effect)；否则落盘 PENDING 并返回 Fresh。
     *
     * 账本不可信（[AppMcpLedgerLoad.UNTRUSTED]）时一律 Rejected(`ledger_unreadable`)：
     * 盘上可能有没删掉的 PENDING，此时给 `Fresh` 就是允许一次重复的设备写。
     */
    fun decide(
        tool: String,
        key: String,
        argumentsFingerprint: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): AppMcpIdemDecision =
        synchronized(lock) {
            ensureLoaded()
            if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized untrustedDecision()
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

    /** 记录成功结果；之后同 key 同参数返回这一份。@return 是否真的写进了磁盘。 */
    fun complete(
        tool: String,
        key: String,
        resultJson: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): Boolean = synchronized(lock) {
        ensureLoaded()
        // 账本不可信时不许动盘：内存里是空的，写一次就等于把盘上那些"未知 PENDING"擦掉。
        if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized false
        val id = idOf(tool, key, scope)
        val current = entries[id] ?: return@synchronized false
        entries[id] = current.copy(
            status = AppMcpLedgerStatus.DONE.name,
            resultJson = resultJson,
            error = null,
            at = clock(),
        )
        prune()
        persist()
    }

    /** 记录失败：同样是终态，重试只回放失败，不重新执行。@return 是否真的写进了磁盘。 */
    fun fail(
        tool: String,
        key: String,
        error: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): Boolean = synchronized(lock) {
        ensureLoaded()
        if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized false
        val id = idOf(tool, key, scope)
        val current = entries[id] ?: return@synchronized false
        entries[id] = current.copy(
            status = AppMcpLedgerStatus.FAILED.name,
            error = error,
            at = clock(),
        )
        prune()
        persist()
    }

    /**
     * 可信时返回条目；账本不可信时返回 `null`。注意 `null` 的含义是"不知道"，不是"没记过"——
     * 调用方不能把这里的结果当放行依据（放行只看 [decide]）。
     */
    fun entryOf(
        tool: String,
        key: String,
        scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE,
    ): AppMcpLedgerEntry? = synchronized(lock) {
        ensureLoaded()
        if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized null
        entries[idOf(tool, key, scope)]
    }

    /** 可信时的条目快照；账本不可信时返回空列表（内容未知，不是"账本是空的"）。 */
    fun snapshot(): List<AppMcpLedgerEntry> = synchronized(lock) {
        ensureLoaded()
        if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized emptyList()
        entries.values.toList()
    }

    /** @return 是否真的写进了磁盘；账本不可信时不删、不写，返回 `false`。 */
    fun forget(tool: String, key: String, scope: String = AppMcpLedgerEntry.DEFAULT_SCOPE): Boolean =
        synchronized(lock) {
            ensureLoaded()
            if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized false
            entries.remove(idOf(tool, key, scope))
            persist()
        }

    /**
     * 只清终态，保留 PENDING（否则一次清理就把"未知副作用"洗白了）。
     * 账本不可信时不清理、不写盘，返回 `false`。
     */
    fun clearFinished(): Boolean = synchronized(lock) {
        ensureLoaded()
        if (load != AppMcpLedgerLoad.TRUSTED) return@synchronized false
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

    private fun untrustedDecision(): AppMcpIdemDecision.Rejected = AppMcpIdemDecision.Rejected(
        code = "ledger_unreadable",
        message = "The idempotency ledger exists but cannot be trusted" +
            (loadFailure?.let { " ($it)" } ?: "") +
            ", so nothing was executed. It may still hold in-flight entries, and a duplicate write is " +
            "worse than a refused one. Repair or remove the ledger file, then restart the app.",
    )

    /**
     * 首次加载。三种情况必须分开处理，绝不能都归成"空账本"：
     * - 文件不存在 → 首次运行，可信的空账本（允许第一笔写）；
     * - 文件存在、能完整解析（含合法 `[]`）→ 可信；
     * - 文件存在但不可读 / 空 / 坏 JSON / 截断 / 不是普通文件（目录等）→ 不可信，
     *   记下原因并**保留文件原样**，之后所有写操作被拒。
     *
     * 恢复策略是**故意保守**的：本对象一旦被判成不可信就永远不可信（不重试、不自动恢复、
     * 不覆盖文件）。人工修好/删掉文件后，下一次进程启动（新对象）才会重新加载；这样运行中
     * 有人"顺手"动一下文件，也不可能把未知 PENDING 悄悄变成"账本干净，可以再写一次"。
     */
    private fun ensureLoaded() {
        if (load != AppMcpLedgerLoad.UNLOADED) return
        entries.clear()
        if (!file.exists()) {
            // 首次运行：连文件都没有，不存在"可能有 PENDING"的问题。
            load = AppMcpLedgerLoad.TRUSTED
            return
        }
        if (!file.isFile) {
            // 被目录/设备节点占住了名字：读不出内容，也不能往里写。
            markUntrusted("ledger path is not a regular file")
            return
        }
        val text = try {
            file.readText()
        } catch (failure: Exception) {
            markUntrusted("read failed: ${failure.message ?: failure::class.simpleName}")
            return
        }
        if (text.isBlank()) {
            // 空文件 ≠ 空账本：它可能是一次写被打断后的残骸。
            markUntrusted("ledger file is empty")
            return
        }
        val decoded = try {
            json.decodeFromString(serializer, text)
        } catch (failure: Exception) {
            markUntrusted("ledger file is not valid json: ${failure.message ?: failure::class.simpleName}")
            return
        }
        decoded.forEach { entry -> entries[idOf(entry.tool, entry.key, entry.scope)] = entry }
        load = AppMcpLedgerLoad.TRUSTED
    }

    private fun markUntrusted(reason: String) {
        loadFailure = reason
        load = AppMcpLedgerLoad.UNTRUSTED
    }

    /**
     * @return 是否真的写进了磁盘。调用方决定"写不进去还能不能执行"（PENDING 必须 fail closed）。
     * 账本不可信时直接返回 `false`，一个字节都不碰盘。
     */
    private fun persist(): Boolean {
        if (load != AppMcpLedgerLoad.TRUSTED) return false
        val text = runCatching { json.encodeToString(serializer, entries.values.toList()) }.getOrNull()
            ?: return false
        return runCatching { writeAtomically(text) }.getOrDefault(false)
    }

    /**
     * 原子写：临时文件 → fsync → rename。
     *
     * - 先 `FileOutputStream` + `fd.sync()` 落盘，避免只写了 0 字节/半截内容就改了名字；
     * - **rename 失败就返回 `false`，绝不回退成直接覆盖正式文件**：旧实现会在 rename 失败后
     *   `file.writeText(text)`，那一步一旦被打断就把原来好好的账本截成坏文件，把 PENDING 记录
     *   一起弄丢，下一次调用就会被判成 Fresh 并重复执行设备写。
     *
     * 已知边界：没有对父目录做 fsync（java.io 拿不到目录 fd），断电丢 rename 的情况靠"文件要么是
     * 旧账本、要么是新账本"的原子性兜住；`renameTo` 在 Android/Linux 上是 `rename(2)`，可覆盖已有文件。
     */
    private fun writeAtomically(text: String): Boolean {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) {
            parent.mkdirs()
            if (!parent.isDirectory) return false
        }
        val tmp = File(parent, "${file.name}.tmp")
        var written = false
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            written = true
        } finally {
            if (!written) tmp.delete()
        }
        return try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            // 不支持原子替换或目标不可写时拒绝执行；不截断正式账本。
            tmp.delete()
            false
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200

        /** 参数指纹：键排序后的稳定 JSON，保证同一份参数得到同一把指纹。 */
        fun fingerprint(arguments: JsonObject): String =
            JsonObject(arguments.entries.sortedBy { it.key }.associate { it.key to it.value }).toString()
    }
}
