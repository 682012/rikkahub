package me.rerere.rikkahub.data.devicecontrol

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备操控授权的本地存储：App 私有目录下的一份 JSON，默认全关。
 *
 * 为什么不进 Settings.kt：这份授权是设备级、默认关闭、随时可能整体撤销的开关，独立文件既不和共享
 * Settings 的合并冲突，也让"撤销授权"就是删这一份文件。
 *
 * 两条硬要求：
 * - **构造不在主线程读盘**：DI 里 new 出来时可能就在主线程。这里构造只给"默认关"，
 *   真正的读盘放在 IO 协程里，调用方用 [awaitLoaded] 等它，避免主线程磁盘 IO 和"读之前误判未授权"。
 * - **原子写**：临时文件写完 fsync 再 rename；rename 失败也绝不直接截断正式文件（半截 JSON = 授权状态不明）。
 */
class DeviceControlPreferences(private val context: Context) {

    @Serializable
    private data class Payload(val version: Int = 1, val grants: DeviceControlGrants = DeviceControlGrants())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private val file: File get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val ready = CompletableDeferred<Unit>()
    private val written = AtomicBoolean(false)
    private val bootstrap = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 初始值 = 全部关闭：在磁盘读完之前，任何判定都是"未授权"，这是安全方向。 */
    private val _grants = MutableStateFlow(DeviceControlGrants())
    val grants: StateFlow<DeviceControlGrants> = _grants.asStateFlow()

    init {
        bootstrap.launch {
            val loaded = runCatching { readFromDisk() }.getOrDefault(DeviceControlGrants())
            // 如果读盘期间用户已经改过授权，以内存里的新值为准，别用旧文件覆盖
            if (!written.get()) _grants.value = loaded
            ready.complete(Unit)
        }
    }

    fun current(): DeviceControlGrants = _grants.value

    /** 等到真的读过盘（最多等 [timeoutMs]）；超时也继续跑，因为此时判定方向是"关"。 */
    suspend fun awaitLoaded(timeoutMs: Long = 2_000) {
        withTimeoutOrNull(timeoutMs) { ready.await() }
    }

    /** 测试/调试用：同步读一次盘并替换内存值。 */
    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { readFromDisk() }
        if (!written.get()) _grants.value = loaded
        if (!ready.isCompleted) ready.complete(Unit)
    }

    suspend fun update(block: (DeviceControlGrants) -> DeviceControlGrants): DeviceControlGrants = mutex.withLock {
        awaitLoaded()
        val next = block(_grants.value).sanitized()
        written.set(true)
        _grants.value = next
        withContext(Dispatchers.IO) { writeToDisk(next) }
        next
    }

    private fun readFromDisk(): DeviceControlGrants {
        val target = file
        if (!target.isFile) return DeviceControlGrants()
        return runCatching {
            json.decodeFromString(Payload.serializer(), target.readText()).grants.sanitized()
        }.getOrElse { error ->
            // 读坏了就当没授权（默认关），不静默放行
            Log.w(TAG, "read device control grants failed, fallback to disabled", error)
            DeviceControlGrants()
        }
    }

    private fun writeToDisk(grants: DeviceControlGrants) {
        val target = file
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$FILE_NAME.part")
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(Payload.serializer(), Payload(grants = grants)).toByteArray())
                output.flush()
                output.fd.sync()
            }
            if (!temp.renameTo(target)) {
                // 不做"覆盖正式文件"的降级：宁可这次没保存，也不留半截 JSON
                Log.w(TAG, "atomic rename failed, keeping the previous grants file")
                temp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "write device control grants failed", error) }
    }

    private companion object {
        const val TAG = "DeviceControlPrefs"
        const val DIRECTORY = "device_control"
        const val FILE_NAME = "grants.json"
    }
}
