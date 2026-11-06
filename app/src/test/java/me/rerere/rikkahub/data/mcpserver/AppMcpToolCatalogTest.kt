package me.rerere.rikkahub.data.mcpserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 policy 测试：不碰 Android、不碰 Koin。
 *
 * 重点不是"工具数量"，而是几条会被悄悄破坏的约束：
 * 写工具必须真的声明 idempotency_key、任何 schema 都不许出现 assistant_id 越权参数、
 * 开关过滤不能漏、工具名不能重复。
 */
class AppMcpToolCatalogTest {

    private val all = AppMcpToolCatalog.catalog()

    @Test
    fun `tool names are unique and snake_case`() {
        val names = all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue("bad name: $name", Regex("^[a-z][a-z0-9_]*$").matches(name))
        }
    }

    @Test
    fun `declared tools cover the required feature areas`() {
        val names = all.map { it.name }.toSet()
        listOf(
            "app_status",
            "chat_list", "chat_search", "chat_read", "chat_send",
            "compression_status", "compression_start", "compression_cancel",
            "autofile_status", "autofile_history", "autofile_details",
            "autofile_run", "autofile_cancel", "autofile_undo",
            "subagent_list", "subagent_result", "subagent_cancel",
            "automation_list", "automation_recipe", "automation_history",
            "automation_save", "automation_schedule", "automation_run", "automation_cancel",
        ).forEach { required ->
            assertTrue("missing declared tool: $required", names.contains(required))
        }
    }

    @Test
    fun `no schema exposes an assistant_id style parameter`() {
        all.forEach { spec ->
            // 只查参数名：description 里出现 "bound assistant" 是正常的说明文字。
            val text = spec.inputSchema.toString()
            assertFalse("${spec.name} lets the caller pick an assistant", text.contains("assistant_id"))
            assertFalse("${spec.name} lets the caller pick an assistant", text.contains("assistantId"))
            val properties = spec.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            assertFalse(spec.name, properties.containsKey("assistant_id"))
            assertFalse(spec.name, properties.containsKey("assistantId"))
        }
    }

    @Test
    fun `every write tool requires an idempotency key in its schema`() {
        val writes = all.filter { !it.readOnly }
        assertTrue("no write tools found", writes.isNotEmpty())
        writes.forEach { spec ->
            assertTrue("${spec.name} must require a key", spec.requiresIdempotencyKey)
            val properties = spec.inputSchema["properties"] as? JsonObject
            assertNotNull("${spec.name} has no properties", properties)
            assertTrue(
                "${spec.name} does not declare idempotency_key",
                properties!!.containsKey(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD),
            )
            val required = (spec.inputSchema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            assertTrue(
                "${spec.name} does not require idempotency_key",
                required.contains(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD),
            )
        }
    }

    @Test
    fun `read only tools never require a key`() {
        all.filter { it.readOnly }.forEach { spec ->
            assertFalse(spec.name, spec.requiresIdempotencyKey)
            val required = (spec.inputSchema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            assertFalse(spec.name, required.contains(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD))
        }
    }

    @Test
    fun `schemas are closed objects`() {
        all.forEach { spec ->
            assertEquals(spec.name, "object", (spec.inputSchema["type"] as? JsonPrimitive)?.content)
            assertEquals(spec.name, "false", (spec.inputSchema["additionalProperties"] as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun `without a bound assistant no chat tool is visible`() {
        val gates = appMcpGates(
            boundAssistantId = null,
            allowRead = true,
            allowMutations = true,
            allowModelRequests = true,
            allowDevice = false,
            allowAutomation = true,
        )
        val visible = AppMcpToolCatalog.visible(gates).map { it.name }.toSet()
        assertTrue(visible.contains("app_status"))
        assertTrue(visible.contains("automation_list"))
        assertFalse(visible.contains("chat_list"))
        assertFalse(visible.contains("chat_send"))
        assertFalse(visible.contains("autofile_status"))
        assertFalse(visible.contains("subagent_list"))
    }

    @Test
    fun `read only binding hides every write tool`() {
        val gates = bound(read = true, mutations = false, modelRequests = false)
        val visible = AppMcpToolCatalog.visible(gates)
        assertTrue(visible.map { it.name }.contains("chat_read"))
        assertTrue(visible.none { !it.readOnly })
        // 模型请求开关单独打开也不够：没有写权限就不该出现 chat_send。
        val modelOnly = bound(read = true, mutations = false, modelRequests = true)
        assertFalse(AppMcpToolCatalog.visible(modelOnly).map { it.name }.contains("chat_send"))
    }

    @Test
    fun `model request tools need their own switch`() {
        val writeOnly = bound(read = true, mutations = true, modelRequests = false)
        val names = AppMcpToolCatalog.visible(writeOnly).map { it.name }.toSet()
        assertFalse(names.contains("chat_send"))
        assertFalse(names.contains("compression_start"))
        assertTrue(names.contains("chat_rename"))

        val withModel = bound(read = true, mutations = true, modelRequests = true)
        val modelNames = AppMcpToolCatalog.visible(withModel).map { it.name }.toSet()
        assertTrue(modelNames.contains("chat_send"))
        assertTrue(modelNames.contains("compression_start"))
    }

    @Test
    fun `automation tools follow their own switch`() {
        val off = bound(read = true, mutations = true, modelRequests = true, automation = false)
        assertTrue(AppMcpToolCatalog.visible(off).none { it.name.startsWith("automation_") })
        val on = bound(read = true, mutations = true, modelRequests = true, automation = true)
        assertTrue(AppMcpToolCatalog.visible(on).any { it.name.startsWith("automation_") })
    }

    @Test
    fun `read gate is the floor for mutations`() {
        val gates = AppMcpGates(
            boundAssistantId = "00000000-0000-0000-0000-000000000001",
            read = false,
            mutations = true,
            modelRequests = true,
        )
        assertFalse(gates.grants(AppMcpGate.READ))
        assertFalse(gates.grants(AppMcpGate.MUTATIONS))
        assertFalse(gates.grants(AppMcpGate.MODEL_REQUESTS))
        assertTrue(AppMcpToolCatalog.visible(gates).none { it.name.startsWith("chat_") })
    }

    @Test
    fun `find is exact and unknown names stay unknown`() {
        assertNotNull(AppMcpToolCatalog.find("chat_send"))
        assertNull(AppMcpToolCatalog.find("chat_send "))
        assertNull(AppMcpToolCatalog.find("CHAT_SEND"))
        assertNull(AppMcpToolCatalog.find("run_script"))
        assertNull(AppMcpToolCatalog.find("device_tap"))
    }

    @Test
    fun `device schema gets a required idempotency key`() {
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject { put("x", buildJsonObject { put("type", "integer") }) },
            )
            put("additionalProperties", false)
        }
        val patched = AppMcpToolCatalog.withIdempotencyKey(schema)
        val properties = patched["properties"] as? JsonObject
        assertTrue(properties!!.containsKey(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD))
        val required = (patched["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        assertTrue(required.contains(AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD))
        // 原字段不能丢
        assertTrue(properties.containsKey("x"))
    }

    @Test
    fun `limits clamp page size characters and offsets`() {
        assertEquals(AppMcpLimits.DEFAULT_PAGE, AppMcpLimits.clampPage(null))
        assertEquals(AppMcpLimits.MAX_PAGE, AppMcpLimits.clampPage(9999))
        assertEquals(1, AppMcpLimits.clampPage(0))
        assertEquals(AppMcpLimits.DEFAULT_CHARS, AppMcpLimits.clampChars(null))
        assertEquals(AppMcpLimits.MAX_CHARS, AppMcpLimits.clampChars(999999))
        assertEquals(200, AppMcpLimits.clampChars(-5))
        assertEquals(0, AppMcpLimits.clampOffset(null))
        assertEquals(0, AppMcpLimits.clampOffset(-1))
    }

    private fun bound(
        read: Boolean,
        mutations: Boolean,
        modelRequests: Boolean,
        automation: Boolean = false,
        device: Boolean = false,
    ): AppMcpGates = AppMcpGates(
        boundAssistantId = "11111111-1111-1111-1111-111111111111",
        read = read,
        mutations = mutations,
        modelRequests = modelRequests,
        automation = automation,
        device = device,
    )
}
