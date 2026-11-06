package me.rerere.rikkahub.automation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AutomationLedgerFileTest {
    @Test
    fun `atomic replacement updates an existing ledger without trailing temporary files`() {
        val dir = Files.createTempDirectory("automation-ledger").toFile()
        try {
            val file = File(dir, "automation.json")
            val io = FileAutomationLedgerIo(file)
            assertNull(io.read())
            io.write("{\"version\":1}")
            io.write("{\"version\":2}")
            assertEquals("{\"version\":2}", io.read())
            assertFalse(File(dir, "automation.json.part").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `an unwritable target does not become an empty ledger`() {
        val dir = Files.createTempDirectory("automation-ledger-block").toFile()
        try {
            val target = File(dir, "automation.json").apply { mkdirs() }
            File(target, "keep").writeText("evidence")
            val io = FileAutomationLedgerIo(target)
            assertThrows(IOException::class.java) { io.read() }
            assertThrows(IOException::class.java) { io.write("{}") }
            assertEquals("evidence", File(target, "keep").readText())
        } finally { dir.deleteRecursively() }
    }
}
