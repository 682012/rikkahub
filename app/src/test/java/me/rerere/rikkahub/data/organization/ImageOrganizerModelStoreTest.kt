package me.rerere.rikkahub.data.organization

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

/**
 * 识别模型选择的本地小 JSON：原子写、失败可见、多实例共用一把锁。
 *
 * 纯 JVM，不碰 Android。
 */
class ImageOrganizerModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File = temp.newFolder()): File = File(dir, "model.json")

    @Test
    fun `what was written can be read back by another instance`() {
        val file = file()
        val modelId = Uuid.random()

        assertTrue(ImageOrganizerModelStore(file).write(modelId))

        assertEquals(modelId, ImageOrganizerModelStore(file).read())
    }

    @Test
    fun `writing null clears the choice`() {
        val file = file()
        val store = ImageOrganizerModelStore(file)
        assertTrue(store.write(Uuid.random()))

        assertTrue(store.write(null))

        assertNull(ImageOrganizerModelStore(file).read())
        assertTrue(file.readText().contains("\"version\":1"))
        assertFalse(file.readText().contains("modelId"))
    }

    @Test
    fun `a corrupt or missing file reads as no choice instead of failing`() {
        val file = file()
        assertNull(ImageOrganizerModelStore(file).read())

        file.writeText("{ not json")
        assertNull(ImageOrganizerModelStore(file).read())

        file.writeText("""{"version":1,"modelId":"not-a-uuid"}""")
        assertNull(ImageOrganizerModelStore(file).read())
    }

    @Test
    fun `a failed write reports itself and keeps the previous value`() {
        val dir = temp.newFolder()
        val file = File(dir, "model.json")
        val store = ImageOrganizerModelStore(file)
        val first = Uuid.random()
        assertTrue(store.write(first))

        // 占位目录正好叫 <file>.tmp 且非空 → 临时文件写不出来
        val stuck = File(dir, "model.json.tmp").apply { mkdirs() }
        File(stuck, "occupied").writeText("x")

        assertFalse(store.write(Uuid.random()))
        assertNotNull(store.lastFailure)

        // 磁盘上还是上一次的值，读出来也是它
        assertEquals(first, ImageOrganizerModelStore(file).read())
    }

    @Test
    fun `a successful write clears the failure message`() {
        val dir = temp.newFolder()
        val file = File(dir, "model.json")
        val stuck = File(dir, "model.json.tmp").apply { mkdirs() }
        File(stuck, "occupied").writeText("x")
        val store = ImageOrganizerModelStore(file)

        assertFalse(store.write(Uuid.random()))
        assertNotNull(store.lastFailure)

        File(stuck, "occupied").delete()
        stuck.delete()
        val modelId = Uuid.random()
        assertTrue(store.write(modelId))
        assertNull(store.lastFailure)
        assertEquals(modelId, store.read())
    }

    @Test
    fun `two instances on the same file do not leave temporary files behind`() {
        val dir = temp.newFolder()
        val file = File(dir, "model.json")
        val modelId = Uuid.random()

        assertTrue(ImageOrganizerModelStore(file).write(Uuid.random()))
        assertTrue(ImageOrganizerModelStore(file).write(modelId))

        assertEquals(modelId, ImageOrganizerModelStore(file).read())
        assertEquals(
            listOf("model.json"),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
    }
}
