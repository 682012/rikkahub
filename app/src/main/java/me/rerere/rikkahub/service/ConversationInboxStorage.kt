package me.rerere.rikkahub.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

class InboxPersistenceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 收件箱的持久层。接口是纯同步的，线程由调用方安排：读一个小 JSON 文件，
 * 不要在主线程上调用。
 *
 * 两条约定，调用方靠它们决定「能不能动内存里的队列」：
 * - [read] 没有文件返回 null（新会话）；文件坏了必须抛出并**保留**文件，不能当成空队列，
 *   否则下一次写入就把用户的待发输入覆盖没了。
 * - [write] 没真正落盘就抛出。收件箱只在 write 返回以后才发布新状态，
 *   所以写失败时内存、回执都不会出现「已经受理」的假象。
 */
interface ConversationInboxStorage {
    fun read(): InboxState?

    fun write(state: InboxState)
}

/** 落盘内容的外壳：带上版本和会话 id，避免跨会话误读或旧格式被静默曲解 */
@Serializable
internal data class PersistedInbox(
    val version: Int = INBOX_FILE_VERSION,
    val conversationId: String,
    val state: InboxState,
)

/** 用户输入队列本来就小（附件只存引用），留足余量后按硬上限拒绝，绝不截断 */
internal const val INBOX_FILE_MAX_BYTES = 8L * 1024 * 1024

internal const val INBOX_FILE_VERSION = 1

/**
 * 一个会话一个 `UUID.json`，写入走 tmp -> fsync -> 原子改名。
 *
 * 不设条数上限：用户排了多少条就存多少条，只在编码后超过 [INBOX_FILE_MAX_BYTES] 时抛错，
 * 让调用方知道这份队列存不下，而不是悄悄丢掉一半。
 */
class FileConversationInboxStorage(
    private val dir: File,
    private val conversationId: Uuid,
) : ConversationInboxStorage {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val file: File get() = File(dir, "$conversationId.json")

    override fun read(): InboxState? = try { readFile() } catch (e: InboxPersistenceException) {
        throw e
    } catch (e: Exception) { throw InboxPersistenceException("Cannot restore pending input", e) }

    private fun readFile(): InboxState? {
        val target = file
        if (!target.isFile) return null
        val length = target.length()
        if (length > INBOX_FILE_MAX_BYTES) {
            // 不截断、不清空：太大也要留给人看，能恢复的输入不能因为尺寸就丢
            throw IOException(
                "inbox file too large: ${target.absolutePath} ($length bytes > $INBOX_FILE_MAX_BYTES)"
            )
        }
        val text = target.readBytes().decodeToString()
        val persisted = try {
            json.decodeFromString(PersistedInbox.serializer(), text)
        } catch (e: Exception) {
            // 保留文件，等人工恢复；这里抛给调用方决定要不要继续会话
            throw IOException("inbox file is corrupt: ${target.absolutePath}", e)
        }
        if (persisted.version > INBOX_FILE_VERSION) {
            throw IOException(
                "inbox file version ${persisted.version} is newer than $INBOX_FILE_VERSION: ${target.absolutePath}"
            )
        }
        if (persisted.conversationId != conversationId.toString()) {
            throw IOException(
                "inbox file belongs to ${persisted.conversationId}, not $conversationId: ${target.absolutePath}"
            )
        }
        return persisted.state
    }

    override fun write(state: InboxState) {
        try { writeFile(state) } catch (e: InboxPersistenceException) { throw e }
        catch (e: Exception) { throw InboxPersistenceException("Cannot save pending input", e) }
    }

    private fun writeFile(state: InboxState) {
        if (state.items.isEmpty() && state.inFlight == null && state.mutedNoticeKeys.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("cannot remove empty pending queue")
            return
        }
        ensureDir()
        val payload = PersistedInbox(
            conversationId = conversationId.toString(),
            state = state,
        )
        val bytes = json.encodeToString(PersistedInbox.serializer(), payload).toByteArray(Charsets.UTF_8)
        if (bytes.size > INBOX_FILE_MAX_BYTES) {
            throw IOException(
                "inbox state too large: ${bytes.size} bytes > $INBOX_FILE_MAX_BYTES (${file.absolutePath})"
            )
        }
        writeAtomically(file, bytes)
    }

    private fun ensureDir() {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create inbox directory: ${dir.absolutePath}")
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("no parent for ${target.absolutePath}")
        val tmp = File(parent, "${target.name}.${Uuid.random()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}
