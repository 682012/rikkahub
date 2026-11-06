package me.rerere.rikkahub.data.devicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSnapshotGuardTest {

    private val node = DeviceNode(id = "0/1", className = "android.widget.Button", text = "OK", left = 10, top = 20, right = 110, bottom = 70)

    private fun snapshot(
        id: String = "snap-1",
        capturedAt: Long = 1_000,
        nodes: List<DeviceNode> = listOf(node),
    ) = DeviceSnapshot(
        id = id,
        packageName = "com.example.notes",
        capturedAtMillis = capturedAt,
        page = 0,
        pageSize = 60,
        totalNodes = nodes.size,
        nodes = nodes,
        hasMore = false,
        truncated = false,
    )

    @Test
    fun `no cached snapshot is reported as no_snapshot`() {
        assertEquals(DeviceErrorCode.NO_SNAPSHOT, DeviceSnapshotGuard.checkSnapshot(null, "snap-1", 1_000, 30_000))
    }

    @Test
    fun `different snapshot id is stale`() {
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkSnapshot(snapshot(), "snap-0", 1_100, 30_000))
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkSnapshot(snapshot(), null, 1_100, 30_000))
    }

    @Test
    fun `snapshot past ttl is stale`() {
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkSnapshot(snapshot(), "snap-1", 31_500, 30_000))
        assertNull(DeviceSnapshotGuard.checkSnapshot(snapshot(), "snap-1", 30_500, 30_000))
    }

    @Test
    fun `missing live node is unknown_node`() {
        assertEquals(DeviceErrorCode.UNKNOWN_NODE, DeviceSnapshotGuard.checkNode(node, null))
        assertEquals(DeviceErrorCode.UNKNOWN_NODE, DeviceSnapshotGuard.checkNode(null, node))
    }

    @Test
    fun `moved or replaced node is stale`() {
        val moved = node.copy(left = 11)
        val replaced = node.copy(className = "android.widget.ImageView")
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkNode(node, moved))
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkNode(node, replaced))
    }

    @Test
    fun `text changes alone do not stale a node`() {
        assertNull(DeviceSnapshotGuard.checkNode(node, node.copy(text = "OK now")))
    }

    @Test
    fun `password node is refused even when geometry matches`() {
        val password = node.copy(sensitive = true, text = "")
        assertEquals(DeviceErrorCode.SENSITIVE_FIELD, DeviceSnapshotGuard.checkNode(password, password))
        assertEquals(DeviceErrorCode.SENSITIVE_FIELD, DeviceSnapshotGuard.checkNode(node, node.copy(sensitive = true)))
    }

    @Test
    fun `node lookup only returns nodes of this snapshot`() {
        assertEquals(node, DeviceSnapshotGuard.nodeInSnapshot(snapshot(), "0/1"))
        assertNull(DeviceSnapshotGuard.nodeInSnapshot(snapshot(), "0/2"))
    }
}

class DeviceSnapshotWindowTest {

    private fun snapshot(windowId: Int = 7) = DeviceSnapshot(
        id = "snap-1",
        packageName = "com.example.notes",
        capturedAtMillis = 1_000,
        page = 0,
        pageSize = 60,
        totalNodes = 1,
        nodes = listOf(
            DeviceNode(id = "0/0", className = "android.widget.Button", left = 0, top = 0, right = 100, bottom = 50),
        ),
        hasMore = false,
        truncated = false,
        windowId = windowId,
    )

    @Test
    fun `a different foreground package invalidates the snapshot`() {
        val cached = snapshot()
        assertNull(DeviceSnapshotGuard.checkWindow(cached, "com.example.notes", 7))
        assertEquals(
            DeviceErrorCode.STALE_SNAPSHOT,
            DeviceSnapshotGuard.checkWindow(cached, "com.bank.app", 7),
        )
    }

    @Test
    fun `a different window id invalidates the snapshot even for the same package`() {
        val cached = snapshot(windowId = 7)
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkWindow(cached, "com.example.notes", 8))
        // 一边拿不到窗口 id 时不强判过期（包名与指纹仍在把关）
        assertNull(DeviceSnapshotGuard.checkWindow(cached, "com.example.notes", -1))
        // 未知前台包不算新鲜
        assertEquals(DeviceErrorCode.STALE_SNAPSHOT, DeviceSnapshotGuard.checkWindow(cached, null, 7))
    }

    @Test
    fun `coordinates cannot be used to bypass a sensitive field`() {
        val password = DeviceNode(
            id = "0/1",
            className = "android.widget.EditText",
            left = 10,
            top = 100,
            right = 300,
            bottom = 160,
            editable = true,
            sensitive = true,
        )
        val button = DeviceNode(id = "0/2", left = 10, top = 200, right = 300, bottom = 260)
        val nodes = listOf(button, password)
        assertTrue(DeviceSnapshotGuard.hitsSensitiveNode(nodes, 150, 130))
        assertFalse(DeviceSnapshotGuard.hitsSensitiveNode(nodes, 150, 230))
    }
}
