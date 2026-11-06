package me.rerere.rikkahub.data.mcpserver

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 幂等账本测试：这些断言就是"传输失败不能重放写动作"的可执行版本。
 */
class AppMcpIdempotencyLedgerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun ledgerFile(name: String = "idempotency.json"): File =
        File(temporaryFolder.newFolder("r22-mcp-$name-${System.nanoTime()}"), name)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun `fresh then complete then replay returns the recorded result`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val first = ledger.decide("chat_send", "k1", AppMcpIdempotencyLedger.fingerprint(args("text" to "hi")))
        assertTrue(first is AppMcpIdemDecision.Fresh)

        ledger.complete("chat_send", "k1", """{"ok":true,"status":"queued"}""")

        val second = ledger.decide("chat_send", "k1", AppMcpIdempotencyLedger.fingerprint(args("text" to "hi")))
        assertTrue(second is AppMcpIdemDecision.Replay)
        assertEquals("""{"ok":true,"status":"queued"}""", (second as AppMcpIdemDecision.Replay).resultJson)
    }

    @Test
    fun `same key with different arguments is rejected and nothing runs`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        ledger.decide("chat_send", "k1", AppMcpIdempotencyLedger.fingerprint(args("text" to "hi")))
        val second = ledger.decide("chat_send", "k1", AppMcpIdempotencyLedger.fingerprint(args("text" to "bye")))
        assertTrue(second is AppMcpIdemDecision.Rejected)
        assertEquals("idempotency_key_reuse", (second as AppMcpIdemDecision.Rejected).code)
    }

    @Test
    fun `same key reused for another tool is rejected`() {
        val ledger = AppMcpIdempotencyLedger(ledgerFile())
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("x" to "1"))
        ledger.decide("chat_rename", "shared", fingerprint)
        val other = ledger.decide("chat_stop", "shared", fingerprint)
        assertTrue(other is AppMcpIdemDecision.Rejected)
        assertEquals("idempotency_key_reuse", (other as AppMcpIdemDecision.Rejected).code)
    }

    @Test
    fun `interrupted pending write is never executed again`() {
        val file = ledgerFile()
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("text" to "hello"))
        // 第一次：落盘 PENDING 后"进程死掉"，没有 complete/fail。
        val before = AppMcpIdempotencyLedger(file)
        assertTrue(before.decide("chat_send", "k1", fingerprint) is AppMcpIdemDecision.Fresh)

        // 重启后重试同一个 key：必须是 unknown_side_effect，而不是再执行一次。
        val after = AppMcpIdempotencyLedger(file)
        val retry = after.decide("chat_send", "k1", fingerprint)
        assertTrue(retry is AppMcpIdemDecision.Rejected)
        assertEquals("unknown_side_effect", (retry as AppMcpIdemDecision.Rejected).code)
        assertEquals(AppMcpLedgerStatus.PENDING.name, after.entryOf("chat_send", "k1")?.status)

        // 还没有结果之前，同一个 ledger 自己也不会放行第二次。
        val again = after.decide("chat_send", "k1", fingerprint)
        assertTrue(again is AppMcpIdemDecision.Rejected)
    }

    @Test
    fun `failed write is not replayed either`() {
        val file = ledgerFile()
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("text" to "hello"))
        val ledger = AppMcpIdempotencyLedger(file)
        ledger.decide("chat_send", "k1", fingerprint)
        ledger.fail("chat_send", "k1", "write_outcome_unknown")

        val retry = AppMcpIdempotencyLedger(file).decide("chat_send", "k1", fingerprint)
        assertTrue(retry is AppMcpIdemDecision.Rejected)
        assertEquals("unknown_side_effect", (retry as AppMcpIdemDecision.Rejected).code)
    }

    @Test
    fun `entry survives a restart and replays after completion`() {
        val file = ledgerFile()
        val fingerprint = AppMcpIdempotencyLedger.fingerprint(args("title" to "new"))
        AppMcpIdempotencyLedger(file).apply {
            decide("chat_rename", "k9", fingerprint)
            complete("chat_rename", "k9", """{"ok":true}""")
        }
        val replay = AppMcpIdempotencyLedger(file).decide("chat_rename", "k9", fingerprint)
        assertEquals("""{"ok":true}""", (replay as AppMcpIdemDecision.Replay).resultJson)
    }

    @Test
    fun `fingerprint ignores key order`() {
        val a = AppMcpIdempotencyLedger.fingerprint(args("a" to "1", "b" to "2"))
        val b = AppMcpIdempotencyLedger.fingerprint(args("b" to "2", "a" to "1"))
        assertEquals(a, b)
    }

    @Test
    fun `different arguments change the fingerprint`() {
        val a = AppMcpIdempotencyLedger.fingerprint(args("a" to "1"))
        val b = AppMcpIdempotencyLedger.fingerprint(args("a" to "2"))
        assertTrue(a != b)
    }

    @Test
    fun `clearFinished keeps pending entries`() {
        val file = ledgerFile()
        val ledger = AppMcpIdempotencyLedger(file)
        ledger.decide("chat_send", "done", AppMcpIdempotencyLedger.fingerprint(args("a" to "1")))
        ledger.complete("chat_send", "done", "{}")
        ledger.decide("chat_send", "pending", AppMcpIdempotencyLedger.fingerprint(args("a" to "2")))
        ledger.clearFinished()

        val remaining = ledger.snapshot()
        assertEquals(1, remaining.size)
        assertEquals("pending", remaining.first().key)
        // 清理之后那条未知副作用仍然挡住重放
        val retry = ledger.decide("chat_send", "pending", AppMcpIdempotencyLedger.fingerprint(args("a" to "2")))
        assertTrue(retry is AppMcpIdemDecision.Rejected)
    }

    @Test
    fun `pruning only drops finished entries`() {
        val file = ledgerFile()
        val ledger = AppMcpIdempotencyLedger(file, maxEntries = 2)
        ledger.decide("chat_send", "pending", AppMcpIdempotencyLedger.fingerprint(args("a" to "p")))
        repeat(4) { index ->
            val key = "done-$index"
            ledger.decide("chat_send", key, AppMcpIdempotencyLedger.fingerprint(args("a" to key)))
            ledger.complete("chat_send", key, "{}")
        }
        val keys = ledger.snapshot().map { it.key }.toSet()
        assertTrue("pending entry must survive pruning", keys.contains("pending"))
        assertTrue(keys.size <= 3)
    }

    @Test
    fun `corrupted ledger file does not crash and does not replay`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val ledger = AppMcpIdempotencyLedger(file)
        val decision = ledger.decide("chat_send", "k1", AppMcpIdempotencyLedger.fingerprint(args("a" to "1")))
        assertTrue(decision is AppMcpIdemDecision.Fresh)
    }
}
