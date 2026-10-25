package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the schema 32 -> 33 memory path plan. No Android, no SQLite: the planner is the
 * part that decides what happens to a user's memory content, so it is tested on its own.
 */
class MemoryPathRepairTest {

    private fun row(assistant: String, path: String, content: String, updatedAt: Long) =
        MemoryFileRow(assistant, path, content, updatedAt)

    @Test
    fun `relative path without conflict is only renamed`() {
        val steps = MemoryPathRepair.plan(listOf(row("a1", "notes.md", "text", 100)))

        assertEquals(listOf(MemoryPathStep.PrefixPath("a1", "notes.md", "/notes.md")), steps)
    }

    @Test
    fun `nested relative path keeps its directories`() {
        val steps = MemoryPathRepair.plan(listOf(row("a1", "dir/sub/x.md", "text", 100)))

        assertEquals(listOf(MemoryPathStep.PrefixPath("a1", "dir/sub/x.md", "/dir/sub/x.md")), steps)
    }

    @Test
    fun `newer relative copy wins the canonical path and the old content is archived`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "profile.md", "new", 200),
                row("a1", "/profile.md", "old", 100),
            )
        )

        assertEquals(
            listOf(
                MemoryPathStep.DropRelative("a1", "profile.md"),
                MemoryPathStep.WriteCanonical("a1", "/profile.md", "new", 200),
                MemoryPathStep.KeepLegacy("a1", "/_legacy/path_conflicts/profile.md", "old", 100),
            ),
            steps,
        )
    }

    @Test
    fun `newer canonical copy is kept and the relative content is archived`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "profile.md", "old", 100),
                row("a1", "/profile.md", "new", 200),
            )
        )

        assertEquals(
            listOf(
                MemoryPathStep.DropRelative("a1", "profile.md"),
                MemoryPathStep.KeepLegacy("a1", "/_legacy/path_conflicts/profile.md", "old", 100),
            ),
            steps,
        )
    }

    @Test
    fun `equal timestamps keep the canonical path and archive the other text`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "tie.md", "relative", 300),
                row("a1", "/tie.md", "canonical", 300),
            )
        )

        assertEquals(
            listOf(
                MemoryPathStep.DropRelative("a1", "tie.md"),
                MemoryPathStep.KeepLegacy("a1", "/_legacy/path_conflicts/tie.md", "relative", 300),
            ),
            steps,
        )
    }

    @Test
    fun `identical text is deduplicated but the newer timestamp survives`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "same.md", "same", 500),
                row("a1", "/same.md", "same", 400),
            )
        )

        assertEquals(
            listOf(
                MemoryPathStep.DropRelative("a1", "same.md"),
                MemoryPathStep.Touch("a1", "/same.md", 500),
            ),
            steps,
        )
    }

    @Test
    fun `identical text with older timestamp writes nothing extra`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "same.md", "same", 100),
                row("a1", "/same.md", "same", 400),
            )
        )

        assertEquals(listOf(MemoryPathStep.DropRelative("a1", "same.md")), steps)
    }

    @Test
    fun `assistants never share rows`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "profile.md", "one", 100),
                row("a2", "profile.md", "two", 100),
                row("a2", "/profile.md", "two canonical", 50),
            )
        )

        assertTrue(steps.contains(MemoryPathStep.PrefixPath("a1", "profile.md", "/profile.md")))
        // a2 has its own conflict: the newer relative copy wins, the canonical text is archived
        assertTrue(steps.contains(MemoryPathStep.WriteCanonical("a2", "/profile.md", "two", 100)))
        assertTrue(
            steps.contains(
                MemoryPathStep.KeepLegacy("a2", "/_legacy/path_conflicts/profile.md", "two canonical", 50)
            )
        )
        assertTrue(steps.none { it is MemoryPathStep.KeepLegacy && it.assistantId == "a1" })
    }

    @Test
    fun `an existing archive with different content is never overwritten`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "/_legacy/path_conflicts/dup.md", "older archive", 50),
                row("a1", "dup.md", "relative", 60),
                row("a1", "/dup.md", "canonical", 10),
            )
        )

        val archived = steps.filterIsInstance<MemoryPathStep.KeepLegacy>()
        assertEquals(1, archived.size)
        val path = archived.single().path
        assertTrue("archive path must stay in the legacy dir: $path", path.startsWith("/_legacy/path_conflicts/dup"))
        assertTrue("archive path must be unique: $path", path != "/_legacy/path_conflicts/dup.md")
        assertTrue("archive path must be markdown: $path", path.endsWith(".md"))
        assertEquals("canonical", archived.single().content)
        assertTrue(
            steps.none { it is MemoryPathStep.Touch && it.path == "/_legacy/path_conflicts/dup.md" }
        )
    }

    @Test
    fun `an existing archive with the same content is reused`() {
        val steps = MemoryPathRepair.plan(
            listOf(
                row("a1", "/_legacy/path_conflicts/dup.md", "canonical", 50),
                row("a1", "dup.md", "relative", 60),
                row("a1", "/dup.md", "canonical", 10),
            )
        )

        assertTrue(steps.none { it is MemoryPathStep.KeepLegacy })
        // The archive already holds that text with a newer timestamp, so nothing is rewritten there.
        assertTrue(steps.none { it is MemoryPathStep.Touch })
        assertEquals(
            listOf(
                MemoryPathStep.DropRelative("a1", "dup.md"),
                MemoryPathStep.WriteCanonical("a1", "/dup.md", "relative", 60),
            ),
            steps,
        )
    }

    @Test
    fun `plan never drops content and every produced path is absolute`() {
        val rows = listOf(
            row("a1", "notes.md", "n", 1),
            row("a1", "profile.md", "p-rel", 2),
            row("a1", "/profile.md", "p-can", 1),
            row("a1", "tie.md", "t", 3),
            row("a1", "/tie.md", "t2", 3),
            row("a1", "same.md", "s", 5),
            row("a1", "/same.md", "s", 4),
            row("a1", "/keep.md", "k", 9),
            row("a2", "x.md", "x", 1),
        )

        val steps = MemoryPathRepair.plan(rows)

        val producedPaths = steps.mapNotNull {
            when (it) {
                is MemoryPathStep.PrefixPath -> it.to
                is MemoryPathStep.KeepLegacy -> it.path
                is MemoryPathStep.WriteCanonical -> it.path
                else -> null
            }
        }
        assertTrue(producedPaths.all { it.startsWith("/") })
        // Every original text still exists either at a canonical path or under the archive dir.
        val written = steps.mapNotNull { step ->
            when (step) {
                is MemoryPathStep.Touch -> step.assistantId to step.path
                is MemoryPathStep.WriteCanonical -> step.assistantId to step.path
                else -> null
            }
        }.toSet()
        val keptContents = buildList {
            steps.forEach { step ->
                when (step) {
                    is MemoryPathStep.PrefixPath -> add(
                        rows.first { it.assistantId == step.assistantId && it.path == step.from }.content
                    )

                    is MemoryPathStep.KeepLegacy -> add(step.content)
                    is MemoryPathStep.WriteCanonical -> add(step.content)
                    is MemoryPathStep.Touch -> add(
                        rows.first { it.assistantId == step.assistantId && it.path == step.path }.content
                    )

                    is MemoryPathStep.DropRelative -> Unit
                }
            }
            // Rows that were already canonical and never written keep their text as well.
            rows.filter { it.path.startsWith("/") && (it.assistantId to it.path) !in written }
                .forEach { add(it.content) }
        }
        listOf("n", "p-rel", "p-can", "t", "t2", "s", "k", "x").forEach { expected ->
            assertTrue("content '$expected' disappeared from the plan: $steps", expected in keptContents)
        }
    }

    @Test
    fun `archive base sanitizes separators and traversal`() {
        assertEquals("/_legacy/path_conflicts/a.md", MemoryPathRepair.archiveBase("a.md"))
        assertEquals("/_legacy/path_conflicts/a/b.md", MemoryPathRepair.archiveBase("/a//b.md"))
        assertEquals("/_legacy/path_conflicts/a/b.md", MemoryPathRepair.archiveBase("a\\b"))
        assertEquals("/_legacy/path_conflicts/unnamed.md", MemoryPathRepair.archiveBase(""))
        assertTrue(!MemoryPathRepair.archiveBase("../x").contains(".."))
    }

    @Test
    fun `planning is deterministic`() {
        val rows = listOf(
            row("a1", "b.md", "b", 1),
            row("a1", "a.md", "a", 1),
            row("a1", "c.md", "c", 1),
        )

        assertEquals(MemoryPathRepair.plan(rows), MemoryPathRepair.plan(rows.reversed()))
    }
}
