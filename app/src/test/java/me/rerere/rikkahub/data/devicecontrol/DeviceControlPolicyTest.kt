package me.rerere.rikkahub.data.devicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlPolicyTest {

    private val off = DeviceControlGrants()

    private val on = DeviceControlGrants(
        enabled = true,
        allowedPackages = setOf("com.example.notes"),
        allowRead = true,
        allowAct = true,
        allowGlobalActions = false,
    )

    @Test
    fun `default grants are off and deny everything`() {
        assertFalse(off.enabled)
        assertEquals(DeviceErrorCode.NOT_AUTHORIZED, DeviceControlPolicy.check(off, DeviceAction.SNAPSHOT))
        assertEquals(DeviceErrorCode.NOT_AUTHORIZED, DeviceControlPolicy.check(off, DeviceAction.TAP, "com.example.notes"))
    }

    @Test
    fun `empty whitelist allows no package at all`() {
        val grants = DeviceControlGrants(enabled = true, allowAct = true)
        assertEquals(DeviceErrorCode.PACKAGE_NOT_ALLOWED, DeviceControlPolicy.check(grants, DeviceAction.TAP, "com.example.notes"))
        assertEquals(DeviceErrorCode.PACKAGE_NOT_ALLOWED, DeviceControlPolicy.check(grants, DeviceAction.TAP, null))
        assertFalse(DeviceControlPolicy.isPackageAllowed(grants, ""))
    }

    @Test
    fun `read allowed while act disabled`() {
        val grants = on.copy(allowAct = false)
        assertEquals(DeviceErrorCode.CAPABILITY_DISABLED, DeviceControlPolicy.check(grants, DeviceAction.TAP_NODE, "com.example.notes"))
        assertNull(DeviceControlPolicy.check(grants, DeviceAction.SNAPSHOT))
    }

    @Test
    fun `global actions need their own switch and a whitelisted target`() {
        assertEquals(
            DeviceErrorCode.CAPABILITY_DISABLED,
            DeviceControlPolicy.check(on, DeviceAction.LAUNCH_APP, targetPackage = "com.example.notes"),
        )
        val withGlobal = on.copy(allowGlobalActions = true)
        assertNull(DeviceControlPolicy.check(withGlobal, DeviceAction.LAUNCH_APP, targetPackage = "com.example.notes"))
        assertEquals(
            DeviceErrorCode.PACKAGE_NOT_ALLOWED,
            DeviceControlPolicy.check(withGlobal, DeviceAction.LAUNCH_APP, targetPackage = "com.other.app"),
        )
    }

    @Test
    fun `unwhitelisted foreground package is refused for gestures`() {
        assertEquals(
            DeviceErrorCode.PACKAGE_NOT_ALLOWED,
            DeviceControlPolicy.check(on, DeviceAction.SWIPE, "com.other.app"),
        )
        assertTrue(DeviceControlPolicy.isPackageAllowed(on, " com.example.notes "))
    }

    @Test
    fun `tool name mapping covers every action and rejects unknown names`() {
        DeviceAction.entries.forEach { action ->
            assertEquals(action, DeviceAction.of(action.wire))
        }
        assertNull(DeviceAction.of("device_teleport"))
    }

    @Test
    fun `sanitize clamps limits and trims packages`() {
        val raw = DeviceControlGrants(
            allowedPackages = setOf("", "  com.a  "),
            maxNodesPerSnapshot = 1,
            pageSize = 99_999,
            maxCharsPerNode = 1,
            snapshotTtlMs = 1,
        ).sanitized()
        assertEquals(setOf("com.a"), raw.allowedPackages)
        assertEquals(20, raw.maxNodesPerSnapshot)
        assertEquals(200, raw.pageSize)
        assertEquals(20, raw.maxCharsPerNode)
        assertEquals(1_000, raw.snapshotTtlMs)
    }

    @Test
    fun `catalog exposes read only flags consistent with actions`() {
        val catalog = DeviceToolCatalog.descriptors()
        assertEquals(DeviceAction.entries.size, catalog.size)
        catalog.forEach { descriptor ->
            val action = DeviceAction.of(descriptor.name)!!
            assertEquals("readOnly mismatch for ${descriptor.name}", action.readOnly, descriptor.readOnly)
        }
    }
}

class DeviceControlPolicyGuardTest {

    private val grants = DeviceControlGrants(
        enabled = true,
        allowedPackages = setOf("com.example.notes", "me.rerere.rikkahub"),
        allowRead = true,
        allowAct = true,
        allowGlobalActions = true,
    )

    @Test
    fun `global back and home require a whitelisted foreground package`() {
        // 未白名单的前台包：连返回/桌面都不许按（否则能用导航键操作未授权 App）
        assertEquals(
            DeviceErrorCode.PACKAGE_NOT_ALLOWED,
            DeviceControlPolicy.check(grants, DeviceAction.BACK, packageName = "com.bank.app"),
        )
        assertEquals(
            DeviceErrorCode.PACKAGE_NOT_ALLOWED,
            DeviceControlPolicy.check(grants, DeviceAction.HOME, packageName = null),
        )
        assertNull(DeviceControlPolicy.check(grants, DeviceAction.BACK, packageName = "com.example.notes"))
        assertNull(DeviceControlPolicy.check(grants, DeviceAction.HOME, packageName = "com.example.notes"))
    }

    @Test
    fun `the host app itself is never a target even when whitelisted`() {
        assertEquals(
            DeviceErrorCode.PACKAGE_NOT_ALLOWED,
            DeviceControlPolicy.check(
                grants,
                DeviceAction.TAP,
                packageName = "me.rerere.rikkahub",
                selfPackage = "me.rerere.rikkahub",
            ),
        )
        // 启动自己没有副作用（也不会泄露/改动授权），允许
        assertNull(
            DeviceControlPolicy.check(
                grants,
                DeviceAction.LAUNCH_APP,
                targetPackage = "me.rerere.rikkahub",
                selfPackage = "me.rerere.rikkahub",
            ),
        )
        assertTrue(DeviceControlPolicy.isSelfProtected("me.rerere.rikkahub", "me.rerere.rikkahub"))
        assertFalse(DeviceControlPolicy.isSelfProtected("com.example.notes", "me.rerere.rikkahub"))
    }
}
