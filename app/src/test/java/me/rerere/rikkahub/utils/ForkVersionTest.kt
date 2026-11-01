package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkVersionTest {
    @Test
    fun `short revisions use numeric ordering`() {
        val revisions = listOf(1, 2, 9, 10, 11, 20, 99, 100, 1000)
        for (left in revisions) for (right in revisions) {
            assertEquals(
                "$left compared with $right",
                left.compareTo(right).sign(),
                compareForkVersions("3.1.1-r$left", "3.1.1-r$right").sign(),
            )
        }
    }

    @Test
    fun `short revision succeeds the legacy line for the same upstream`() {
        assertTrue(compareForkVersions("3.1.1-r1", "3.1.1-fork.43") > 0)
        assertTrue(compareForkVersions("3.1.1-fork.43", "3.1.1-r1") < 0)
    }

    @Test
    fun `new upstream core wins before revision`() {
        assertTrue(compareForkVersions("3.1.2-r1", "3.1.1-r1000") > 0)
        assertTrue(compareForkVersions("3.2.0-r1", "3.1.9-r1000") > 0)
        assertTrue(compareForkVersions("4.0.0-r1", "3.9.9-r1000") > 0)
        assertTrue(compareForkVersions("3.1.2-fork.1", "3.1.1-r2") > 0)
    }

    @Test
    fun `legacy numerical ordering and shared semver remain unchanged`() {
        assertTrue(compareForkVersions("3.1.1-fork.43", "3.1.1-fork.9") > 0)
        assertEquals(0, compareForkVersions("3.1.1-r1", "3.1.1-r1"))
        assertTrue(Version.compare("3.1.1-r10", "3.1.1-r2") < 0)
        assertTrue(Version.compare("3.1.1", "3.1.1-r1") > 0)
    }

    private fun Int.sign(): Int = compareTo(0)
}
