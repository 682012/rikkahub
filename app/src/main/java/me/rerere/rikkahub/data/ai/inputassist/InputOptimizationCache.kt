package me.rerere.rikkahub.data.ai.inputassist

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

/**
 * 一条缓存记录代表的那次尝试的结果。
 *
 * 为什么不只缓存成功：同一句用户消息会因为断线重连、重新生成、恢复中的重试被反复送进
 * `prepareTurn`。只缓存成功等于让"失败一次"变成"每次重连再花钱失败一次"。
 */
enum class OptimizationCacheKind {
    /** 真的拿到了一版可用的改写 */
    REWRITE,

    /** 模型认为原话已经够清楚（或改写等于原话）：这一轮发原话，再问一次也还是这个结论 */
    UNCHANGED,

    /** 这一步失败了（超时/报错/守卫拒绝）；只留很短一段时间，避免同一句话被反复收费 */
    FAILED,
}

/**
 * 优化结果的私有磁盘缓存：一条消息一个文件，写入走"临时文件 + 原子替换"。
 *
 * 为什么要它：一轮消息可能因为断网恢复、重连、重新生成被再次送进请求组装，
 * 每次重新优化都是一次真实扣费。缓存键是 **会话 + messageId**，内容里再记原文 hash 和助手 id：
 * - 原文 hash 变了（用户编辑过这条消息）→ 当作未命中并删掉旧条目，旧优化版绝不复活；
 * - 助手 id 不同 → 不跨助手复用，避免把 A 助手的上下文化改写塞给 B 助手。这一条是**严格相等**：
 *   缺字段/对不上都算未命中，不存在"null 就放行"的旁路；
 * - 只存优化后的文本，不存用户原话副本：原话始终只有数据库那一份；
 * - [OptimizationCacheKind.UNCHANGED]/[OptimizationCacheKind.FAILED] 也记一条，但失败条目用更短的
 *   [DEFAULT_FAILED_TTL_MILLIS]，只挡住"同一句话被连续重试"，不会把一次偶发超时变成永久结论。
 *
 * 这里只用 JDK（File / Files）读写，不依赖 Android 与 kotlinx.serialization，
 * 因此可以离线单测真实文件的原子替换与失效行为。
 */
