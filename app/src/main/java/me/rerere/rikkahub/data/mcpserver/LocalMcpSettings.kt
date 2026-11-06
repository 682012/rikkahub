package me.rerere.rikkahub.data.mcpserver

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

const val LOCAL_MCP_DEFAULT_PORT = 8766
const val LOCAL_MCP_MIN_PORT = 1024
const val LOCAL_MCP_MAX_PORT = 65535

/** 请求体读取硬上限：先看 Content-Length，再按块读，绝不先 readText 一整个流。 */
const val LOCAL_MCP_MAX_REQUEST_BYTES = 256 * 1024

/** 同时处理的 HTTP 请求数上限；超出的直接 429，不排队堆积。 */
const val LOCAL_MCP_MAX_CONCURRENT_REQUESTS = 8

/**
 * 读完一个请求体的硬超时（毫秒）。没有这个上限，一个只用 8KiB 速率滴流 body 的连接就能一直占着
 * 并发许可和一条 CIO 连接，属于最省成本的 slowloris。
 */
const val LOCAL_MCP_BODY_READ_TIMEOUT_MS = 10_000L

/** 服务停止时允许等待在途请求结束的宽限（毫秒），以及随后强停的上限。 */
const val LOCAL_MCP_STOP_GRACE_MS = 0L
const val LOCAL_MCP_STOP_TIMEOUT_MS = 800L

/** 停止服务时同步等待引擎收尾的上限；超过就走后台收尾，绝不无限阻塞调用线程（可能是主线程）。 */
const val LOCAL_MCP_STOP_JOIN_MS = 250L

private const val LOCAL_MCP_TOKEN_PREFIX = "lmcp_"
private const val LOCAL_MCP_TOKEN_BYTES = 32 // 256 bit
private const val LOCAL_MCP_MIN_TOKEN_LENGTH = 40 // 前缀 + 43 位 base64url

/**
 * 本机 MCP 的全部可变设置。默认：关、只绑 127.0.0.1、随机 token、只读开、其余全关。
 *
 * [assistantId] 是**用户从 UI 里显式选的助手 UUID**；`null` 表示"不暴露内置会话"，
 * 绝不能被当成"所有助手都可用"。
 */
@Serializable
data class LocalMcpSettings(
    val enabled: Boolean = false,
    val port: Int = LOCAL_MCP_DEFAULT_PORT,
    val token: String = newLocalMcpToken(),
    val assistantId: String? = null,
    val allowRead: Boolean = true,
    val allowMutations: Boolean = false,
    val allowModelRequests: Boolean = false,
    val allowDevice: Boolean = false,
    val allowAutomation: Boolean = false,
) {
    val endpoint: String get() = localMcpEndpoint(port)

    val hasAssistant: Boolean get() = !assistantId.isNullOrBlank()

    /** 刻意脱敏：这个对象可能进日志/崩溃报告，token 不能出现在里面。 */
    override fun toString(): String =
        "LocalMcpSettings(enabled=$enabled, port=$port, token=${maskToken(token)}, " +
            "assistantId=$assistantId, allowRead=$allowRead, allowMutations=$allowMutations, " +
            "allowModelRequests=$allowModelRequests, allowDevice=$allowDevice, allowAutomation=$allowAutomation)"
}

fun localMcpEndpoint(port: Int): String = "http://127.0.0.1:$port/mcp"

fun maskToken(token: String): String = when {
    token.isBlank() -> "«empty»"
    else -> "***(${token.length})"
}

/** 生成 256bit 随机 token（32 字节 SecureRandom + base64url 无填充）。 */
fun newLocalMcpToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(LOCAL_MCP_TOKEN_BYTES)
    random.nextBytes(bytes)
    return LOCAL_MCP_TOKEN_PREFIX + base64UrlNoPadding(bytes)
}

fun isLocalMcpTokenAcceptable(token: String): Boolean = token.length >= LOCAL_MCP_MIN_TOKEN_LENGTH

enum class LocalMcpSettingsProblem {
    PORT_OUT_OF_RANGE,
    PORT_PRIVILEGED,
    TOKEN_TOO_SHORT,
    ASSISTANT_NOT_SELECTED,
}

fun validateLocalMcpPort(port: Int): LocalMcpSettingsProblem? = when {
    port < LOCAL_MCP_MIN_PORT && port > 0 -> LocalMcpSettingsProblem.PORT_PRIVILEGED
    port < LOCAL_MCP_MIN_PORT || port > LOCAL_MCP_MAX_PORT -> LocalMcpSettingsProblem.PORT_OUT_OF_RANGE
    else -> null
}

/**
 * @param requireAssistant 只有真的会暴露"内置会话"工具时才要求选助手；
 *   一个工具都不暴露的话不该拿这个卡用户。
 */
fun validateLocalMcpSettings(
    settings: LocalMcpSettings,
    requireAssistant: Boolean = false,
): List<LocalMcpSettingsProblem> = buildList {
    validateLocalMcpPort(settings.port)?.let { add(it) }
    if (!isLocalMcpTokenAcceptable(settings.token)) add(LocalMcpSettingsProblem.TOKEN_TOO_SHORT)
    if (requireAssistant && !settings.hasAssistant) add(LocalMcpSettingsProblem.ASSISTANT_NOT_SELECTED)
}

/** 落盘/更新前的规整：坏端口回默认、空 token 重新生成、空白助手当没选。 */
fun sanitizeLocalMcpSettings(
    settings: LocalMcpSettings,
    random: SecureRandom = SecureRandom(),
): LocalMcpSettings {
    val port = if (validateLocalMcpPort(settings.port) == null) settings.port else LOCAL_MCP_DEFAULT_PORT
    val token = if (isLocalMcpTokenAcceptable(settings.token)) settings.token else newLocalMcpToken(random)
    val assistantId = settings.assistantId?.trim()?.takeIf { it.isNotEmpty() }
    return settings.copy(port = port, token = token, assistantId = assistantId)
}

