package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.data.workspace.WorkspaceAccessSettings
import me.rerere.rikkahub.data.workspace.WorkspaceMountSpec
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAccessCardTest {

    // --- 未授权不能显示已提权 -------------------------------------------------

    @Test
    fun `unauthorized settings never report an active privilege mode`() {
        val settings = WorkspaceAccessSettings(
            androidShellEnabled = false,
            androidPrivilege = WorkspacePrivilegeMode.ROOT,
        )
        assertEquals(WorkspaceAndroidAccessState.UNAUTHORIZED, workspaceAndroidAccessState(settings))
        assertNull(workspaceAndroidModeBadge(settings))
    }

    @Test
    fun `authorized settings report the granted mode`() {
        val settings = WorkspaceAccessSettings(
            androidShellEnabled = true,
            androidPrivilege = WorkspacePrivilegeMode.SHIZUKU,
        )
        assertEquals(WorkspaceAndroidAccessState.AUTHORIZED, workspaceAndroidAccessState(settings))
        assertEquals(WorkspacePrivilegeMode.SHIZUKU, workspaceAndroidModeBadge(settings))
    }

    @Test
    fun `only shizuku and root need an impact confirmation`() {
        assertFalse(workspacePrivilegeModeRequiresConfirm(WorkspacePrivilegeMode.APP))
        assertTrue(workspacePrivilegeModeRequiresConfirm(WorkspacePrivilegeMode.SHIZUKU))
        assertTrue(workspacePrivilegeModeRequiresConfirm(WorkspacePrivilegeMode.ROOT))
    }

    @Test
    fun `mode labels stay untranslated brand names`() {
        assertEquals("App", workspaceAccessModeLabel(WorkspacePrivilegeMode.APP))
        assertEquals("Shizuku", workspaceAccessModeLabel(WorkspacePrivilegeMode.SHIZUKU))
        assertEquals("Root", workspaceAccessModeLabel(WorkspacePrivilegeMode.ROOT))
    }

    // --- 挂载目标路径 -------------------------------------------------------

    @Test
    fun `blank mount target falls back to the default mount point`() {
        assertEquals("/mnt/shared", workspaceMountTargetOrDefault(""))
        assertEquals("/mnt/shared", workspaceMountTargetOrDefault("   "))
    }

    @Test
    fun `mount target drops trailing and duplicate slashes`() {
        assertEquals("/mnt/shared", workspaceMountTargetOrDefault("/mnt/shared/"))
        assertEquals("/mnt/shared/docs", workspaceMountTargetOrDefault("//mnt//shared///docs/"))
        assertEquals("/", workspaceMountTargetOrDefault("/"))
    }

    @Test
    fun `relative mount target is left alone for validation`() {
        assertEquals("mnt/shared", workspaceMountTargetOrDefault(" mnt/shared "))
    }

    // --- 填写路径校验 -------------------------------------------------------

    @Test
    fun `empty source is rejected`() {
        assertEquals(
            WorkspaceMountDraftError.SOURCE_EMPTY,
            validateWorkspaceMountDraft("   ", "/mnt/shared"),
        )
    }

    @Test
    fun `content uri needs the directory picker`() {
        assertEquals(
            WorkspaceMountDraftError.SOURCE_NOT_PATH,
            validateWorkspaceMountDraft(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                "/mnt/shared",
            ),
        )
    }

    @Test
    fun `relative phone path is rejected`() {
        assertEquals(
            WorkspaceMountDraftError.SOURCE_RELATIVE,
            validateWorkspaceMountDraft("storage/emulated/0/Documents", "/mnt/shared"),
        )
    }

    @Test
    fun `whole phone root as source is rejected`() {
        assertEquals(
            WorkspaceMountDraftError.SOURCE_TOO_BROAD,
            validateWorkspaceMountDraft("/", "/mnt/shared"),
        )
    }

    @Test
    fun `relative rootfs path is rejected`() {
        assertEquals(
            WorkspaceMountDraftError.TARGET_RELATIVE,
            validateWorkspaceMountDraft("/storage/emulated/0/Documents", "mnt/shared"),
        )
    }

    @Test
    fun `rootfs root and mnt are too broad`() {
        assertEquals(
            WorkspaceMountDraftError.TARGET_TOO_BROAD,
            validateWorkspaceMountDraft("/storage/emulated/0/Documents", "/"),
        )
        assertEquals(
            WorkspaceMountDraftError.TARGET_TOO_BROAD,
            validateWorkspaceMountDraft("/storage/emulated/0/Documents", "/mnt/"),
        )
    }

    @Test
    fun `duplicate rootfs target is rejected after normalization`() {
        assertEquals(
            WorkspaceMountDraftError.TARGET_DUPLICATE,
            validateWorkspaceMountDraft(
                source = "/storage/emulated/0/Pictures",
                target = "/mnt/shared/",
                existingTargets = listOf("/mnt/shared"),
            ),
        )
    }

    @Test
    fun `valid draft passes and blank target uses the default`() {
        assertNull(
            validateWorkspaceMountDraft(
                source = " /storage/emulated/0/Documents ",
                target = "",
                existingTargets = listOf("/mnt/other"),
            ),
        )
    }

    // --- 展示与测试 tag -----------------------------------------------------

    @Test
    fun `mount row shows source then target`() {
        val mount = WorkspaceMountSpec(
            id = "m1",
            sourcePath = "/storage/emulated/0/Documents",
            targetPath = "/mnt/shared",
        )
        val row = workspaceMountRowText(mount)
        assertTrue(row.contains(mount.sourcePath))
        assertTrue(row.contains(mount.targetPath))
        assertTrue(row.indexOf(mount.sourcePath) < row.indexOf(mount.targetPath))
    }

    @Test
    fun `test tags are unique and non blank`() {
        val tags = listOf(
            WorkspaceAccessTestTags.CARD,
            WorkspaceAccessTestTags.TRUSTED_SWITCH,
            WorkspaceAccessTestTags.ANDROID_PROBE,
            WorkspaceAccessTestTags.ANDROID_REVOKE,
            WorkspaceAccessTestTags.MODE_APP,
            WorkspaceAccessTestTags.MODE_SHIZUKU,
            WorkspaceAccessTestTags.MODE_ROOT,
            WorkspaceAccessTestTags.STORAGE_SETTINGS,
            WorkspaceAccessTestTags.PICK_DIRECTORY,
            WorkspaceAccessTestTags.ADD_PATH,
            WorkspaceAccessTestTags.PATH_SOURCE,
            WorkspaceAccessTestTags.PATH_TARGET,
            WorkspaceAccessTestTags.PATH_CONFIRM,
        )
        assertTrue(tags.all { it.isNotBlank() })
        assertEquals(tags.size, tags.toSet().size)
        assertEquals(tags.size, WorkspacePrivilegeMode.entries.map { WorkspaceAccessTestTags.mode(it) }.toSet().size)
        assertTrue(WorkspaceAccessTestTags.mountRow("a") != WorkspaceAccessTestTags.mountRow("b"))
    }
}
