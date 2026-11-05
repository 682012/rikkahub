package me.rerere.rikkahub.data.organization.autofile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * 严格解析快速模型返回的分类 JSON。
 *
 * 契约要求「畸形输出一律不动」，所以这里选择**宁可一条都不采纳**：
 * - 只接受一个纯粹的 JSON 对象（`{...}`），带代码块、前后有解释文字的直接判失败；
 * - 顶层只允许 `assignments`，每项必须**同时**有 `id` / `name` / `isNew` / `confidence`，
 *   多一个未知字段也算失败（`ignoreUnknownKeys = false`）；
 * - id 不是合法 UUID、`confidence` 是 NaN/Infinity、name 空白的项直接丢掉；
 * - 同一个 id 出现多次时全部丢掉（重复意味着模型自己没对齐，不猜哪条算数）；
 * - 是否存在于候选集合、是否够置信、要建几个库，交给 [ConversationAutoFilePlanner]。
 */
object ConversationAutoFileParser {
    const val MAX_NAME_LENGTH = 40

    /** 输出长度上限：模型吐别的东西时先截断，避免把整段长文塞进正则。 */
    const val MAX_OUTPUT_LENGTH = 20_000

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Serializable
    private data class RawRoot(val assignments: List<RawAssignment>)

    @Serializable
    private data class RawAssignment(
        val id: String,
        val name: String,
        val isNew: Boolean,
        val confidence: Double,
    )

    fun parse(rawText: String): AutoFileParsedClassification {
        val text = rawText.take(MAX_OUTPUT_LENGTH).trim()
        if (text.isEmpty()) return failure("模型没有返回内容")
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return failure("模型输出不是 JSON 对象")
        }
        val root = runCatching { json.decodeFromString(RawRoot.serializer(), text) }.getOrElse {
            return failure("分类结果格式不正确")
        }

        val counts = root.assignments.groupingBy { it.id.trim() }.eachCount()
        val seen = HashSet<Uuid>()
        val assignments = ArrayList<AutoFileAssignment>(root.assignments.size)
        root.assignments.forEach { raw ->
            val id = runCatching { Uuid.parse(raw.id.trim()) }.getOrNull() ?: return@forEach
            if ((counts[raw.id.trim()] ?: 0) > 1) return@forEach
            if (!seen.add(id)) return@forEach
            if (!raw.confidence.isFinite() || raw.confidence < 0.0 || raw.confidence > 1.0) return@forEach
            val name = cleanName(raw.name) ?: return@forEach
            assignments += AutoFileAssignment(
                conversationId = id,
                folderName = name,
                isNew = raw.isNew,
                confidence = raw.confidence,
            )
        }
        return AutoFileParsedClassification(assignments = assignments)
    }

    private fun failure(message: String) = AutoFileParsedClassification(assignments = emptyList(), error = message)

    /** 折叠空白、去掉首尾空格、按 [MAX_NAME_LENGTH] 截断；空名字不算建议。 */
    fun cleanName(raw: String): String? {
        val name = raw.replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) return null
        return if (name.length > MAX_NAME_LENGTH) name.take(MAX_NAME_LENGTH).trim() else name
    }
}
