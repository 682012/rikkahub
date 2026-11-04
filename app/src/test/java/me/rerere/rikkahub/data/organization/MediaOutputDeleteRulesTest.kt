package me.rerere.rikkahub.data.organization

import java.io.File
import java.nio.file.Files
import me.rerere.rikkahub.data.model.MediaCreationOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「删一张媒体创作产出图」的纯 JVM 规则：路径校验、outputs 摘除、引用判断、删除计划里的媒体分支。
 *
 * 不碰 Android，也不碰真实数据库；仓库里的落盘 / 事务步骤靠代码审查，这里只保证判断不会走错。
 */
class MediaOutputDeleteRulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun output(path: String, mime: String = "image/png") =
        MediaCreationOutput(path = path, mimeType = mime)

    /** 期望被拒绝时，直接取出拒绝原因；意外通过就 fail 掉。 */
    private fun rejectionOf(filesDir: File, recordDir: File, path: String): MediaOutputDeleteRules.Rejection {
        val check = MediaOutputDeleteRules.resolveTarget(filesDir, recordDir, path)
        return (check as? MediaOutputDeleteRules.TargetCheck.Rejected)?.reason
            ?: error("expected a rejection for $path, got $check")
    }

    // ---- 路径校验 ----

    @Test
    fun `plain output file inside the record dir is accepted`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        val file = File(recordDir, "out_0.png").apply { writeText("x") }

        val check = MediaOutputDeleteRules.resolveTarget(filesDir, recordDir, "media_creation/session/record/out_0.png")
        val target = (check as? MediaOutputDeleteRules.TargetCheck.Ok)?.target
            ?: error("expected the file to be accepted, got $check")
        assertEquals(file.canonicalPath, target.canonicalPath)
    }

    @Test
    fun `empty path is rejected`() {
        val filesDir = temp.newFolder("files")
        assertEquals(
            MediaOutputDeleteRules.Rejection.EMPTY_PATH,
            rejectionOf(filesDir, filesDir, "   "),
        )
    }

    @Test
    fun `keeps the file out of a raw path traversal`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        File(filesDir, "upload").mkdirs()
        File(filesDir, "upload/secret.png").writeText("x")

        assertEquals(
            MediaOutputDeleteRules.Rejection.NOT_IN_RECORD_DIR,
            rejectionOf(filesDir, recordDir, "media_creation/session/record/../../../upload/secret.png"),
        )
    }

    @Test
    fun `absolute paths and urls are rejected`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        File(filesDir, "upload").mkdirs()
        val outside = File(filesDir, "upload/a.png").apply { writeText("x") }

        assertEquals(MediaOutputDeleteRules.Rejection.UNSAFE_PATH, rejectionOf(filesDir, recordDir, outside.absolutePath))
        assertEquals(MediaOutputDeleteRules.Rejection.UNSAFE_PATH, rejectionOf(filesDir, recordDir, "https://example.com/a.png"))
    }

    @Test
    fun `nested file and directories are rejected`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        val nested = File(recordDir, "thumbs").apply { mkdirs() }
        File(nested, "out_0.png").writeText("x")

        assertEquals(
            MediaOutputDeleteRules.Rejection.NOT_IN_RECORD_DIR,
            rejectionOf(filesDir, recordDir, "media_creation/session/record/thumbs/out_0.png"),
        )

        File(recordDir, "out_1.png").mkdirs()
        assertEquals(
            MediaOutputDeleteRules.Rejection.NOT_A_FILE,
            rejectionOf(filesDir, recordDir, "media_creation/session/record/out_1.png"),
        )
    }

    @Test
    fun `missing file is rejected instead of silently touching the record`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        assertEquals(
            MediaOutputDeleteRules.Rejection.NOT_A_FILE,
            rejectionOf(filesDir, recordDir, "media_creation/session/record/gone.png"),
        )
    }

    @Test
    fun `symlinked or escaping target is rejected`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        val outside = File(temp.newFolder("outside"), "real.png").apply { writeText("x") }

        // 记录目录里放一个指向外部文件的 symlink：canonical 之后不在记录目录里
        val link = File(recordDir, "out_0.png")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return
        assertEquals(
            MediaOutputDeleteRules.Rejection.NOT_IN_RECORD_DIR,
            rejectionOf(filesDir, recordDir, "media_creation/session/record/out_0.png"),
        )
    }

    // ---- outputs 摘除 ----

    @Test
    fun `only the matching output entry is removed`() {
        val outputs = listOf(output("a.png"), output("b.png"), output("c.png"))
        assertEquals(listOf("a.png", "c.png"), MediaOutputDeleteRules.withoutOutput(outputs, "b.png")?.map { it.path })
    }

    @Test
    fun `missing entry reports null so the caller keeps the record untouched`() {
        assertNull(MediaOutputDeleteRules.withoutOutput(listOf(output("a.png")), "b.png"))
    }

    // ---- 引用判断 ----

    @Test
    fun `relative absolute and file scheme references all match`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        val file = File(recordDir, "out_0.png").apply { writeText("x") }
        val canonical = file.canonicalPath

        assertTrue(MediaOutputDeleteRules.sameFile(filesDir, "media_creation/session/record/out_0.png", canonical))
        assertTrue(MediaOutputDeleteRules.sameFile(filesDir, file.absolutePath, canonical))
        assertTrue(MediaOutputDeleteRules.sameFile(filesDir, "file://${file.absolutePath}", canonical))
        assertFalse(MediaOutputDeleteRules.sameFile(filesDir, "media_creation/session/record/out_1.png", canonical))
        assertFalse(MediaOutputDeleteRules.sameFile(filesDir, "https://example.com/out_0.png", canonical))
    }

    @Test
    fun `any live reference blocks the deletion`() {
        val filesDir = temp.newFolder("files")
        val recordDir = File(filesDir, "media_creation/session/record").apply { mkdirs() }
        val file = File(recordDir, "out_0.png").apply { writeText("x") }
        val canonical = file.canonicalPath

        assertFalse(MediaOutputDeleteRules.isReferenced(filesDir, canonical, listOf("media_creation/session/draft/a.png")))
        assertTrue(MediaOutputDeleteRules.isReferenced(filesDir, canonical, listOf("media_creation/session/record/out_0.png")))
    }

    // ---- 删除计划里的媒体分支 ----

    private fun mediaItem(root: File, name: String, deletable: Boolean, shared: Int = 0): ImageOrganizerItem {
        val file = File(root, name).apply { parentFile?.mkdirs(); writeText("x") }
        return ImageOrganizerItem(
            id = "media:media_creation/session/record/$name",
            name = name,
            path = file.absolutePath,
            relativePath = "media_creation/session/record/$name",
            bytes = 1L,
            source = ImageSource.MEDIA_CREATION,
            deletable = deletable,
            sharedReferences = shared,
            blockReason = if (deletable) null else "所属的生成记录还在进行中，等它完成后再删",
        )
    }

    @Test
    fun `deletable media output now goes into the delete list`() {
        val root = temp.newFolder("root")
        val item = mediaItem(root, "out_0.png", deletable = true)
        val plan = ImageDeletePlanner.plan(listOf(item), setOf(item.id))

        assertEquals(listOf(item.id), plan.delete.map { it.id })
        assertTrue(plan.skipped.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `media output whose record is still running is skipped with a reason`() {
        val root = temp.newFolder("root")
        val item = mediaItem(root, "out_0.png", deletable = false)
        val plan = ImageDeletePlanner.plan(listOf(item), setOf(item.id))

        assertTrue(plan.delete.isEmpty())
        assertEquals(
            ImageDeleteSkipReason.NOT_SUPPORTED,
            plan.skipped.single { it.id == item.id }.reason,
        )
        assertTrue(plan.warnings.any { it.contains("还在进行中") })
    }

    @Test
    fun `shared media output is skipped as a shared reference`() {
        val root = temp.newFolder("root")
        val item = mediaItem(root, "out_0.png", deletable = false, shared = 1)
        val plan = ImageDeletePlanner.plan(listOf(item), setOf(item.id))

        assertTrue(plan.delete.isEmpty())
        assertEquals(
            ImageDeleteSkipReason.SHARED_REFERENCE,
            plan.skipped.single { it.id == item.id }.reason,
        )
    }

    @Test
    fun `protected media output is never deleted`() {
        val root = temp.newFolder("root")
        val item = mediaItem(root, "out_0.png", deletable = true).copy(protected = true)
        val plan = ImageDeletePlanner.plan(listOf(item), setOf(item.id))

        assertTrue(plan.delete.isEmpty())
        assertEquals(
            ImageDeleteSkipReason.PROTECTED,
            plan.skipped.single { it.id == item.id }.reason,
        )
    }
}
