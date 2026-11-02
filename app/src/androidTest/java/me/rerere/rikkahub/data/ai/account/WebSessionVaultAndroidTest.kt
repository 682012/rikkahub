package me.rerere.rikkahub.data.ai.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

/**
 * 真机 / 模拟器上的 Keystore 往返测试。每个用例用唯一 provider UUID, 结束时 clearSession,
 * 不删除全局 alias (其它测试和已保存的会话仍然可用)。
 */
@RunWith(AndroidJUnit4::class)
class WebSessionVaultAndroidTest {

    private val context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        object : android.content.ContextWrapper(base) {
            private val testRoot = File(base.noBackupFilesDir, "vault-test-${Uuid.random()}").apply { mkdirs() }
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = testRoot
        }
    }
    private val vault by lazy { WebSessionVault(context) }
    private val createdIds = mutableListOf<Uuid>()

    private fun newId(): Uuid = Uuid.random().also { createdIds += it }

    private fun sessionFile(id: Uuid): File =
        File(File(context.noBackupFilesDir, SESSIONS_DIR), "$id.session")

    @After
    fun clearCreatedSessions() = runBlocking {
        for (id in createdIds) vault.clearSession(id)
        createdIds.clear()
        context.noBackupFilesDir.deleteRecursively()
    }

    @Test
    fun importedSessionRoundTripsAndFileHoldsOnlyCiphertext() = runBlocking {
        val id = newId()

        vault.importSession(id, WebSessionService.GEMINI, GEMINI_HEADER)

        assertTrue(vault.hasSession(id, WebSessionService.GEMINI))
        val credential = vault.session(id, WebSessionService.GEMINI)
        assertEquals(mapOf("__Secure-1PSID" to PSID, "__Secure-1PSIDTS" to PSIDTS), credential.cookies)
        assertEquals(
            "__Secure-1PSID=$PSID; __Secure-1PSIDTS=$PSIDTS",
            credential.headerFor(WebSessionService.GEMINI.host),
        )

        val stored = sessionFile(id).readText()
        assertTrue(stored.startsWith("v1:GEMINI:"))
        assertFalse(stored.contains(PSID))
        assertFalse(stored.contains(PSIDTS))
        assertFalse(stored.contains("__Secure-1PSID"))
        assertFalse(stored.contains("sessionKey"))
        assertFalse(credential.toString().contains(PSID))
        assertFalse(vault.toString().contains(PSID))
    }

    @Test
    fun servicesAreIsolatedSoOneProviderCannotReadTheOther() = runBlocking {
        val id = newId()

        vault.importSession(id, WebSessionService.CLAUDE, "sessionKey=$CLAUDE_KEY")

        assertTrue(vault.hasSession(id, WebSessionService.CLAUDE))
        assertFalse(vault.hasSession(id, WebSessionService.GEMINI))
        val failure = expectFailure { vault.session(id, WebSessionService.GEMINI) }
        assertTrue(failure.message.orEmpty().contains("Paste the cookies again"))
        assertFalse(failure.message.orEmpty().contains(CLAUDE_KEY))
    }

    @Test
    fun aadBindsServiceAndProviderId() = runBlocking {
        val id = newId()
        val other = newId()

        vault.importSession(id, WebSessionService.GEMINI, GEMINI_HEADER)
        val tampered = sessionFile(id).readText().replace("v1:GEMINI:", "v1:CLAUDE:")
        sessionFile(id).writeText(tampered)

        // 把 GEMINI 密文伪装成 CLAUDE: AAD 不匹配, 解不开
        assertFalse(vault.hasSession(id, WebSessionService.CLAUDE))
        val serviceFailure = expectFailure { vault.session(id, WebSessionService.CLAUDE) }
        assertFalse(serviceFailure.message.orEmpty().contains(PSID))

        // 同一段密文换到另一个 provider UUID 也解不开: AAD 绑定 id
        File(sessionFile(id).parentFile, "$other.session")
            .writeText(tampered.replace("v1:CLAUDE:", "v1:GEMINI:"))
        assertFalse(vault.hasSession(other, WebSessionService.GEMINI))
        val idFailure = expectFailure { vault.session(other, WebSessionService.GEMINI) }
        assertFalse(idFailure.message.orEmpty().contains(PSID))
    }

    @Test
    fun undecryptableFileFailsFriendlyWithoutEchoingInput() = runBlocking {
        val id = newId()
        vault.importSession(id, WebSessionService.GEMINI, GEMINI_HEADER)

        val garbage = Base64.encodeToString(ByteArray(64) { 0x5A }, Base64.NO_WRAP)
        sessionFile(id).writeText("v1:GEMINI:$garbage")

        assertFalse(vault.hasSession(id, WebSessionService.GEMINI))
        val failure = expectFailure { vault.session(id, WebSessionService.GEMINI) }
        assertTrue(failure.message.orEmpty().contains("Paste the cookies again"))
        assertFalse(failure.message.orEmpty().contains(PSID))
        assertFalse(failure.message.orEmpty().contains("ZZZZ"))
    }

    @Test
    fun unknownProviderHasNoSession() = runBlocking {
        val id = newId()

        assertFalse(vault.hasSession(id, WebSessionService.GEMINI))
        assertFalse(sessionFile(id).exists())
        val failure = expectFailure { vault.session(id, WebSessionService.GEMINI) }
        assertTrue(failure.message.orEmpty().contains("Paste the cookies again"))
    }

    @Test
    fun clearingOneSessionKeepsTheKeyAndEveryOtherSession() = runBlocking {
        val gemini = newId()
        val claude = newId()
        vault.importSession(gemini, WebSessionService.GEMINI, GEMINI_HEADER)
        vault.importSession(claude, WebSessionService.CLAUDE, "sessionKey=$CLAUDE_KEY")

        vault.clearSession(gemini)

        assertFalse(vault.hasSession(gemini, WebSessionService.GEMINI))
        assertFalse(sessionFile(gemini).exists())
        // 其它会话仍可解密, 说明没有删掉全局密钥, 也没有影响别的测试
        assertEquals(CLAUDE_KEY, vault.session(claude, WebSessionService.CLAUDE).cookies["sessionKey"])

        // 同一个 provider 重新导入仍然可用
        vault.importSession(gemini, WebSessionService.GEMINI, GEMINI_HEADER)
        assertTrue(vault.hasSession(gemini, WebSessionService.GEMINI))
    }

    @Test
    fun retainOnlyDeletesOnlyOrphanSessions() = runBlocking {
        val keep = newId()
        val orphan = newId()
        vault.importSession(keep, WebSessionService.GEMINI, GEMINI_HEADER)
        vault.importSession(orphan, WebSessionService.CLAUDE, "sessionKey=$CLAUDE_KEY")
        val unrelated = File(sessionFile(keep).parentFile, "notes.txt").apply { writeText("keep me") }

        try {
            vault.retainOnly(setOf(keep))

            assertTrue(vault.hasSession(keep, WebSessionService.GEMINI))
            assertFalse(vault.hasSession(orphan, WebSessionService.CLAUDE))
            assertFalse(sessionFile(orphan).exists())
            assertTrue(unrelated.exists())
        } finally {
            unrelated.delete()
        }
    }

    @Test
    fun oversizedInputIsRejectedWithoutWritingAFile() = runBlocking {
        val id = newId()

        val failure = expectFailure {
            vault.importSession(id, WebSessionService.GEMINI, "__Secure-1PSID=" + "a".repeat(70_000))
        }

        assertFalse(failure.message.orEmpty().contains("aaaaaaaa"))
        assertFalse(sessionFile(id).exists())
        assertFalse(vault.hasSession(id, WebSessionService.GEMINI))
    }

    @Test
    fun tooManyCookiesAndForeignKeyAreRejectedWithoutWritingAFile() = runBlocking {
        val id = newId()

        val many = (1..40).joinToString("; ") { "cookie$it=value$it" }
        val manyFailure = expectFailure { vault.importSession(id, WebSessionService.GEMINI, many) }
        assertTrue(manyFailure.message.orEmpty().contains("Too many cookies"))
        assertFalse(sessionFile(id).exists())

        val foreign = """[{"name":"__Secure-1PSID","value":"$PSID","domain":".youtube.com"}]"""
        val foreignFailure = expectFailure { vault.importSession(id, WebSessionService.GEMINI, foreign) }
        assertFalse(foreignFailure.message.orEmpty().contains(PSID))
        assertFalse(sessionFile(id).exists())
    }

    @Test
    fun importingAgainReplacesTheStoredSession() = runBlocking {
        val id = newId()

        vault.importSession(id, WebSessionService.GEMINI, "__Secure-1PSID=first-$PSID")
        vault.importSession(id, WebSessionService.GEMINI, "__Secure-1PSID=second-$PSID")

        val cookies = vault.session(id, WebSessionService.GEMINI).cookies
        assertEquals(mapOf("__Secure-1PSID" to "second-$PSID"), cookies)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): WebSessionException = try {
        block()
        throw AssertionError("expected a WebSessionException")
    } catch (e: WebSessionException) {
        e
    }

    private companion object {
        const val SESSIONS_DIR = "web-sessions"
        const val PSID = "psid-local-test-value"
        const val PSIDTS = "psidts-local-test-value"
        const val CLAUDE_KEY = "sk-ant-sid01-local-test-value"
        const val GEMINI_HEADER = "Cookie: __Secure-1PSID=$PSID; __Secure-1PSIDTS=$PSIDTS"
    }
}
