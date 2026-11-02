package me.rerere.rikkahub.data.ai.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionCredentialProvider
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import me.rerere.ai.provider.webService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

/**
 * 本地 web 会话仓库: 用户粘贴的 cookie 只以 AndroidKeyStore AES/GCM 密文保存在
 * `noBackupFilesDir/web-sessions/<providerUUID>.session`。
 *
 * 契约:
 * - 明文 cookie 只存在于内存与密文中, 不写设置、不进备份、不分享、不写日志 (也不记录异常正文);
 * - 每个 provider UUID 一个文件, AAD 绑定 UUID + 服务, 换服务或换 UUID 都解不开;
 * - 只在本机 Keystore 里维护独立 alias, 解密失败时提示用户重新导入, 绝不新建密钥去"解开"旧文件;
 * - 只管理自己的目录, 不读也不清 WebView / 浏览器的 cookie;
 * - [retainOnly] 只在显式调用时删除孤儿会话, 调用方必须在设置真正加载完成后才调用,
 *   不能把"还没加载"当成空集合清库。
 */
class WebSessionVault(context: Context) : WebSessionCredentialProvider {

    private val appContext: Context = context.applicationContext ?: context
    private val rootDir: File = File(appContext.noBackupFilesDir, SESSIONS_DIR)

    /** 每个 provider UUID 一把锁, 串行化 import / read / delete */
    private val idLocks = ConcurrentHashMap<String, Mutex>()

    /** 删除计数: 让删除前就发起、仍在等锁的旧导入无法把会话写回来 */
    private val idEpochs = ConcurrentHashMap<String, Long>()
    @Volatile private var retainedIds: Set<String>? = null

    override suspend fun getSession(setting: ProviderSetting.WebAccount): WebSessionCredential =
        session(setting.id, setting.webService)

    /** 读取一个 provider 的会话, 解密失败抛 [WebSessionException] 并提示重新导入。 */
    suspend fun session(id: Uuid, service: WebSessionService): WebSessionCredential {
        val file = sessionFile(id)
        return withContext(Dispatchers.IO) {
            lockFor(id.toString()).withLock {
                WebSessionCredential(service, readSession(id, service, file))
            }
        }
    }

    /**
     * 是否存在可用的会话。会真的尝试解密, 所以损坏/换机/换服务后返回 false,
     * 界面据此显示"需要重新导入", 而不是等到请求时才失败。
     */
    suspend fun hasSession(id: Uuid, service: WebSessionService): Boolean = withContext(Dispatchers.IO) {
        val file = sessionFile(id)
        lockFor(id.toString()).withLock {
            runCatching { readSession(id, service, file).isNotEmpty() }.getOrDefault(false)
        }
    }

