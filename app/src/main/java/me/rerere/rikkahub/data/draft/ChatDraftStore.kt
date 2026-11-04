package me.rerere.rikkahub.data.draft

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.ChatDraft
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ChatDraftStore"

/** 墓碑最多留多少个 key。 */
private const val MAX_TOMBSTONES = 64

/** 应用私有目录里放草稿的子目录。 */
const val CHAT_DRAFT_DIR = "chat-drafts"

/** 输入停下多久写盘：够短，强杀最多丢这一小段；够长，连续打字不会一直写文件。 */
const val CHAT_DRAFT_WRITE_DELAY_MS = 400L

/**
 * 草稿的落盘方式。key 是 `conversation:<id>` 或 `assistant:<id>`，见 `chatDraftKey`。
 *
 * 抽成接口是为了让 [ChatDraftStore] 的排队/防覆盖逻辑能在纯 JVM 单测里跑，
 * 真正的文件实现在 [FileChatDraftStorage]（Android `AtomicFile`）。
 */
interface ChatDraftStorage {
    suspend fun read(key: String): ChatDraft?
    suspend fun write(key: String, draft: ChatDraft)

    /** 没有内容时删掉，下次就是干净的 */
    suspend fun delete(key: String)
}

/** key 不可直接当文件名（会话/助手 id 里有 `:`），统一 SHA-256。 */
internal fun chatDraftFileName(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".json"
}

/** 与 `chatDraftKey` 生成的前缀一致。 */
internal const val CHAT_DRAFT_CONVERSATION_PREFIX = "conversation:"
internal const val CHAT_DRAFT_ASSISTANT_PREFIX = "assistant:"

/**
 * key 从「新会话按助手」变成「按会话」：只会发生在发出第一条消息之后。
 * 这时输入框里剩下的内容是同一个会话的，要搬到新 key 上，不能当成上一个 key 的残留清掉。
 */
internal fun isDraftKeyMigration(previousKey: String, nextKey: String): Boolean {
    if (!previousKey.startsWith(CHAT_DRAFT_ASSISTANT_PREFIX)) return false
    if (!nextKey.startsWith(CHAT_DRAFT_CONVERSATION_PREFIX)) return false
    val conversationId = nextKey.removePrefix(CHAT_DRAFT_CONVERSATION_PREFIX)
    if (conversationId.isEmpty()) return false
    // 新会话 key 形如 `assistant:<assistantId>:<conversationId>`，必须指向同一个会话才算迁移。
    // 只比前缀会把「A 的落库确认」清到 B 的草稿上（A: assistant key -> 任意 conversation key 都算迁移）。
    // 旧版本留下的 `assistant:<assistantId>` 没有会话段，一律不算迁移，交给各自的会话 key。
    val ownerConversationId = previousKey
        .removePrefix(CHAT_DRAFT_ASSISTANT_PREFIX)
        .substringAfterLast(':', missingDelimiterValue = "")
    return ownerConversationId == conversationId
}

/** 每个 key 一个 JSON 文件，先写 `.new` 再改名，进程被杀不会留下半份草稿。 */
class FileChatDraftStorage(
    private val dir: File,
    private val json: Json = JsonInstant,
) : ChatDraftStorage {
    private fun fileOf(key: String): File = File(dir, chatDraftFileName(key))

    override suspend fun read(key: String): ChatDraft? = withContext(Dispatchers.IO) {
        val file = fileOf(key)
        if (!file.isFile) return@withContext null
        try {
            val text = AtomicFile(file).openRead().use { input ->
                input.readBytes().decodeToString()
            }
            json.decodeFromString<ChatDraft>(text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 文件坏了就当没有草稿，不能让一句坏 JSON 挡住聊天页
            Log.w(TAG, "read chat draft failed: $key", e)
            null
        }
    }

    override suspend fun write(key: String, draft: ChatDraft) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(ChatDraft.serializer(), draft)
        dir.mkdirs()
        val atomic = AtomicFile(fileOf(key))
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        Unit
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        val file = fileOf(key)
        if (!file.exists()) return@withContext
        try {
            AtomicFile(file).delete()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            file.delete()
        }
        Unit
    }
}

