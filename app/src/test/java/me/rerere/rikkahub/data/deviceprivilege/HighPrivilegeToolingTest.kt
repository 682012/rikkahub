package me.rerere.rikkahub.data.deviceprivilege

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：工具层的**静态**分类（写 / 只读 / 需审批 / 是否硬绑 Shizuku）与 schema 必填项。
 *
 * 这里刻意不碰控制器和 Android：授权门禁由 `HighPrivilegeGateTest` 覆盖，
 * 本文件只保证"分类不会把写工具悄悄降级成只读"、"写工具的 schema 一定要求 idempotency_key"、
 * 以及"settings/packages 不会被误判成离不开 Shizuku"（否则权限中心里 app-granted 的合法路径会被挡死）。
 */
class HighPrivilegeToolingTest {

    private val writes = setOf(HighPrivilegeTooling.SETTINGS_PUT, HighPrivilegeTooling.SHELL)

    @Test
    fun `write classification covers exactly settings_put and shell`() {
        assertEquals(writes, HighPrivilegeTooling.all.filter { HighPrivilegeTooling.isWrite(it) }.toSet())
        assertTrue(HighPrivilegeTooling.all.containsAll(writes))
        assertEquals(6, HighPrivilegeTooling.all.size)
    }

    @Test
    fun `read only tools never need approval except diagnostics`() {
        val needsApproval = HighPrivilegeTooling.all.filter { HighPrivilegeTooling.isSensitive(it) }.toSet()
        assertEquals(writes + HighPrivilegeTooling.DIAGNOSTICS, needsApproval)
        // 写工具必须永远需要审批（不能被参数或调用方放宽）
        writes.forEach { assertTrue(HighPrivilegeTooling.isSensitive(it)) }
        // 只读探测不应被误标成敏感
        listOf(HighPrivilegeTooling.STATUS, HighPrivilegeTooling.SETTINGS_GET, HighPrivilegeTooling.PACKAGES)
            .forEach { assertFalse(HighPrivilegeTooling.isSensitive(it)) }
    }

    @Test
    fun `only shell and diagnostics are hard bound to Shizuku`() {
        assertEquals(
            setOf(HighPrivilegeTooling.SHELL, HighPrivilegeTooling.DIAGNOSTICS),
            HighPrivilegeTooling.all.filter { HighPrivilegeTooling.needsShizuku(it) }.toSet(),
        )
        // App 已获 WRITE_SECURE_SETTINGS（或 system 命名空间 canWrite）时，控制器走 app-granted；
        // 工具层不能再叠一道"必须有 Shizuku"，否则那条合法路径会被判死。
        assertFalse(HighPrivilegeTooling.needsShizuku(HighPrivilegeTooling.SETTINGS_PUT))
        assertFalse(HighPrivilegeTooling.needsShizuku(HighPrivilegeTooling.SETTINGS_GET))
        // packages 只读 PackageManager，不需要 shell 通道
        assertFalse(HighPrivilegeTooling.needsShizuku(HighPrivilegeTooling.PACKAGES))
    }

    @Test
    fun `write schemas require idempotency key and read schemas do not`() {
        HighPrivilegeTooling.all.forEach { name ->
            val schema = HighPrivilegeTooling.schema(name)
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            assertEquals("schema must be closed for $name", false, schema["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
            val required = (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (HighPrivilegeTooling.isWrite(name)) {
                assertTrue("$name must require idempotency_key", required.contains(HighPrivilegeTooling.IDEMPOTENCY_KEY_FIELD))
            } else {
                assertFalse("$name must not require idempotency_key", required.contains(HighPrivilegeTooling.IDEMPOTENCY_KEY_FIELD))
            }
        }
    }

    @Test
    fun `every tool has a description and an object property bag`() {
        HighPrivilegeTooling.all.forEach { name ->
            assertTrue("$name needs a description", HighPrivilegeTooling.description(name).length > 40)
            assertNotNull("$name needs a properties bag", HighPrivilegeTooling.schema(name)["properties"] as? JsonObject)
        }
    }

    @Test
    fun `secret redaction stays opt in`() {
        val get = HighPrivilegeTooling.schema(HighPrivilegeTooling.SETTINGS_GET)
        val properties = get["properties"] as JsonObject
        val flag = properties[HighPrivilegeTooling.INCLUDE_SENSITIVE_FIELD] as JsonObject
        assertEquals("boolean", flag["type"]?.jsonPrimitive?.content)
        // 默认值必须是 false 或干脆不写（宿主按 false 处理），绝不能默认吐真值
        assertFalse(flag["default"]?.jsonPrimitive?.content?.toBoolean() == true)
    }
}
