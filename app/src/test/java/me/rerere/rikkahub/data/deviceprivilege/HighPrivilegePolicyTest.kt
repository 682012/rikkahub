package me.rerere.rikkahub.data.deviceprivilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：命令拼装安全、settings 校验、白名单、UID 解析。 */
class HighPrivilegePolicyTest {

    private val pkg = "me.rerere.rikkahub.fork"

    @Test
    fun `whitelist accepts only the four development permissions`() {
        assertEquals(
            listOf("WRITE_SECURE_SETTINGS", "DUMP", "READ_LOGS", "CHANGE_CONFIGURATION"),
            HighPrivilegePolicy.developmentPermissionIds,
        )
        assertEquals("android.permission.DUMP", HighPrivilegePolicy.developmentPermission("DUMP"))
        assertEquals("android.permission.DUMP", HighPrivilegePolicy.developmentPermission("android.permission.DUMP"))
        assertNull(HighPrivilegePolicy.developmentPermission("INJECT_EVENTS"))
        assertNull(HighPrivilegePolicy.developmentPermission("android.permission.INSTALL_PACKAGES"))
        assertNull(HighPrivilegePolicy.developmentPermission("REBOOT"))
        assertNull(HighPrivilegePolicy.developmentPermission(""))
    }

    @Test
    fun `adb commands never include non whitelisted ids`() {
        val commands = HighPrivilegePolicy.adbCommands(
            pkg,
            setOf("WRITE_SECURE_SETTINGS", "INJECT_EVENTS", "READ_LOGS"),
        )
        assertEquals(
            listOf(
                "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                "adb shell pm grant $pkg android.permission.READ_LOGS",
            ),
            commands,
        )
        assertTrue(HighPrivilegePolicy.adbCommands(pkg, emptySet()).isEmpty())
    }

    @Test
    fun `shell quoting neutralises injection`() {
        assertEquals("'plain'", HighPrivilegePolicy.quote("plain"))
        assertEquals("'a'\\''b'", HighPrivilegePolicy.quote("a'b"))
        val hostile = "x'; reboot; echo '"
        val quoted = HighPrivilegePolicy.quote(hostile)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        // 整个恶意串被关在单引号里：每个内部单引号都换成了 '\'' 序列
        assertEquals("'x'\\''; reboot; echo '\\'''", quoted)

        val command = HighPrivilegePolicy.grantCommand(pkg, "android.permission.DUMP")
        assertEquals(
            "pm grant --user current '$pkg' 'android.permission.DUMP'",
            command,
        )
        assertTrue(HighPrivilegePolicy.grantCommand(pkg, "android.permission.DUMP").contains("--user current"))
    }

    @Test
    fun `grant command rejects malformed package names`() {
        val bad = listOf("", "pkg; rm -rf /", "me.rerere.rikkahub.fork'; reboot; '")
        for (value in bad) {
            val failed = runCatching { HighPrivilegePolicy.grantCommand(value, "android.permission.DUMP") }
            assertTrue("expected rejection for '$value'", failed.isFailure)
        }
        assertTrue(
            HighPrivilegePolicy.grantCommand("me.rerere.rikkahub.fork.debug", "android.permission.DUMP")
                .contains("me.rerere.rikkahub.fork.debug"),
        )
    }

    @Test
    fun `settings validation enforces namespace key and value`() {
        val ok = HighPrivilegePolicy.validateSetting("secure", "enabled_notification_listeners", "a:b")
        assertEquals(HighPrivilegePolicy.SettingsNamespace.SECURE, ok.namespace)
        assertEquals("a:b", ok.value)

        val delete = HighPrivilegePolicy.validateSetting("global", "adb_enabled", null)
        assertNull(delete.value)

        assertTrue(runCatching { HighPrivilegePolicy.validateSetting("ro", "k", "v") }.isFailure)
        assertTrue(runCatching { HighPrivilegePolicy.validateSetting("system", "", "v") }.isFailure)
        assertTrue(runCatching { HighPrivilegePolicy.validateSetting("system", "bad key", "v") }.isFailure)
        assertTrue(runCatching { HighPrivilegePolicy.validateSetting("system", "k".repeat(129), null) }.isFailure)
        assertTrue(runCatching { HighPrivilegePolicy.validateSetting("system", "k", "a\nb") }.isFailure)
        assertTrue(
            runCatching {
                HighPrivilegePolicy.validateSetting("system", "k", "v".repeat(HighPrivilegePolicy.MAX_VALUE_CHARS + 1))
            }.isFailure,
        )
    }

