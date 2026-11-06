package me.rerere.rikkahub.automation

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本 fail-closed 契约（纯 JVM：内存假存储 + 计数 runner，不需要 Android / 真磁盘）。
 *
 * 只钉四件实事：
 * 1. 已存在但坏了/空了/读不出来的账本 **不能**被当成空账本放行，一个工具都不许执行；
 * 2. 原文件一字不改（既不当垃圾清掉，也不拿空内容覆盖）；
 * 3. 写失败要一路传到执行器：写前落盘失败 → 那一步**没执行**；执行后落盘失败 → **未知副作用**；
 * 4. 新 run 必须先落盘才能开始跑。
 */
class AutomationLedgerFailClosedTest {

    // ------------------------------------------------------------------ fakes

    /** 内存账本：可控读失败、可控"第 N 次写开始必失败"，并记录每次成功写入的原文。 */
    private class MemoryLedgerIo(var text: String? = null) : AutomationLedgerIo {
        var writeCount = 0
            private set
        var readCount = 0
            private set

        /** 允许成功写入的次数，超出后一律抛 IOException（模拟磁盘满）。 */
        var successfulWrites = Int.MAX_VALUE

        var readFails = false
        val writes = mutableListOf<String>()
        val events = mutableListOf<String>()

        override val location: String get() = "memory://automation.json"

        override fun read(): String? {
            readCount++
            if (readFails) throw IOException("read denied")
            return text
        }

        override fun write(text: String) {
            if (writeCount >= successfulWrites) throw IOException("no space left on device")
            writeCount++
            this.text = text
            writes += text
            events += "write"
        }
    }

    private class RecordingRunner(
        private val specs: List<AutomationToolSpec>,
        private val events: MutableList<String>? = null,
        private val result: AutomationToolResult = AutomationToolResult(ok = true),
    ) : AutomationToolRunner {
        var executed = 0
            private set
        val executedTools = mutableListOf<String>()

        override fun tools(): List<AutomationToolSpec> = specs

        override suspend fun execute(tool: String, arguments: JsonObject): AutomationToolResult {
            executed++
            executedTools += tool
            events?.add("execute")
            return result
        }
    }

    private val tools = listOf(
        AutomationToolSpec("device_tap_node", readOnly = false),
        AutomationToolSpec("device_snapshot", readOnly = true),
    )

    private val tapRecipe = AutomationRecipe(
        id = "r-1",
        name = "Tap once",
        steps = listOf(AutomationStep("device_tap_node")),
    )

    private val snapshotRecipe = AutomationRecipe(
        id = "r-2",
        name = "Look only",
        steps = listOf(AutomationStep("device_snapshot")),
    )

    private fun ids(): () -> String {
        var n = 0
        return {
            n += 1
            "run-$n"
        }
    }

    private fun engine(store: AutomationStore, runner: RecordingRunner, clock: Long = 1_000L) =
        AutomationEngine(runner = runner, store = store, now = { clock }, newId = ids())

    private fun expectIoFailure(block: suspend () -> Unit) {
        assertThrows(IOException::class.java) { runBlocking { block() } }
    }

    private fun ledgerJson(recipes: String = "", runs: String = "[]", pending: String = "[]") =
        """{"version":2,"recipes":[$recipes],"runs":$runs,"pending":$pending}"""

    private val recipeJson =
        """{"id":"r-1","name":"Tap once","steps":[{"tool":"device_tap_node","arguments":{}}],""" +
            """"intervalMinutes":0,"enabled":false,"createdAt":1,"updatedAt":1}"""

    // ------------------------------------------------- ledger is a precondition, not a formality

    @Test
    fun `a corrupt ledger is not loaded as empty and no tool runs`() {
        val broken = """{"version":2,"recipes":[{"id":"""
        val io = MemoryLedgerIo(broken)
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals("nothing may execute off an unreadable ledger", 0, runner.executed)
        assertEquals(0, io.writeCount)
        assertEquals("the original file must stay byte-identical", broken, io.text)
        assertEquals(AutomationRunStatus.INVALID, run.status)
        assertTrue(run.message, run.message.contains("nothing was executed"))
        assertTrue(run.message, run.message.contains("left untouched"))
        assertEquals(AutomationLedgerHealth.UNREADABLE, store.health)
        assertTrue(store.blocked)
        assertTrue(store.recipes.value.isEmpty())
    }

