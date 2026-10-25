package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime

/**
 * [WorkspaceImportStaging] 的 JVM 单元测试（`testDebugUnitTest`）。
 *
 * 覆盖清理的六个安全闸门：名字精确匹配、只看 base 直接子项、只删目录、不跟符号链接、
 * 24h 年龄门槛、进程内租约（含取消）。全部跑真实文件系统（真目录/真链接/真 mtime）。
 */
class WorkspaceImportStagingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `deletes stale staging directory with nested archive and content`() {
        val base = tmp.newFolder("workspaces")
        val staging = File(base, ".import-$U1")
        File(staging, "files/deep/nested").mkdirs()
        File(staging, "archive.zip").writeText("zip-bytes")
        File(staging, "files/deep/nested/a.txt").writeText("hello")
        backdate(staging)

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertFalse("stale staging must be gone", existsNoFollow(staging))
        assertEquals(1, summary.deletedCount)
        assertEquals(0, summary.errorCount)
        assertFalse(summary.timedOut)
    }

    @Test
    fun `keeps a staging directory that is still leased`() = runBlocking<Unit> {
        val base = tmp.newFolder("workspaces")
        val staging = File(base, ".import-$U1")
        File(staging, "files").mkdirs()
        File(staging, "archive.zip").writeText("in-flight")
        backdate(staging)

        WorkspaceImportStaging.withStagingLease(staging) {
            val during = WorkspaceImportStaging.cleanupOnce(base)
            assertEquals(0, during.deletedCount)
            assertEquals(1, during.skippedActiveCount)
            assertTrue("active staging must survive", existsNoFollow(staging))
        }

        // 租约释放后同一份残骸就应该被回收。
        val after = WorkspaceImportStaging.cleanupOnce(base)
        assertEquals(1, after.deletedCount)
        assertFalse(existsNoFollow(staging))
    }

    @Test
    fun `cancellation releases the lease`() = runBlocking<Unit> {
        val base = tmp.newFolder("workspaces")
        val staging = File(base, ".import-$U1").apply { mkdirs() }
        val baseline = WorkspaceImportStaging.activeLeaseCount()

        val job = launch {
            WorkspaceImportStaging.withStagingLease(staging) { awaitCancellation() }
        }
        while (!WorkspaceImportStaging.isActive(staging)) yield()
        assertTrue(WorkspaceImportStaging.activeLeaseCount() > baseline)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse("lease must be dropped on cancellation", WorkspaceImportStaging.isActive(staging))
        assertEquals(baseline, WorkspaceImportStaging.activeLeaseCount())
    }

    @Test
    fun `keeps recent staging and other prefixed names`() {
        val base = tmp.newFolder("workspaces")
        val fresh = File(base, ".import-$U1").apply { mkdirs() }
        File(fresh, "archive.zip").writeText("just started")

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertEquals(0, summary.deletedCount)
        assertEquals(1, summary.skippedRecentCount)
        assertTrue(existsNoFollow(fresh))

        // 年龄门槛可注入：同一份目录在 minAge=0 时就是残骸。
        val forced = WorkspaceImportStaging.cleanupOnce(base, minAgeMillis = 0)
        assertEquals(1, forced.deletedCount)
        assertFalse(existsNoFollow(fresh))
    }

    @Test
    fun `never deletes target workspaces lookalikes or plain files`() {
        val base = tmp.newFolder("workspaces")
        val keep = listOf(
            "0123abcd-1111-2222-3333-444455556666", // 正式工作区目录：裸 uuid，无前缀
            ".import-not-a-uuid",
            ".import-${U1.uppercase()}", // 大小写不匹配
            ".import-$U1.bak",
            ".import-$U1.tmp",
            ".imports-$U1",
            "x.import-$U1",
            ".import-0123abcd-1111-2222-3333-444455556666x", // 多一位
        )
        keep.forEach { name ->
            File(base, name).mkdirs()
            File(base, "$name/inner.txt").writeText("keep me")
            backdate(File(base, name))
        }
        // 同名普通文件也不能删（不是目录就不是我们的残骸）。
        val fileCase = File(base, ".import-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        fileCase.writeText("regular file")
        backdate(fileCase)

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertEquals(0, summary.deletedCount)
        assertEquals(0, summary.errorCount)
        keep.forEach { name ->
            assertTrue("must keep: $name", File(base, name).isDirectory)
            assertEquals("keep me", File(base, "$name/inner.txt").readText())
        }
        assertTrue(fileCase.isFile)
        assertEquals("regular file", fileCase.readText())
    }

    @Test
    fun `does not follow symlinks outside or inside the tree`() {
        val base = tmp.newFolder("workspaces")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.key").writeText("TOP-SECRET")

        // 1) 残骸内部有一条指向外部的链接 + 一条悬空链接：只删链接本身，目标必须完好。
        val staging = File(base, ".import-$U1")
        File(staging, "files").mkdirs()
        Files.createSymbolicLink(File(staging, "files/escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(staging, "dangling").toPath(), File(outside, "missing").toPath())
        backdate(staging)

        // 2) 名字精确匹配、但本身是符号链接的"目录"：绝不能顺着链接删掉别人的目录。
        val linkedStaging = File(base, ".import-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        Files.createSymbolicLink(linkedStaging.toPath(), outside.toPath())
        backdate(outside)

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertEquals(1, summary.deletedCount) // 只回收了真目录那一份
        assertFalse(existsNoFollow(staging))
        assertTrue("symlinked candidate must not be followed", existsNoFollow(linkedStaging))
        assertTrue(outside.isDirectory)
        assertEquals("TOP-SECRET", File(outside, "secret.key").readText())
    }

    @Test
    fun `ignores nested staging names below the base directory`() {
        val base = tmp.newFolder("workspaces")
        val nested = File(base, "0123abcd-1111-2222-3333-444455556666/.import-$U1")
        nested.mkdirs()
        File(nested, "archive.zip").writeText("not ours")
        backdate(nested)

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertEquals(0, summary.deletedCount)
        assertTrue(existsNoFollow(nested))
    }

    @Test
    fun `missing base directory is reported not thrown`() {
        val base = File(tmp.root, "workspaces-does-not-exist")

        val summary = WorkspaceImportStaging.cleanupOnce(base)

        assertEquals(0, summary.deletedCount)
        assertEquals(1, summary.errorCount)
    }

    @Test
    fun `name matching and staging dir helper are strict`() {
        assertTrue(WorkspaceImportStaging.isStagingName(".import-$U1"))
        assertFalse(WorkspaceImportStaging.isStagingName(".import-${U1.uppercase()}"))
        assertFalse(WorkspaceImportStaging.isStagingName(".import-$U1/"))
        assertFalse(WorkspaceImportStaging.isStagingName(" .import-$U1"))
        assertFalse(WorkspaceImportStaging.isStagingName(".import-${U1}n"))

        val base = tmp.newFolder("workspaces2")
        assertEquals(
            ".import-$U1",
            WorkspaceImportStaging.stagingDirFor(base, U1).name,
        )
        var rejected = 0
        listOf("", "..", "a/b", "a\\b").forEach { bad ->
            try {
                WorkspaceImportStaging.stagingDirFor(base, bad)
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        assertEquals(4, rejected)
    }

    // ------------------------------------------------------------ helpers

    private fun backdate(file: File, ageMillis: Long = OLD_AGE_MILLIS) {
        Files.setLastModifiedTime(
            file.toPath(),
            FileTime.fromMillis(System.currentTimeMillis() - ageMillis),
        )
    }

    private fun existsNoFollow(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        private const val OLD_AGE_MILLIS: Long = 48L * 60L * 60L * 1000L
        /** 带 a-f 字母，才能让 uppercase 版本真的与规范小写形式不同。 */
        private const val U1: String = "deadbeef-1111-2222-3333-444455556666"
    }
}
