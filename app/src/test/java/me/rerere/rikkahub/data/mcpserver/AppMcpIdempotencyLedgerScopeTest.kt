package me.rerere.rikkahub.data.mcpserver

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 一个 `idempotency_key` 在一个归属域里只能指向一笔操作：换了工具也算"换了操作"，必须被拒。
 *
 * 这些断言锁的是账本的身份键（`scope` + `key`，**不含**工具名）。如果工具名进了身份键，
 * "同一个 key 换工具"就会落成一条全新的 PENDING 并真的执行第二次写动作。
 */
class AppMcpIdempotencyLedgerScopeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun ledgerFile(name: String = "idempotency.json"): File =
        File(temporaryFolder.newFolder("m1-ledger-scope-$name-${System.nanoTime()}"), name)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private fun fingerprint(): String = AppMcpIdempotencyLedger.fingerprint(args("x" to "1"))

    @Test
    fun `same key with another tool never becomes a second write`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = fingerprint()
        assertTrue(ledger.decide("chat_rename", "shared", fingerprint) is AppMcpIdemDecision.Fresh)

        val other = ledger.decide("chat_stop", "shared", fingerprint)
        assertTrue("another tool must not get a Fresh permit: $other", other is AppMcpIdemDecision.Rejected)
        assertEquals("idempotency_key_reuse", (other as AppMcpIdemDecision.Rejected).code)

        // 只有第一笔那条 PENDING，第二笔既没落盘成新条目，也没有被执行。
        val entries = ledger.snapshot()
        assertEquals(1, entries.size)
        assertEquals("chat_rename", entries.first().tool)
        assertEquals(AppMcpLedgerStatus.PENDING.name, entries.first().status)
    }

    @Test
    fun `a completed write is not replayed to another tool`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = fingerprint()
        ledger.decide("chat_rename", "k1", fingerprint)
        ledger.complete("chat_rename", "k1", """{"ok":true,"tool":"chat_rename"}""")

        val other = ledger.decide("chat_stop", "k1", fingerprint)
        assertTrue("result of another tool must never be replayed: $other", other is AppMcpIdemDecision.Rejected)
        assertEquals("idempotency_key_reuse", (other as AppMcpIdemDecision.Rejected).code)
    }

    @Test
    fun `the same key in another scope is a different operation`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = fingerprint()
        val assistantA = "11111111-1111-1111-1111-111111111111"
        val assistantB = "22222222-2222-2222-2222-222222222222"

        assertTrue(ledger.decide("chat_send", "k1", fingerprint, assistantA) is AppMcpIdemDecision.Fresh)
        ledger.complete("chat_send", "k1", """{"ok":true}""", assistantA)
        assertTrue(ledger.decide("chat_send", "k1", fingerprint, assistantA) is AppMcpIdemDecision.Replay)
        assertTrue(ledger.decide("chat_send", "k1", fingerprint, assistantB) is AppMcpIdemDecision.Fresh)

        assertEquals(AppMcpLedgerStatus.DONE.name, ledger.entryOf("chat_send", "k1", assistantA)?.status)
        assertEquals(AppMcpLedgerStatus.PENDING.name, ledger.entryOf("chat_send", "k1", assistantB)?.status)
        assertNull(ledger.entryOf("chat_send", "k1", "device"))
    }

    @Test
    fun `a legacy ledger holding one key for two tools fails closed`() {
        // 旧写法把工具名拼进身份键，盘上可能真有两个工具共用同一个 key。加载后只能有一条
        // 能对上号，另一条必须被拒——绝不能拿到 Fresh 许可再执行一次，也绝不能把别人的结果当回放。
        val file = ledgerFile("legacy.json")
        file.parentFile.mkdirs()
        file.writeText(
            """
            [
              {"key":"shared","tool":"chat_rename","argsHash":"{\"x\":\"1\"}","status":"DONE",
               "resultJson":"{\"ok\":true,\"tool\":\"chat_rename\"}","at":1,"scope":""},
              {"key":"shared","tool":"chat_stop","argsHash":"{\"x\":\"1\"}","status":"PENDING",
               "at":2,"scope":""}
            ]
            """.trimIndent(),
        )

        val ledger = AppMcpIdempotencyLedger(file)
        listOf("chat_rename", "chat_stop").forEach { tool ->
            val decision = ledger.decide(tool, "shared", fingerprint())
            assertTrue("$tool got a Fresh permit from a duplicate legacy key: $decision",
                decision is AppMcpIdemDecision.Rejected)
            val code = (decision as AppMcpIdemDecision.Rejected).code
            assertTrue("unexpected rejection code $code",
                code == "idempotency_key_reuse" || code == "unknown_side_effect")
        }
    }

    @Test
    fun `an unreadable ledger rejects every scope instead of granting a fresh permit`() {
        // 盘上记过什么已经不知道了：任何归属域都不许拿到 Fresh，否则就是一次重复的设备写。
        val file = ledgerFile("broken.json")
        file.parentFile.mkdirs()
        file.writeText(
            "[{\"key\":\"k1\",\"tool\":\"chat_send\",\"argsHash\":\"{\\\"x\\\":\\\"1\\\"}\",\"status\":\"PEND",
        )
        val before = file.readBytes()

        val ledger = AppMcpIdempotencyLedger(file)
        listOf("assistant-a", "assistant-b", "", "device").forEach { scope ->
            val decision = ledger.decide("chat_send", "k1", fingerprint(), scope)
            assertTrue(
                "scope '$scope' got a permit from an unreadable ledger: $decision",
                decision is AppMcpIdemDecision.Rejected,
            )
            assertEquals("ledger_unreadable", (decision as AppMcpIdemDecision.Rejected).code)
        }
        assertTrue("the broken ledger must be preserved", before.contentEquals(file.readBytes()))
        assertTrue(ledger.snapshot().isEmpty())
    }
}