    @Test
    fun `an existing empty ledger file blocks instead of reviving an empty one`() {
        val io = MemoryLedgerIo("")
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals(0, runner.executed)
        assertEquals(0, io.writeCount)
        assertEquals("", io.text)
        assertEquals(AutomationRunStatus.INVALID, run.status)
        assertTrue(store.blocked)
    }

    @Test
    fun `a read failure blocks and never writes over the ledger`() {
        val io = MemoryLedgerIo(null)
        io.readFails = true
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals(0, runner.executed)
        assertEquals(0, io.writeCount)
        assertNull(io.text)
        assertEquals(AutomationRunStatus.INVALID, run.status)
        assertTrue(run.message, run.message.contains("read denied"))
        assertTrue(store.blocked)
    }

    @Test
    fun `an unreadable ledger refuses every write and keeps the original bytes`() {
        val broken = """{"version":2,"recipes":[]"""
        val io = MemoryLedgerIo(broken)
        val store = AutomationStore(io)
        runBlocking { store.ensureLoaded() }

        expectIoFailure { store.saveRecipe(tapRecipe) }
        expectIoFailure { store.clearHistory() }
        expectIoFailure { store.deleteRecipe("r-1") }

        assertEquals(0, io.writeCount)
        assertEquals(broken, io.text)
        assertTrue(store.recipes.value.isEmpty())
        assertTrue(store.blocked)
    }

