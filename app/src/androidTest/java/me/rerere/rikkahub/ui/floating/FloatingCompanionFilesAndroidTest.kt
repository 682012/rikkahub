package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import me.rerere.rikkahub.data.datastore.FloatingCompanionLimits
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义角色图片的落盘行为：拷进私有目录、只认本目录、超限拒绝、删旧文件只删自己的。
 *
 * 不依赖 Compose / Koin，只是把 [FloatingCompanionFiles] 的边界跑一遍。
 */
class FloatingCompanionFilesAndroidTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sourceFile(bytes: ByteArray, name: String = "picked.png"): File {
        val dir = File(context.cacheDir, "companion-test").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun copyIntoPrivateDirectoryAndRelease() {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val source = sourceFile(bytes)
        val stored = FloatingCompanionFiles.copyFromUri(context, Uri.fromFile(source))

        assertTrue(stored.startsWith("file://"))
        val file = File(Uri.parse(stored).path!!)
        assertTrue(file.exists())
        assertEquals(FloatingCompanionFiles.directory(context).canonicalFile, file.canonicalFile.parentFile)

        // 存下来之后能过白名单，UI 才会真的去加载
        assertNotNull(FloatingCompanionFiles.resolve(context, stored))
        val settings = FloatingCompanionSettings(
            preset = me.rerere.rikkahub.data.datastore.FloatingCompanionPreset.CUSTOM,
            customImagePath = stored,
        )
        assertEquals(listOf(file.name), FloatingCompanionFiles.ownedFiles(context, settings).map { it.name })

        FloatingCompanionFiles.release(context, stored)
        assertFalse(file.exists())
        assertNull(FloatingCompanionFiles.resolve(context, stored))
    }

    @Test
    fun oversizedImageIsRejectedAndLeavesNothingBehind() {
        val dir = File(context.cacheDir, "companion-test").apply { mkdirs() }
        val huge = File(dir, "huge.png")
        // 稀疏文件，不用真的写 17MB 数据
        RandomAccessFile(huge, "rw").use { it.setLength(FloatingCompanionLimits.MAX_IMAGE_BYTES + 1024) }
        val before = FloatingCompanionFiles.directory(context).listFiles()?.size ?: 0

        val result = runCatching { FloatingCompanionFiles.copyFromUri(context, Uri.fromFile(huge)) }

        assertTrue(result.isFailure)
        val after = FloatingCompanionFiles.directory(context).listFiles()?.size ?: 0
        assertEquals(before, after)
        huge.delete()
    }

    @Test
    fun filesOutsideThePrivateDirectoryAreIgnored() {
        val outside = File(context.cacheDir, "not-ours.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val uri = Uri.fromFile(outside).toString()

        assertNull(FloatingCompanionFiles.resolve(context, uri))
        // 删也只删自己目录里的：外部文件必须还在
        FloatingCompanionFiles.release(context, uri)
        assertTrue(outside.exists())

        assertNull(FloatingCompanionFiles.resolve(context, "https://example.com/cat.png"))
        assertNull(FloatingCompanionFiles.resolve(context, null))
        outside.delete()
    }
}