    @Test
    fun `settings commands quote key and value`() {
        val setting = HighPrivilegePolicy.validateSetting("secure", "a_b", "x'; reboot; '")
        val put = HighPrivilegePolicy.settingsPutCommand(setting)
        assertTrue(put.startsWith("settings put secure 'a_b' '"))
        assertEquals("settings put secure 'a_b' " + HighPrivilegePolicy.quote("x'; reboot; '"), put)
        assertFalse(put.contains("; reboot; ' "))

        assertEquals("settings get system 'k'", HighPrivilegePolicy.settingsGetCommand(
            HighPrivilegePolicy.SettingsNamespace.SYSTEM, "k",
        ))
        assertEquals("settings delete global 'k'", HighPrivilegePolicy.settingsDeleteCommand(
            HighPrivilegePolicy.SettingsNamespace.GLOBAL, "k",
        ))
        assertTrue(runCatching { HighPrivilegePolicy.settingsPutCommand(setting.copy(value = null)) }.isFailure)
    }

    @Test
    fun `timeout is clamped and uid parsing never guesses`() {
        assertEquals(1_000L, HighPrivilegePolicy.clampTimeoutSeconds(0))
        assertEquals(30_000L, HighPrivilegePolicy.clampTimeoutSeconds(30))
        assertEquals(600_000L, HighPrivilegePolicy.clampTimeoutSeconds(99_999))

        assertEquals(2000, HighPrivilegePolicy.parseUid("2000\n"))
        assertEquals(0, HighPrivilegePolicy.parseUid("uid=0\ncontext=u:r:shell:s0"))
        assertNull(HighPrivilegePolicy.parseUid("error: closed"))
        assertNull(HighPrivilegePolicy.parseUid(""))
        assertNull(HighPrivilegePolicy.parseUid("uid unknown"))
    }

    @Test
    fun `shizuku status must say READY`() {
        assertTrue(HighPrivilegePolicy.shizukuReady("status=READY; api=13; uid=2000; userServiceBound=false; fallback=none"))
        assertFalse(HighPrivilegePolicy.shizukuReady("status=PERMISSION_DENIED; api=13; uid=unknown"))
        assertFalse(HighPrivilegePolicy.shizukuReady("status=NOT_RUNNING; api=-1; uid=unknown"))
        assertFalse(HighPrivilegePolicy.shizukuReady(""))
    }

    @Test
    fun `output is bounded and secret keys are flagged`() {
        val long = "x".repeat(HighPrivilegePolicy.MAX_OUTPUT_CHARS + 50)
        val (bounded, truncated) = HighPrivilegePolicy.bound(long)
        assertTrue(truncated)
        assertTrue(bounded.length < long.length)
        assertTrue(bounded.startsWith("x".repeat(64)))

        val (short, notTruncated) = HighPrivilegePolicy.bound("ok")
        assertEquals("ok", short)
        assertFalse(notTruncated)

        assertTrue(HighPrivilegePolicy.looksSecret("wifi_password"))
        assertTrue(HighPrivilegePolicy.looksSecret("auth_token"))
        assertFalse(HighPrivilegePolicy.looksSecret("screen_brightness"))
    }

    @Test
    fun `development rows never report granted when undeclared`() {
        assertEquals(PermissionState.UNAVAILABLE, HighPrivilegePolicy.developmentRowState(declared = false, granted = false))
        assertEquals(PermissionState.UNAVAILABLE, HighPrivilegePolicy.developmentRowState(declared = false, granted = true))
        assertEquals(PermissionState.DENIED, HighPrivilegePolicy.developmentRowState(declared = true, granted = false))
        assertEquals(PermissionState.GRANTED, HighPrivilegePolicy.developmentRowState(declared = true, granted = true))
    }

    @Test
    fun `read only catalogue has no interpolated commands`() {
        for (spec in HighPrivilegePolicy.READ_ONLY_COMMANDS) {
            assertFalse(spec.command.contains("$"))
            assertFalse(spec.command.contains(";"))
            assertFalse(spec.command.contains("|"))
        }
        assertNull(HighPrivilegePolicy.readOnly("nope"))
        assertTrue(HighPrivilegePolicy.logcatCommand().startsWith("logcat -d"))
        assertTrue(HighPrivilegePolicy.logcatCommand(10_000).endsWith("-t 500"))
    }
}
