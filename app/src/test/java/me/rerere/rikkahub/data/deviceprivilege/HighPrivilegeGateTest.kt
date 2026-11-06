package me.rerere.rikkahub.data.deviceprivilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁矩阵：默认全关、null 不等于所有助手、写权限独立、Shizuku 不可用必须硬拒。 */
class HighPrivilegeGateTest {

    private fun state(
        enabled: Boolean = true,
        allowWrites: Boolean = true,
        assistantId: String? = "assistant-a",
    ) = HighPrivilegeAutomation(enabled = enabled, allowWrites = allowWrites, assistantId = assistantId)

    @Test
    fun `fresh install is fully closed`() {
        val fresh = HighPrivilegeAutomation()
        assertFalse(fresh.enabled)
        assertFalse(fresh.allowWrites)
        assertNull(fresh.assistantId)
        val decision = HighPrivilegePolicy.gate(fresh, "assistant-a", requiresWrite = false, shizukuReady = true)
        assertFalse(decision.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.AUTOMATION_DISABLED, decision.denial)
    }

    @Test
    fun `null assistant binding is not a wildcard`() {
        val decision = HighPrivilegePolicy.gate(
            state(assistantId = null), "assistant-a", requiresWrite = false, shizukuReady = true,
        )
        assertFalse(decision.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.ASSISTANT_UNBOUND, decision.denial)

        val blank = HighPrivilegePolicy.gate(
            state(assistantId = "   "), "assistant-a", requiresWrite = false, shizukuReady = true,
        )
        assertFalse(blank.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.ASSISTANT_UNBOUND, blank.denial)
    }

    @Test
    fun `only the bound assistant is allowed`() {
        val other = HighPrivilegePolicy.gate(
            state(), "assistant-b", requiresWrite = false, shizukuReady = true,
        )
        assertFalse(other.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.ASSISTANT_MISMATCH, other.denial)

        val bound = HighPrivilegePolicy.gate(
            state(), "assistant-a", requiresWrite = false, shizukuReady = true,
        )
        assertTrue(bound.allowed)
    }

    @Test
    fun `writes need their own switch`() {
        val readOnly = HighPrivilegePolicy.gate(
            state(allowWrites = false), "assistant-a", requiresWrite = true, shizukuReady = true,
        )
        assertFalse(readOnly.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.WRITE_DISABLED, readOnly.denial)

        val read = HighPrivilegePolicy.gate(
            state(allowWrites = false), "assistant-a", requiresWrite = false, shizukuReady = true,
        )
        assertTrue(read.allowed)
    }

    @Test
    fun `shizuku must be actually ready`() {
        val decision = HighPrivilegePolicy.gate(
            state(), "assistant-a", requiresWrite = false, shizukuReady = false,
        )
        assertFalse(decision.allowed)
        assertEquals(HighPrivilegePolicy.GateDenial.SHIZUKU_UNAVAILABLE, decision.denial)
    }

    @Test
    fun `normalisation collapses writes and blank binding`() {
        val collapsed = HighPrivilegeAutomation(enabled = false, allowWrites = true, assistantId = "").normalized()
        assertFalse(collapsed.allowWrites)
        assertNull(collapsed.assistantId)

        val trimmed = HighPrivilegeAutomation(enabled = true, allowWrites = true, assistantId = " a ").normalized()
        assertEquals("a", trimmed.assistantId)
        assertTrue(trimmed.allowWrites)
    }

    @Test
    fun `generic command validation is bounded`() {
        HighPrivilegePolicy.validateCommand("id -u")
        assertTrue(runCatching { HighPrivilegePolicy.validateCommand("") }.isFailure)
        assertTrue(runCatching { HighPrivilegePolicy.validateCommand("   ") }.isFailure)
        assertTrue(
            runCatching {
                HighPrivilegePolicy.validateCommand("x".repeat(HighPrivilegePolicy.MAX_COMMAND_CHARS + 1))
            }.isFailure,
        )
        assertTrue(runCatching { HighPrivilegePolicy.validateCommand("a\u0000b") }.isFailure)
    }
}