    @Test
    fun `a healthy ledger still loads and a run still executes`() {
        val io = MemoryLedgerIo(ledgerJson(recipes = recipeJson))
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        runBlocking { store.ensureLoaded() }
        assertFalse(store.blocked)
        assertEquals(AutomationLedgerHealth.READY, store.health)
        assertEquals(1, store.recipes.value.size)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }
        assertEquals(AutomationRunStatus.SUCCEEDED, run.status)
        assertEquals(listOf("device_tap_node"), runner.executedTools)
        assertEquals(AutomationRunStatus.SUCCEEDED, store.runs.value.first().status)
    }

    // ----------------------------------------------------------- write failure reaches the engine

    @Test
    fun `a failed write-ahead never executes the write step and is not an unknown side effect`() {
        val io = MemoryLedgerIo(null)
        io.successfulWrites = 1 // run 头写进去了，写前记录写不进去
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals("the write step must not run when its record cannot be saved", 0, runner.executed)
        assertEquals(AutomationRunStatus.FAILED, run.status)
        val step = run.steps.single()
        assertEquals(AutomationStepStatus.FAILED, step.status)
        assertFalse("nothing happened, so it must not be recorded as a side effect", step.sideEffect)
        assertTrue(step.detail, step.detail.contains("not executed"))
        assertTrue(run.message, run.message.contains("not executed"))
        assertFalse(run.message, run.message.contains("may or may not"))
        assertFalse("no success may ever reach the disk", io.text!!.contains("SUCCEEDED"))
    }

    @Test
    fun `a failed write after the step ran is an unknown side effect and blocks the next run`() {
        val io = MemoryLedgerIo(null)
        io.successfulWrites = 2 // run 头 + 写前记录成功，写后的结果记录失败
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals(1, runner.executed)
        assertEquals(AutomationRunStatus.UNKNOWN_SIDE_EFFECT, run.status)
        assertTrue(run.message, run.message.contains("confirm"))
        assertTrue("the write-ahead marker must still be on disk", io.text!!.contains("RUNNING"))

        // 重启：盘上那条"要写但结果未知"必须变成"待用户确认"，并且挡住重跑
        val restarted = AutomationStore(io)
        runBlocking { restarted.ensureLoaded() }
        assertTrue(restarted.pending.value.any { it.recipeId == "r-1" })

        val runner2 = RecordingRunner(tools)
        val blocked = runBlocking {
            engine(restarted, runner2, clock = 2_000L).run(tapRecipe, AutomationTrigger.MANUAL)
        }
        assertEquals(AutomationRunStatus.BLOCKED_UNKNOWN_SIDE_EFFECT, blocked.status)
        assertEquals("a blocked recipe must not execute anything", 0, runner2.executed)
    }

    @Test
    fun `a run that cannot be saved before starting executes nothing`() {
        val io = MemoryLedgerIo(null)
        io.successfulWrites = 0
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals(0, runner.executed)
        assertEquals(AutomationRunStatus.FAILED, run.status)
        assertTrue(run.message, run.message.contains("could not be saved before starting"))
        assertNull(io.text)
    }

    @Test
    fun `the run head and the write-ahead marker are saved before the first tool call`() {
        val io = MemoryLedgerIo(null)
        val runner = RecordingRunner(tools, events = io.events)
        val store = AutomationStore(io)

        runBlocking { engine(store, runner).run(tapRecipe, AutomationTrigger.MANUAL) }

        assertEquals("write", io.events.first())
        assertTrue("the run head must be durable before executing", io.events.indexOf("execute") > 0)
        assertTrue(io.writes.first().contains("RUNNING"))
        assertTrue("the write-ahead marker comes before the tool call", io.writes[1].contains("RUNNING"))
    }

    @Test
    fun `a read-only run still finishes when the ledger is healthy`() {
        val io = MemoryLedgerIo(null)
        val runner = RecordingRunner(tools)
        val store = AutomationStore(io)

        val run = runBlocking { engine(store, runner).run(snapshotRecipe, AutomationTrigger.MANUAL) }

        assertEquals(AutomationRunStatus.SUCCEEDED, run.status)
        assertEquals(1, runner.executed)
        assertFalse(run.steps.single().sideEffect)
    }

    // ------------------------------------------------------------------ writers never fake success

    @Test
    fun `saveRecipe throws instead of pretending to save`() {
        val io = MemoryLedgerIo(null)
        val store = AutomationStore(io)
        runBlocking { store.ensureLoaded() }
        io.successfulWrites = 0

        expectIoFailure { store.saveRecipe(tapRecipe) }

        assertTrue("a failed save must not appear in memory", store.recipes.value.isEmpty())
        assertNull(io.text)
        assertEquals(AutomationLedgerHealth.WRITE_FAILED, store.health)
        assertFalse("a write failure is not an unreadable ledger", store.blocked)
    }

    @Test
    fun `a later successful write clears the write-failed state`() {
        val io = MemoryLedgerIo(null)
        val store = AutomationStore(io)
        runBlocking { store.ensureLoaded() }
        io.successfulWrites = 0
        expectIoFailure { store.saveRecipe(tapRecipe) }

        io.successfulWrites = 1
        runBlocking { store.saveRecipe(tapRecipe) }

        assertEquals(AutomationLedgerHealth.READY, store.health)
        assertEquals(1, store.recipes.value.size)
        assertNotNull(io.text)
    }

    @Test
    fun `a failed write keeps the previous recipes history and pending confirmations`() {
        val original = ledgerJson(
            recipes = recipeJson,
            runs = """[{"id":"old","recipeId":"r-1","recipeName":"Tap once","trigger":"MANUAL",""" +
                """"startedAt":1,"status":"UNKNOWN_SIDE_EFFECT","message":"may or may not have happened"}]""",
            pending = """[{"runId":"old","recipeId":"r-1","recipeName":"Tap once","at":1,""" +
                """"message":"may or may not have happened"}]""",
        )
        val io = MemoryLedgerIo(original)
        val store = AutomationStore(io)
        runBlocking { store.ensureLoaded() }
        assertEquals(1, store.pending.value.size)
        assertEquals(1, store.runs.value.size)

        io.successfulWrites = io.writeCount
        expectIoFailure { store.saveRecipe(tapRecipe.copy(name = "Renamed")) }

        assertEquals("the stored ledger must be byte-identical", original, io.text)
        assertEquals("pending confirmations survive", 1, store.pending.value.size)
        assertEquals(1, store.runs.value.size)
        assertEquals(listOf("Tap once"), store.recipes.value.map { it.name })
    }
}