/** 这个分类是否被用户授权。 */
fun LocalMcpSettings.allows(category: LocalMcpToolCategory): Boolean = when (category) {
    LocalMcpToolCategory.READ -> allowRead
    LocalMcpToolCategory.MUTATION -> allowMutations
    LocalMcpToolCategory.MODEL -> allowModelRequests
    LocalMcpToolCategory.DEVICE -> allowDevice
    LocalMcpToolCategory.AUTOMATION -> allowAutomation
}

/** 按设置过滤工具清单，顺序保持宿主给的顺序。 */
fun LocalMcpSettings.visibleTools(tools: List<LocalMcpTool>): List<LocalMcpTool> =
    tools.filter { allows(it.category) }

/**
 * 生成给 MCP 客户端粘贴的配置 JSON（只在用户点"复制"时调用，不要自动写日志/通知）：
 *
 * ```json
 * {"mcpServers":{"local-app":{"url":"http://127.0.0.1:8766/mcp",
 *   "headers":{"Authorization":"Bearer lmcp_..."}}}}
 * ```
 */
fun localMcpClientConfigJson(
    settings: LocalMcpSettings,
    serverKey: String = "local-app",
): String {
    val config = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(serverKey) {
                put("url", settings.endpoint)
                putJsonObject("headers") {
                    put("Authorization", "Bearer ${settings.token}")
                }
            }
        }
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config)
}

/**
 * 独立的一份本地设置（JSON 文件，App 私有目录），不进 Settings.kt / DataStore：
 * 默认关、可整份撤销、和共享 Settings 的合并/迁移完全不冲突。
 *
 * 写盘是"先改内存再异步落盘"，连续多次修改只会写最后一次；token 只在 [rotateToken] 或首次
 * 生成时变化，读取旧文件不会静默换掉用户手上的令牌。
 */
class LocalMcpSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()
    private val dirty = AtomicBoolean(false)

    private val file: File get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<LocalMcpSettings> = _state.asStateFlow()

    init {
        // 首次安装 / 文件缺失：把带随机 token 的默认值落盘，避免每次启动都换 token
        if (!file.isFile) schedulePersist()
    }

    fun current(): LocalMcpSettings = _state.value

    /**
     * 纯内存改 + 异步落盘。transform 里不要做 IO；返回值就是生效后的设置。
     */
    fun update(transform: (LocalMcpSettings) -> LocalMcpSettings): LocalMcpSettings {
        val next = sanitizeLocalMcpSettings(transform(_state.value))
        _state.value = next
        schedulePersist()
        return next
    }

    /** 换新令牌：旧客户端立刻失效。调用方负责提示"需要重新粘贴配置"。 */
    fun rotateToken(random: SecureRandom = SecureRandom()): LocalMcpSettings {
        val next = _state.value.copy(token = newLocalMcpToken(random))
        _state.value = next
        schedulePersist()
        return next
    }

    private fun loadFromDisk(): LocalMcpSettings {
        val target = file
        if (!target.isFile) return LocalMcpSettings()
        return runCatching {
            sanitizeLocalMcpSettings(json.decodeFromString(LocalMcpSettings.serializer(), target.readText()))
        }.getOrElse { error ->
            Log.w(TAG, "read local mcp settings failed, fallback to disabled", error)
            LocalMcpSettings()
        }
    }

    private fun schedulePersist() {
        if (!dirty.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                dirty.set(false)
                val snapshot = _state.value
                writeMutex.withLock { runCatching { writeToDisk(snapshot) } }
                    .onFailure { error -> Log.w(TAG, "write local mcp settings failed", error) }
                if (!dirty.get()) break
            }
        }
    }

    private fun writeToDisk(settings: LocalMcpSettings) {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.part")
        temp.writeText(json.encodeToString(LocalMcpSettings.serializer(), settings))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "LocalMcpSettings"
        const val DIRECTORY = "local_mcp"
        const val FILE_NAME = "settings.json"
    }
}

/**
 * 只读地看一眼用户意图（`enabled`），**不**构造 store、不生成 token、不注册任何东西。
 *
 * FGS 恢复入口（[LocalMcpService.restoreIfEnabled]）在 Koin 还没起来时用它兜底：早先的实现每次
 * 都 `LocalMcpSettingsStore(context)`，等于每次都新建一个 StateFlow/scope，文件缺失时还会再生成一份
 * 新 token 落盘——恢复入口变成"悄悄换令牌"。
 */
fun localMcpEnabledOnDisk(context: Context): Boolean = runCatching {
    val file = File(File(context.filesDir, "local_mcp"), "settings.json")
    if (!file.isFile) {
        false
    } else {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(LocalMcpSettings.serializer(), file.readText())
            .enabled
    }
}.getOrDefault(false)

private fun base64UrlNoPadding(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
        val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        out.append(alphabet[(chunk ushr 18) and 0x3F])
        out.append(alphabet[(chunk ushr 12) and 0x3F])
        out.append(alphabet[(chunk ushr 6) and 0x3F])
        out.append(alphabet[chunk and 0x3F])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val chunk = (bytes[index].toInt() and 0xFF) shl 16
            out.append(alphabet[(chunk ushr 18) and 0x3F])
            out.append(alphabet[(chunk ushr 12) and 0x3F])
        }

        2 -> {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            out.append(alphabet[(chunk ushr 18) and 0x3F])
            out.append(alphabet[(chunk ushr 12) and 0x3F])
            out.append(alphabet[(chunk ushr 6) and 0x3F])
        }
    }
    return out.toString()
}