    /**
     * 解析并加密保存一次粘贴的 cookie。解析在锁外完成, 写入时校验 epoch:
     * 如果期间该 provider 被删除, 旧导入直接失败而不是把会话写回来。
     */
    suspend fun importSession(id: Uuid, service: WebSessionService, raw: String) {
        val file = sessionFile(id)
        val key = id.toString()
        val epochAtStart = epochOf(key)
        val imported = try {
            withContext(Dispatchers.IO) { WebSessionImport.parse(service, raw) }
        } catch (e: WebSessionImportException) {
            throw WebSessionException(e.message ?: "The pasted cookies cannot be used for ${service.host}.")
        }
        val plaintext = WEB_SESSION_JSON
            .encodeToString(COOKIE_MAP_SERIALIZER, imported.cookies)
            .toByteArray(Charsets.UTF_8)
        try {
            withContext(Dispatchers.IO) {
                lockFor(key).withLock {
                    currentCoroutineContext().ensureActive()
                    if (epochOf(key) != epochAtStart || retainedIds?.let { key !in it } == true) {
                        throw WebSessionException(
                            "The web session was cleared while importing. Paste the cookies again."
                        )
                    }
                    writeAtomic(file, seal(id, service, plaintext))
                    if (retainedIds?.let { key !in it } == true) {
                        deleteQuietly(file)
                        throw WebSessionException("Provider was removed while importing; session was not retained.")
                    }
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /** 删除单个 provider 的会话。只删自己的密文文件, 不动 Keystore 密钥和其它会话。 */
    suspend fun clearSession(id: Uuid) {
        val file = sessionFile(id)
        val key = id.toString()
        withContext(Dispatchers.IO + NonCancellable) {
            lockFor(key).withLock {
                idEpochs.merge(key, 1L) { current, _ -> current + 1L }
                deleteQuietly(file)
            }
        }
    }

    /**
     * 删除已经不在 [ids] 里的孤儿会话文件 (provider 被删)。
     *
     * 只由调用方在设置加载完成后显式调用; 空集合等于清空全部会话, 见类注释。
     * 用户其它浏览器的 cookie 不在这个目录, 不会受影响。
     */
    suspend fun retainOnly(ids: Set<Uuid>) {
        val keep = ids.mapTo(HashSet()) { it.toString() }
        retainedIds = keep
        withContext(Dispatchers.IO) {
            val fileIds = rootDir.listFiles().orEmpty().mapNotNull(::sessionIdOf)
            // Also fence imports which already hold a per-id lock but have not created a file yet.
            val orphanIds = (fileIds + idLocks.keys).toSet() - keep
            for (sessionId in orphanIds) {
                lockFor(sessionId).withLock {
                    idEpochs.merge(sessionId, 1L) { current, _ -> current + 1L }
                    deleteQuietly(File(rootDir, "$sessionId$SESSION_EXTENSION"))
                }
            }
        }
    }

    override fun toString(): String = "WebSessionVault(sessions=[REDACTED])"

    // ---------------------------------------------------------------- 文件与锁

    private fun lockFor(key: String): Mutex = idLocks.computeIfAbsent(key) { Mutex() }

    private fun epochOf(key: String): Long = idEpochs[key] ?: 0L

    /** 文件名只接受规范 UUID, 防止路径穿越 */
    private fun sessionFile(id: Uuid): File {
        val name = id.toString()
        require(SESSION_ID_PATTERN.matches(name)) { "Invalid web session id" }
        return File(rootDir, "$name$SESSION_EXTENSION")
    }

    private fun sessionIdOf(file: File): String? {
        val name = file.name
        if (!name.endsWith(SESSION_EXTENSION)) return null
        val id = name.removeSuffix(SESSION_EXTENSION)
        return id.takeIf { SESSION_ID_PATTERN.matches(it) }
    }

    private fun writeAtomic(file: File, envelope: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream: FileOutputStream = try {
            atomic.startWrite()
        } catch (e: Exception) {
            throw WebSessionException("The web session could not be saved on this device. Try importing again.")
        }
        try {
            stream.write(envelope.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            runCatching { atomic.failWrite(stream) }
            throw WebSessionException("The web session could not be saved on this device. Try importing again.")
        }
    }

    private fun deleteQuietly(file: File) {
        AtomicFile(file).delete()
        if (file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()) {
            throw WebSessionException("Could not clear the local session; please try again.")
        }
    }

    // ---------------------------------------------------------------- 加解密

    private fun readSession(id: Uuid, service: WebSessionService, file: File): Map<String, String> {
        if (!file.isFile && !File(file.path + ".bak").isFile) throw WebSessionException(MISSING_SESSION)
        val envelope = try {
            AtomicFile(file).openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 128 * 1024) throw WebSessionException(REIMPORT_SESSION)
                    out.write(buffer, 0, count)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: Exception) {
            throw WebSessionException(REIMPORT_SESSION)
        }
        val plaintext = open(id, service, envelope)
        val cookies = try {
            WEB_SESSION_JSON.decodeFromString(COOKIE_MAP_SERIALIZER, plaintext.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw WebSessionException(REIMPORT_SESSION)
        } finally {
            plaintext.fill(0)
        }
        if (cookies.isEmpty()) throw WebSessionException(REIMPORT_SESSION)
        return cookies
    }

    /** `v1:<SERVICE>:<base64(iv + ciphertext)>`, 明文 JSON 只在密文里 */
    private fun seal(id: Uuid, service: WebSessionService, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, encryptKey())
            cipher.updateAAD(aad(id, service))
            val sealed = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size != IV_SIZE) throw WebSessionException("Web session encryption is unavailable on this device.")
            return "$ENVELOPE_VERSION:${service.name}:${Base64.encodeToString(iv + sealed, Base64.NO_WRAP)}"
        } catch (e: GeneralSecurityException) {
            throw WebSessionException("The web session could not be encrypted on this device. Try importing again.")
        }
    }

    private fun open(id: Uuid, service: WebSessionService, envelope: String): ByteArray {
        val parts = envelope.trim().split(':', limit = 3)
        if (parts.size != 3 || parts[0] != ENVELOPE_VERSION) throw WebSessionException(REIMPORT_SESSION)
        val storedService = WebSessionService.entries.firstOrNull { it.name == parts[1] }
            ?: throw WebSessionException(REIMPORT_SESSION)
        if (storedService != service) throw WebSessionException(REIMPORT_SESSION)
        val payload = try {
            Base64.decode(parts[2], Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw WebSessionException(REIMPORT_SESSION)
        }
        if (payload.size <= IV_SIZE) throw WebSessionException(REIMPORT_SESSION)
        val key = existingKey() ?: throw WebSessionException(REIMPORT_SESSION)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE))
            cipher.updateAAD(aad(id, service))
            cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            // 换机/清除数据/文件被改写: 不新建密钥去"解开"旧密文, 让用户重新导入
            throw WebSessionException(REIMPORT_SESSION)
        }
    }

    private fun aad(id: Uuid, service: WebSessionService): ByteArray =
        "$id|${service.name}".toByteArray(Charsets.UTF_8)

    /** 只读: 密钥不存在时返回 null, 绝不在解密路径上生成新密钥 */
    private fun existingKey(): SecretKey? {
        val keyStore = loadKeyStore() ?: return null
        return try {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** 加密路径才会创建密钥 */
    private fun encryptKey(): SecretKey = synchronized(KEY_LOCK) {
        existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun loadKeyStore(): KeyStore? = try {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val SESSIONS_DIR = "web-sessions"
        const val SESSION_EXTENSION = ".session"
        const val ENVELOPE_VERSION = "v1"

        const val KEYSTORE = "AndroidKeyStore"

        /** 与 SecretCipher 等其它用途的 alias 分开, 互不影响 */
        const val KEY_ALIAS = "rikkahub_web_session_vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256

        val SESSION_ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val COOKIE_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        val WEB_SESSION_JSON = Json { ignoreUnknownKeys = true }
        val KEY_LOCK = Any()

        const val MISSING_SESSION =
            "No web session is saved for this provider. Paste the cookies again to sign in."
        const val REIMPORT_SESSION =
            "This web session cannot be read on this device. Paste the cookies again to sign in."
    }
}
