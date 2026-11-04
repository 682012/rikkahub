package me.rerere.rikkahub.data.organization

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 图片整理的纯逻辑部分：解析、引用统计/路径解析、删除计划。
 * 不碰 Android、不发请求，只保证这些判断不会走错分支。
 */
class ImageOrganizationTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 解析 ----

    @Test
    fun `parses json wrapped in code fence`() {
        val raw = """
            好的，结果如下：
            ```json
            {"category":"PORTRAIT","description":"一个动漫角色的半身像"}
            ```
        """.trimIndent()
        val parsed = ImageClassificationParser.parse(raw)
        assertEquals(ImageCategory.PORTRAIT, parsed.category)
        assertEquals("一个动漫角色的半身像", parsed.description)
    }

    @Test
    fun `parses chinese category and plain text answer`() {
        val parsed = ImageClassificationParser.parse("这张是截图/文字，里面是聊天界面")
        assertEquals(ImageCategory.SCREENSHOT, parsed.category)
    }

    @Test
    fun `unknown category falls back to other`() {
        val parsed = ImageClassificationParser.parse("""{"category":"CAT","description":"猫"}""")
        assertEquals(ImageCategory.OTHER, parsed.category)
        assertEquals("猫", parsed.description)
    }

    @Test
    fun `garbage and empty fall back to unrecognized`() {
        assertEquals(ImageCategory.UNRECOGNIZED, ImageClassificationParser.parse(null).category)
        assertEquals(ImageCategory.UNRECOGNIZED, ImageClassificationParser.parse("   ").category)
        assertEquals(
            ImageCategory.UNRECOGNIZED,
            ImageClassificationParser.parse("完全不相关的一串话").category,
        )
        assertNull(ImageClassificationParser.parse("").description)
    }

    @Test
    fun `description is truncated`() {
        val long = "很".repeat(500)
        val parsed = ImageClassificationParser.parse("""{"category":"SCENERY","description":"$long"}""")
        assertEquals(ImageCategory.SCENERY, parsed.category)
        assertTrue(parsed.description!!.length <= ImageClassificationParser.MAX_DESCRIPTION_LENGTH)
    }

    @Test
    fun `unrecognized category is not offered to the filter`() {
        assertTrue(ImageCategory.UNRECOGNIZED !in ImageCategory.selectable)
        assertEquals(5, ImageCategory.selectable.size)
    }

    // ---- 路径与引用 ----

    @Test
    fun `legacy gen media subdirectory is resolved`() {
        val imagesDir = temp.newFolder("images")
        val nested = File(imagesDir, "1700000000000_vendor").apply { mkdirs() }
        val target = File(nested, "1700000000000_vendor_model_0.png").apply { writeText("x") }

        val resolved = ImageMediaPaths.resolveGenMediaFile(
            imagesDir = imagesDir,
            relativePath = "images/1700000000000_vendor_model_0.png",
            modelId = "vendor/model",
            createAt = 1700000000000L,
        )
        assertEquals(target.absolutePath, resolved.absolutePath)
    }

    @Test
    fun `reference index counts local references only`() {
        val filesDir = temp.newFolder("files")
        val index = ImageReferenceIndex.build(
            filesDir = filesDir,
            blobs = listOf(
                "upload/a.png\nupload/b.png",
                "https://example.com/c.png",
                "data:image/png;base64,AAAA",
                "images/d.png",
                "",
            ),
        )
        assertEquals(1, ImageReferenceIndex.countFor(index, File(filesDir, "upload/a.png").path))
        assertEquals(1, ImageReferenceIndex.countFor(index, File(filesDir, "upload/b.png").path))
        assertEquals(1, ImageReferenceIndex.countFor(index, File(filesDir, "images/d.png").path))
        assertEquals(0, ImageReferenceIndex.countFor(index, File(filesDir, "upload/zzz.png").path))
        assertEquals(3, index.size)
    }

    // ---- 路径闸门（删除前的最后一道检查） ----

    @Test
    fun `path confinement rejects escapes and symlinks`() {
        val filesDir = temp.newFolder("filesdir")
        val inside = File(filesDir, "upload/a.png").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        val outside = temp.newFolder("outside")
        val outsideFile = File(outside, "secret.txt").apply { writeText("x") }

        assertTrue(ImageMediaPaths.isConfinedTo(filesDir, inside))
        assertTrue(!ImageMediaPaths.isConfinedTo(filesDir, outsideFile))
        // 目录本身不算
        assertTrue(!ImageMediaPaths.isConfinedTo(filesDir, filesDir))
        // `..` 逃逸
        assertTrue(!ImageMediaPaths.isConfinedTo(filesDir, File(filesDir, "../outside/secret.txt")))

        // symlink 指到外部：canonical 解析后不在 filesDir 内，必须拒绝
        val link = File(filesDir, "upload/link.png")
        val created = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outsideFile.toPath()) }.isSuccess
        if (created) {
            assertTrue(!ImageMediaPaths.isConfinedTo(filesDir, link))
        }
    }

    // ---- 删除计划 ----

    @Test
    fun `delete plan keeps protected shared and unsupported items`() {
        val root = temp.newFolder("root")
        fun item(
            id: String,
            source: ImageSource = ImageSource.UPLOAD,
            exists: Boolean = true,
            protected: Boolean = false,
            shared: Int = 0,
            deletable: Boolean = true,
        ): ImageOrganizerItem {
            val file = File(root, "$id.png")
            if (exists) file.writeText("x")
            return ImageOrganizerItem(
                id = id,
                name = "$id.png",
                path = file.absolutePath,
                relativePath = "$id.png",
                bytes = 1L,
                source = source,
                protected = protected,
                sharedReferences = shared,
                deletable = deletable,
            )
        }

        val items = listOf(
            item("upload:1"),
            item("upload:2", protected = true),
            item("upload:3", shared = 2),
            item("gen:4", source = ImageSource.GEN_MEDIA),
            item("gen:5", source = ImageSource.GEN_MEDIA, exists = false),
            item("media:6", source = ImageSource.MEDIA_CREATION, deletable = false),
        )
        val plan = ImageDeletePlanner.plan(
            items = items,
            selected = items.map { it.id }.toSet() + "upload:404",
        )

        assertEquals(listOf("upload:1", "gen:4"), plan.delete.map { it.id })
        assertEquals(
            ImageDeleteSkipReason.PROTECTED,
            plan.skipped.first { it.id == "upload:2" }.reason,
        )
        assertEquals(
            ImageDeleteSkipReason.SHARED_REFERENCE,
            plan.skipped.first { it.id == "upload:3" }.reason,
        )
        assertEquals(
            ImageDeleteSkipReason.NOT_SUPPORTED,
            plan.skipped.first { it.id == "media:6" }.reason,
        )
        assertEquals(
            ImageDeleteSkipReason.NOT_FOUND,
            plan.skipped.first { it.id == "gen:5" }.reason,
        )
        assertEquals(
            ImageDeleteSkipReason.NOT_SELECTED,
            plan.skipped.first { it.id == "upload:404" }.reason,
        )
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun `nothing is deleted when nothing is selected`() {
        val plan = ImageDeletePlanner.plan(items = emptyList(), selected = emptySet())
        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `cache key changes when the file is replaced`() {
        val before = ImageCacheKeys.of("upload:1", "upload/a.png", 100L, 1_000L)
        val after = ImageCacheKeys.of("upload:1", "upload/a.png", 100L, 2_000L)
        assertTrue(before != after)
        assertEquals(before, ImageCacheKeys.of("upload:1", "upload/a.png", 100L, 1_000L))
    }
}