class InputOptimizationCache(
    private val dir: File,
    private val maxEntries: Int = 64,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val failedTtlMillis: Long = DEFAULT_FAILED_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 一条缓存记录：键（会话 + messageId）已经由文件名表达，这里只记判定用的指纹和结论。 */
    data class Entry(
        val conversationId: String,
        val messageId: String,
        /** 助手 id 必须严格存在并相等；null 不再表示"任意助手"。 */
        val assistantId: String,
        val originalHash: String,
        val optimizedText: String? = null,
        val status: OptimizationCacheKind = OptimizationCacheKind.REWRITE,
        val modelName: String? = null,
        /** 只用于诊断/统计，不参与是否命中的判断 */
        val modelId: String? = null,
        val providerId: String? = null,
        val failureReason: String? = null,
        val createdAtMillis: Long = 0L,
    )

    /**
     * 读一条命中。原文 hash、助手、会话或消息 id 任何一项对不上就先删掉这条再返回 null：
     * 留着一份对不上号的旧结论没有任何用处，反而会在下次误命中。
     */
    fun get(
        conversationId: Uuid,
        messageId: Uuid,
        originalHash: String,
        assistantId: Uuid,
    ): Entry? {
        val file = fileFor(conversationId, messageId)
        if (!file.isFile) return null
        val entry = runCatching { decode(file.readText()) }.getOrNull()
        if (entry == null || !matches(entry, conversationId, messageId, originalHash, assistantId)) {
            file.delete()
            return null
        }
        if (isExpired(entry)) {
            file.delete()
            return null
        }
        return entry
    }

    /** 写入一条记录；可替换的改写必须有正文，空文本一律不写。 */
    fun put(entry: Entry) {
        if (entry.status == OptimizationCacheKind.REWRITE && entry.optimizedText.isNullOrBlank()) return
        if (entry.assistantId.isEmpty() || entry.originalHash.isEmpty()) return
        val conversationId = Uuid.parse(entry.conversationId)
        val messageId = Uuid.parse(entry.messageId)
        ensureDir()
        atomicWrite(fileFor(conversationId, messageId), encode(entry))
        prune()
    }

    /** 某条消息被编辑/删除时调用：它的优化版立即失效。 */
    fun invalidateMessage(conversationId: Uuid, messageId: Uuid) {
        fileFor(conversationId, messageId).delete()
    }

    /** 整个会话的记录（例如会话被清空/换助手/被删除）一起失效。 */
    fun invalidateConversation(conversationId: Uuid) {
        val prefix = conversationId.toString()
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.startsWith("$prefix-")) file.delete()
        }
    }

    fun clear() {
        dir.listFiles().orEmpty().forEach { file -> if (file.isFile) file.delete() }
    }

    fun size(): Int = dir.listFiles().orEmpty().count { it.isFile && it.name.endsWith(EXT) }

    private fun matches(
        entry: Entry,
        conversationId: Uuid,
        messageId: Uuid,
        originalHash: String,
        assistantId: Uuid,
    ): Boolean {
        if (entry.conversationId != conversationId.toString()) return false
        if (entry.messageId != messageId.toString()) return false
        if (entry.originalHash != originalHash) return false
        if (entry.assistantId != assistantId.toString()) return false
        if (entry.status == OptimizationCacheKind.REWRITE && entry.optimizedText.isNullOrBlank()) return false
        return true
    }

    private fun isExpired(entry: Entry): Boolean {
        if (entry.createdAtMillis <= 0L) return false
        val ttl = if (entry.status == OptimizationCacheKind.FAILED) failedTtlMillis else ttlMillis
        if (ttl <= 0L) return false
        return now() - entry.createdAtMillis > ttl
    }

    private fun fileFor(conversationId: Uuid, messageId: Uuid): File =
        File(dir, "$conversationId-$messageId$EXT")

    private fun ensureDir() {
        if (dir.isDirectory) return
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create input-assist cache dir: $dir" }
    }

    /** 临时文件写完再原子替换：进程被杀时要么是旧版，要么是新版，不会是半截内容。 */
    private fun atomicWrite(file: File, content: String) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(content)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Cannot replace $file with temp file" }
            }
        }
    }

    /** 有界：只留最近的 [maxEntries] 条，最旧的先删。 */
    private fun prune() {
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(EXT) }
            .sortedByDescending { it.lastModified() }
        if (files.size <= maxEntries) return
        files.drop(maxEntries).forEach { runCatching { it.delete() } }
    }

    private fun encode(entry: Entry): String = buildString {
        appendLine("v=2")
        appendLine("c=${escape(entry.conversationId)}")
        appendLine("m=${escape(entry.messageId)}")
        appendLine("a=${escape(entry.assistantId)}")
        appendLine("h=${escape(entry.originalHash)}")
        appendLine("s=${entry.status.name}")
        appendLine("n=${escape(entry.modelName.orEmpty())}")
        appendLine("i=${escape(entry.modelId.orEmpty())}")
        appendLine("p=${escape(entry.providerId.orEmpty())}")
        appendLine("f=${escape(entry.failureReason.orEmpty())}")
        appendLine("t=${entry.createdAtMillis}")
        append("x=${escape(entry.optimizedText.orEmpty())}")
    }

    private fun decode(raw: String): Entry? {
        val fields = HashMap<String, String>()
        for (line in raw.split('\n')) {
            val index = line.indexOf('=')
            if (index <= 0) continue
            fields[line.substring(0, index)] = unescape(line.substring(index + 1))
        }
        val version = fields["v"]
        if (version != "1" && version != "2") return null
        val conversationId = fields["c"]?.takeIf { it.isNotEmpty() } ?: return null
        val messageId = fields["m"]?.takeIf { it.isNotEmpty() } ?: return null
        val hash = fields["h"]?.takeIf { it.isNotEmpty() } ?: return null
        // v1 没有助手字段：老文件一律当未命中（严格助手校验），不猜它是谁写的
        val assistantId = fields["a"]?.takeIf { it.isNotEmpty() } ?: return null
        val optimizedText = fields["x"]?.takeIf { it.isNotEmpty() }
        val status = if (version == "1") {
            OptimizationCacheKind.REWRITE
        } else {
            fields["s"]?.let { name -> OptimizationCacheKind.entries.firstOrNull { it.name == name } }
                ?: OptimizationCacheKind.REWRITE
        }
        return Entry(
            conversationId = conversationId,
            messageId = messageId,
            assistantId = assistantId,
            originalHash = hash,
            optimizedText = optimizedText,
            status = status,
            modelName = fields["n"]?.takeIf { it.isNotEmpty() },
            modelId = fields["i"]?.takeIf { it.isNotEmpty() },
            providerId = fields["p"]?.takeIf { it.isNotEmpty() },
            failureReason = fields["f"]?.takeIf { it.isNotEmpty() },
            createdAtMillis = fields["t"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String {
        if (value.indexOf('\\') == -1) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.length - 1) {
                out.append(char)
                index++
                continue
            }
            when (val next = value[index + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(next)
            }
            index += 2
        }
        return out.toString()
    }

    companion object {
        private const val EXT = ".entry"

        /** 两周：够覆盖重连/重开 App 的现实窗口，又不至于让旧优化版长期活着 */
        const val DEFAULT_TTL_MILLIS = 14L * 24 * 60 * 60 * 1000

        /**
         * 失败只记 5 分钟：刚好挡住"同一句话被连续重连/重新生成反复请求"，
         * 又不至于让一次偶发超时在半天内都不再尝试。
         */
        const val DEFAULT_FAILED_TTL_MILLIS = 5L * 60 * 1000
    }
}
