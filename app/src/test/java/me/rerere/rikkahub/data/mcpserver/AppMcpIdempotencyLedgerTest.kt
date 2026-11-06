package me.rerere.rikkahub.data.mcpserver

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun ledgerDir(name: String = "idempotency.json"): File =
        temporaryFolder.newFolder("r22-mcp-$name-${System.nanoTime()}")

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private fun fp(vararg pairs: Pair<String, String>): String =
        AppMcpIdempotencyLedger.fingerprint(args(*pairs))

    private fun rejection(decision: AppMcpIdemDecision): AppMcpIdemDecision.Rejected {
        assertTrue("expected a rejection, got $decision", decision is AppMcpIdemDecision.Rejected)
        return decision as AppMcpIdemDecision.Rejected
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
    fun `pending entry still blocks after a restart even next to finished entries`() {
        val file = ledgerFile()
        val pendingFp = fp("text" to "hello")
        val doneFp = fp("text" to "bye")
        AppMcpIdempotencyLedger(file).apply {
            decide("chat_send", "done-key", doneFp)
            complete("chat_send", "done-key", """{"ok":true}""")
            decide("chat_send", "pending-key", pendingFp)
        }

        val restarted = AppMcpIdempotencyLedger(file)
        assertEquals("unknown_side_effect", rejection(restarted.decide("chat_send", "pending-key", pendingFp)).code)
        assertEquals("""{"ok":true}""", (restarted.decide("chat_send", "done-key", doneFp) as AppMcpIdemDecision.Replay).resultJson)
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
        assertTrue(ledger.clearFinished())

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

    // ------------------------------------------------- 不可信账本：fail closed

    @Test
    fun `corrupted ledger file is rejected instead of treated as empty`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val before = file.readBytes()

        val ledger = AppMcpIdempotencyLedger(file)
        val decision = ledger.decide("chat_send", "k1", fp("a" to "1"))

        assertEquals("ledger_unreadable", rejection(decision).code)
        assertEquals(AppMcpLedgerLoad.UNTRUSTED, ledger.loadState)
        // 坏文件必须原样留着：不许被一本空账覆盖掉。
        assertTrue("corrupted ledger bytes must be preserved", before.contentEquals(file.readBytes()))
        assertNull("nothing may be recorded as in-flight", ledger.entryOf("chat_send", "k1"))
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test
    fun `an existing empty ledger file is rejected not treated as a fresh ledger`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        assertTrue(file.createNewFile())
        assertEquals(0L, file.length())

        val ledger = AppMcpIdempotencyLedger(file)
        assertEquals("ledger_unreadable", rejection(ledger.decide("chat_send", "k1", fp("a" to "1"))).code)
        assertEquals(0L, file.length())
        assertEquals(AppMcpLedgerLoad.UNTRUSTED, ledger.loadState)
        assertTrue(
            "the reason should say the file is empty: ${ledger.loadError}",
            ledger.loadError?.contains("empty") == true,
        )
    }

    @Test
    fun `a whitespace only ledger file is rejected and preserved`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("  \n\t ")
        val before = file.readBytes()

        val ledger = AppMcpIdempotencyLedger(file)
        assertEquals("ledger_unreadable", rejection(ledger.decide("chat_send", "k1", fp("a" to "1"))).code)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a truncated ledger file is rejected and preserved`() {
        val file = ledgerFile()
        val fingerprint = fp("title" to "x")
        AppMcpIdempotencyLedger(file).apply {
            decide("chat_rename", "k1", fingerprint)
            complete("chat_rename", "k1", """{"ok":true,"padding":"xxxxxxxxxxxxxxxx"}""")
        }
        val full = file.readText()
        file.writeText(full.take(full.length / 2))
        val before = file.readBytes()

        val ledger = AppMcpIdempotencyLedger(file)
        assertEquals("ledger_unreadable", rejection(ledger.decide("chat_rename", "k1", fingerprint)).code)
        assertTrue("truncated ledger bytes must be preserved", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a directory sitting on the ledger path is rejected and left alone`() {
        val dir = ledgerDir()
        val file = File(dir, "idempotency.json")
        assertTrue(file.mkdirs())

        val ledger = AppMcpIdempotencyLedger(file)
        assertEquals("ledger_unreadable", rejection(ledger.decide("chat_send", "k1", fp("a" to "1"))).code)
        assertTrue("the directory must not be replaced by a file", file.isDirectory)
        assertFalse(File(dir, "idempotency.json.tmp").exists())
    }

    @Test
    fun `a missing ledger file is a first run and the first write is allowed`() {
        val file = ledgerFile()
        assertFalse(file.exists())

        val ledger = AppMcpIdempotencyLedger(file)
        assertTrue(ledger.decide("chat_send", "k1", fp("a" to "1")) is AppMcpIdemDecision.Fresh)
        assertEquals(AppMcpLedgerLoad.TRUSTED, ledger.loadState)
        assertTrue(file.isFile)
        assertFalse("atomic write must not leave a temp file", File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `a valid empty array is a clean ledger and allows a new key`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("[]")

        val ledger = AppMcpIdempotencyLedger(file)
        assertEquals(AppMcpLedgerLoad.TRUSTED, ledger.loadState)
        assertTrue(ledger.decide("chat_send", "k1", fp("a" to "1")) is AppMcpIdemDecision.Fresh)
    }

    @Test
    fun `an unreadable ledger never gets rewritten by mutators`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val before = file.readBytes()

        val ledger = AppMcpIdempotencyLedger(file)
        assertFalse("complete must not write", ledger.complete("chat_send", "k1", "{}"))
        assertFalse("fail must not write", ledger.fail("chat_send", "k1", "boom"))
        assertFalse("forget must not write", ledger.forget("chat_send", "k1"))
        assertFalse("clearFinished must not write", ledger.clearFinished())

        assertTrue("mutators must not overwrite an unreadable ledger", before.contentEquals(file.readBytes()))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `a repaired ledger stays untrusted for the running instance and recovers on restart`() {
        val file = ledgerFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")

        val broken = AppMcpIdempotencyLedger(file)
        assertEquals("ledger_unreadable", rejection(broken.decide("chat_send", "k1", fp("a" to "1"))).code)

        // 运维把文件修好了（这里模拟成清成一份合法空账）：
        file.writeText("[]")
        // 同一个对象故意不自动恢复：运行中的"修一下"不能把未知 PENDING 洗成可以再写一次。
        assertEquals("ledger_unreadable", rejection(broken.decide("chat_send", "k1", fp("a" to "1"))).code)
        assertEquals(AppMcpLedgerLoad.UNTRUSTED, broken.loadState)

        // 重启（新对象）才会重新读盘：文件合法 → 可信 → 后续写操作恢复正常。
        val restarted = AppMcpIdempotencyLedger(file)
        assertEquals(AppMcpLedgerLoad.TRUSTED, restarted.loadState)
        assertTrue(restarted.decide("chat_send", "k1", fp("a" to "1")) is AppMcpIdemDecision.Fresh)
    }

    @Test
    fun `a failed rename fails closed and keeps the temp file from lingering`() {
        val dir = ledgerDir()
        val file = File(dir, "idempotency.json")
        val ledger = AppMcpIdempotencyLedger(file)
        assertTrue(ledger.decide("chat_send", "k1", fp("a" to "1")) is AppMcpIdemDecision.Fresh)
        assertTrue(ledger.complete("chat_send", "k1", """{"ok":true}"""))

        // 让 rename 一定失败：目标名字被一个目录占住。旧实现此时会直接 writeText 覆盖，
        // 把原来好好的账本截成坏文件；新实现必须返回 ledger_unavailable 并保留现场。
        assertTrue(file.delete())
        assertTrue(file.mkdirs())

        val decision = ledger.decide("chat_send", "k2", fp("a" to "2"))
        assertEquals("ledger_unavailable", rejection(decision).code)
        assertTrue("rename failure must not overwrite the target", file.isDirectory)
        assertFalse("temp file must be cleaned up", File(dir, "idempotency.json.tmp").exists())
        assertNull("nothing may be recorded as in-flight", ledger.entryOf("chat_send", "k2"))
    }
}
