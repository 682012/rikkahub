package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.rerere.rikkahub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V6: 其他分支自定义的 provider 类型（如 codex）当前版本没有对应子类
            root["providers"]?.let { element ->
                root["providers"] = filterSupportedProviders(element)
            }

            // V5: 其他分支导出的 settings 可能把非空数值字段写成 null
            root["providers"]?.let { element ->
                root["providers"] = migrateProvidersNumericNulls(element)
            }

            // V4: 移除 assistants 的 allowConversationPromptInjection 开关
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsConversationInjections(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }

    private fun migrateProvidersNumericNulls(element: JsonElement): JsonElement {
        val providers = element as? JsonArray ?: return element
        return JsonArray(providers.map { provider ->
            val providerObject = provider as? JsonObject ?: return@map provider
            val models = providerObject["models"] as? JsonArray ?: return@map provider
            val fixedModels = JsonArray(models.map { model ->
                val modelObject = model as? JsonObject ?: return@map model
                val fields = modelObject.toMutableMap()
                if (fields["contextLength"] is JsonNull) {
                    fields["contextLength"] = JsonPrimitive(1_000_000)
                }
                JsonObject(fields)
            })
            JsonObject(providerObject.toMutableMap().apply { put("models", fixedModels) })
        })
    }

    private val SUPPORTED_PROVIDER_TYPES = setOf("openai", "google", "claude")

    private fun filterSupportedProviders(element: JsonElement): JsonElement {
        val providers = element as? JsonArray ?: return element
        val kept = providers.filter { provider ->
            val type = (provider as? JsonObject)
                ?.get("type")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            type == null || type in SUPPORTED_PROVIDER_TYPES
        }
        val dropped = providers.size - kept.size
        if (dropped > 0) {
            Log.w(TAG, "filterSupportedProviders: dropped $dropped unsupported providers")
        }
        return JsonArray(kept)
    }
}