/**
 * 聊天输入草稿：内存里始终是最新的一份，写盘按 key 合并 + 短延迟，页面走了也能写完。
 *
 * 四个不变量：
 * 1. 内存优先：读盘迟到的结果不能覆盖已经保存过的新内容（切助手时旧输入不会写到新 key 上）；
 * 2. 同一个 key 的「取下一条 + 写盘」整体串行，先排队的旧稿绝不会落在新稿后面；
 * 3. 发送成功后 [ack] 只清「内容仍是发出去那份」的草稿，用户等确认时新打的字不会被顺手清掉；
 * 4. 已确认删除的 key 打墓碑（[deleted]）+ 每次内存变更递增 [versions]，迟到的读盘结果作废，
 *    不会把刚确认清掉的旧稿又读回来。
 *
 * 发送失败不需要特殊恢复：点发送时输入框不会清空，草稿一直留在原处。
 *
 * 存的是文字和附件引用（本地文件路径），附件本体不动，所以不影响文件清理。
 */
class ChatDraftStore(
    private val storage: ChatDraftStorage,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = CHAT_DRAFT_WRITE_DELAY_MS,
    private val onWriteError: (key: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private sealed interface Pending {
        data class Save(val draft: ChatDraft) : Pending
        data object Delete : Pending
    }

    private val lock = Any()
    private val writeMutex = Mutex()
    private val cache = HashMap<String, ChatDraft>()
    private val pending = HashMap<String, Pending>()
    private val jobs = HashMap<String, Job>()

    /** 每个 key 的内存变更计数：读盘前后不一致就说明期间有人改过，读到的旧稿不能生效。 */
    private val versions = HashMap<String, Long>()

    /** 已经确认删除（发送成功/会话删除）的 key；迟到的读盘结果和重复的删除都靠它挡住。 */
    private val deleted = LinkedHashSet<String>()

    /** 墓碑只需要挡住「刚删掉又马上读」的窗口，留最近这些就够，不随会话数无限增长。 */
    private fun markDeleted(key: String) {
        deleted.add(key)
        if (deleted.size > MAX_TOMBSTONES) {
            val eldest = deleted.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    /** 最近一次写进内存的内容，不读盘。 */
    fun cached(key: String): ChatDraft? = synchronized(lock) { cache[key] }

    /**
     * 读草稿。内存里已经有时直接用内存；读盘期间如果这个 key 被写过或确认删除，
     * 迟到的结果直接作废（否则会把刚清掉的旧稿又装回内存）。
     */
    suspend fun load(key: String): ChatDraft? {
        val version = synchronized(lock) {
            val cachedNow = cache[key]
            if (cachedNow != null) return cachedNow
            if (key in deleted) return null
            versions[key] ?: 0L
        }
        val stored = storage.read(key)?.takeIf { !it.isEmpty } ?: return null
        return synchronized(lock) {
            val cachedNow = cache[key]
            if (cachedNow != null) return@synchronized cachedNow
            val untouched = (versions[key] ?: 0L) == version && key !in deleted
            if (!untouched) return@synchronized null
            stored.also { cache[key] = it }
        }
    }

    /**
     * 记下当前输入（短延迟合并写盘）。内容和上一次一样时只更新内存，不重写文件：
     * 光标移动会不断产生新快照，不能每次都整份写一遍。
     */
    fun save(key: String, draft: ChatDraft) {
        submit(key, draft, debounce = true)
    }

    /**
     * 记下并立刻落盘（发送前保存快照、页面离开）。入队是同步的，
     * 所以之后到达的 [ack]/[forget] 一定排在这次写入后面。
     */
    fun saveNow(key: String, draft: ChatDraft) {
        submit(key, draft, debounce = false)
    }

    private fun submit(key: String, draft: ChatDraft, debounce: Boolean) {
        synchronized(lock) {
            val previous = cache[key]
            cache[key] = draft
            // 只有光标变了：盘上那份内容还是对的，不用重写
            if (previous != null && previous.sameContent(draft)) {
                when (val queued = pending[key]) {
                    is Pending.Save -> if (queued.draft.sameContent(draft)) return
                    Pending.Delete -> if (draft.isEmpty) return
                    null -> return
                }
            }
            if (draft.isEmpty) {
                // 本来就没有、也不等着删：不用白删一次
                if (previous == null && pending[key] !is Pending.Save) {
                    versions[key] = (versions[key] ?: 0L) + 1
                    return
                }
                pending[key] = Pending.Delete
            } else {
                // 用户又写了内容：之前的确认删除不再作数
                deleted.remove(key)
                pending[key] = Pending.Save(draft)
            }
            versions[key] = (versions[key] ?: 0L) + 1
            if (debounce) {
                if (jobs[key]?.isActive != true) {
                    jobs[key] = scope.launch {
                        delay(debounceMillis)
                        drain(key)
                    }
                }
            } else {
                // 不等 debounce：马上排一次写盘。旧的 debounce 任务照旧会跑，drain 里没东西就退出。
                jobs[key] = scope.launch { drain(key) }
            }
        }
    }

    /** 立刻把 [key] 还没写的改动落盘（切 key、页面离开时用）。 */
    suspend fun flush(key: String) {
        drain(key)
    }

    /** 后台把所有待写的 key 落盘；不挂起，供 `onCleared` 这类不能等的地方用。 */
    fun flushAll() {
        val keys = synchronized(lock) { pending.keys.toList() }
        if (keys.isEmpty()) return
        scope.launch { keys.forEach { key -> drain(key) } }
    }

    /**
     * 用户消息已落库：草稿可以清了。只有内存里那份还是发出去的内容才清，
     * 发送之后新打的字不能被这句确认顺手清掉。返回是否真的清了。
     */
    fun ack(key: String, sent: ChatDraft): Boolean {
        synchronized(lock) {
            val current = cache[key]
            if (current != null && !current.sameContent(sent)) return false
            cache.remove(key)
            versions[key] = (versions[key] ?: 0L) + 1
            markDeleted(key)
            pending[key] = Pending.Delete
        }
        scope.launch { drain(key) }
        return true
    }

    /** 这个 key 的草稿彻底不要了（删除会话时用）。 */
    fun forget(key: String) {
        synchronized(lock) {
            cache.remove(key)
            versions[key] = (versions[key] ?: 0L) + 1
            markDeleted(key)
            pending[key] = Pending.Delete
        }
        scope.launch { drain(key) }
    }

    /**
     * 取出下一条待写状态和写盘整个放在 [writeMutex] 里：两个任务不会先各取一条再逆序落盘。
     */
    private suspend fun drain(key: String) {
        writeMutex.withLock {
            while (true) {
                val next = synchronized(lock) {
                    pending.remove(key).also { if (it == null) jobs.remove(key) }
                } ?: return
                try {
                    when (next) {
                        is Pending.Save -> storage.write(key, next.draft)
                        Pending.Delete -> storage.delete(key)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // 写不进去就留在内存里，这个 key 下次有变化再试
                    onWriteError(key, e)
                }
            }
        }
    }
}

/**
 * 进程内唯一的草稿存储：多个聊天页（比如两个新会话）会写同一个助手 key，
 * 必须共用一份内存缓存和写盘队列。
 *
 * 主代理若要改成 Koin 单例：`single { ChatDraftStore(FileChatDraftStorage(File(get<Context>().filesDir, CHAT_DRAFT_DIR)), get()) }`，
 * 然后给 `ChatVM` 传同名参数即可，这个 holder 可以直接删掉。
 */
object ChatDraftStores {
    @Volatile
    private var instance: ChatDraftStore? = null

    /** 应用级作用域：发送确认（ack）不能挂在页面 ViewModel 上，否则切页就丢了。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun of(context: Context): ChatDraftStore = instance ?: synchronized(this) {
        instance ?: run {
            val dir = File(context.applicationContext.filesDir, CHAT_DRAFT_DIR)
            ChatDraftStore(
                storage = FileChatDraftStorage(dir),
                scope = appScope,
                onWriteError = { key, error -> Log.w(TAG, "write chat draft failed: $key", error) },
            ).also { instance = it }
        }
    }

    /** 测试或 DI 接线用：换成外部构造的实例。 */
    fun install(store: ChatDraftStore) {
        synchronized(this) { instance = store }
    }
}
