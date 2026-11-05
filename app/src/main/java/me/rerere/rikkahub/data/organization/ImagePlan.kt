package me.rerere.rikkahub.data.organization

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 虚拟分类方案：AI 看一遍「图片清单」(名字 / 已有的粗分类 / 描述) 给出几组分组建议。
 *
 * 重要约束：
 * - 只发文字清单，不发图片本身，所以不会上传原图；
 * - 方案只是「虚拟分类」，桶里存的是图片 id，**不移动、不改名、不删除任何文件**；
 * - 方案可编辑：用户可以改名、删桶、把图挪到别的桶，改完自己保存；
 * - 生成失败或模型瞎写（不存在的 id）都不影响已有分类结果。
 */
@Serializable
data class ImagePlanBucket(
    /** 桶的稳定 id，编辑时用；由序号或用户新建时生成。 */
    val id: String,
    val name: String,
    val note: String? = null,
    val itemIds: List<String> = emptyList(),
)

@Serializable
data class ImagePlan(
    val version: Int = 1,
    val createdAt: Long = 0L,
    val modelId: String? = null,
    /** 用户改过之后为 true，界面据此提示「方案已编辑，未重新生成」。 */
    val edited: Boolean = false,
    val buckets: List<ImagePlanBucket> = emptyList(),
) {
    val totalItems: Int get() = buckets.sumOf { it.itemIds.size }

    fun bucketOf(itemId: String): ImagePlanBucket? = buckets.firstOrNull { itemId in it.itemIds }

    companion object {
        const val MAX_BUCKETS = 12
        const val MAX_NAME_LENGTH = 24
    }
}

/** 生成方案时发给模型的清单。只包含文字，不含图片、不含绝对路径。 */
object ImagePlanPrompt {
    val SYSTEM: String = """
你是相册整理助手。用户会给你一份「本机已有图片的清单」，每行是：id、文件名、已有粗分类、简短描述。
请把它们分成 3 到 8 组便于浏览的虚拟分类（例如「工作截图」「风景壁纸」「表情包」）。

要求：
1. 只输出一个 JSON 对象，不要解释、不要代码块；
2. 格式：{"buckets":[{"name":"分类名","note":"一句话说明","items":["图片id"]}]}
3. 分类名 2 到 12 个汉字，同类不要拆太碎，也不要一桶装所有图；
4. items 里只能出现清单中出现过的 id，每张图最多出现在一个桶里；
5. 不确定的图可以不放进任何桶（界面会归到「未分类」）。
""".trimIndent()

    const val USER_PREFIX: String = "图片清单（id | 文件名 | 粗分类 | 描述）："

    /** 一张图一行；描述截断，避免清单过长把提示顶爆。 */
    fun buildUserMessage(items: List<ImageOrganizerItem>, limit: Int = 200): String = buildString {
        appendLine(USER_PREFIX)
        items.asSequence()
            .filter { it.category != null || it.description != null }
            .take(limit)
            .forEach { item ->
                append(item.id)
                append(" | ")
                append(item.name.take(80))
                append(" | ")
                append(item.category?.label ?: "未识别")
                append(" | ")
                append(item.description?.take(120) ?: "-")
                appendLine()
            }
    }
}

data class ParsedImagePlan(
    val plan: ImagePlan?,
    /** 被丢掉的 id 数（模型编造或图片已被删）。 */
    val droppedItems: Int = 0,
    val error: String? = null,
)

/** 解析并清洗模型返回的方案。纯 JVM，可单测。 */
object ImagePlanParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        raw: String?,
        knownIds: Set<String>,
        now: Long = System.currentTimeMillis(),
        modelId: String? = null,
    ): ParsedImagePlan {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ParsedImagePlan(null, error = "模型没有返回内容")
        val array = extractBuckets(text)
            ?: return ParsedImagePlan(null, error = "没有解析出分类分组")
        val seen = HashSet<String>()
        var dropped = 0
        val buckets = mutableListOf<ImagePlanBucket>()
        array.forEachIndexed { index, element ->
            val obj = (element as? JsonObject) ?: return@forEachIndexed
            val name = stringField(obj, "name", "title", "bucket")?.trim()?.take(ImagePlan.MAX_NAME_LENGTH)
                ?: return@forEachIndexed
            if (buckets.any { it.name == name }) return@forEachIndexed
            val note = stringField(obj, "note", "description", "desc")?.trim()?.take(120)
            val ids = mutableListOf<String>()
            val itemsElement = obj["items"] ?: obj["ids"] ?: obj["images"]
            (itemsElement as? JsonArray)?.forEach { item ->
                val id = (item as? JsonPrimitive)?.plainContent()?.trim() ?: return@forEach
                if (id.isEmpty()) return@forEach
                if (id !in knownIds) {
                    dropped++
                    return@forEach
                }
                if (!seen.add(id)) return@forEach
                ids += id
            }
            if (ids.isEmpty()) return@forEachIndexed
            buckets += ImagePlanBucket(
                id = "b${index + 1}",
                name = name,
                note = note,
                itemIds = ids,
            )
        }
        if (buckets.isEmpty()) {
            return ParsedImagePlan(null, droppedItems = dropped, error = "模型给的分组里没有可用的图片")
        }
        return ParsedImagePlan(
            plan = ImagePlan(
                createdAt = now,
                modelId = modelId,
                buckets = buckets.take(ImagePlan.MAX_BUCKETS),
            ),
            droppedItems = dropped,
        )
    }

    /** 找到 {"buckets":[...]} 或直接一个数组；模型常带代码块和前言，这里宽松截取。 */
    private fun extractBuckets(text: String): JsonArray? {
        val candidate = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { json.parseToJsonElement(candidate).jsonObject["buckets"]?.jsonArray }
            .getOrNull()?.let { return it }
        runCatching { json.parseToJsonElement(candidate).jsonArray }.getOrNull()?.let { return it }
        // 兜底：从第一个 [ 到最后一个 ] 之间取
        val start = candidate.indexOf('[')
        val end = candidate.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(candidate.substring(start, end + 1)).jsonArray }.getOrNull()
    }

    private fun stringField(obj: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.plainContent()?.takeIf { it.isNotBlank() }
    }

    private fun JsonPrimitive.plainContent(): String? = content.takeIf { it != "null" }
}

/** `filesDir/image_organizer/plan.json`。方案是用户可见可改的数据，读写都不能悄悄失败。 */
class ImageOrganizerPlanStore(private val file: File) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(key: String): Any =
            locks[key] ?: Any().let { created -> locks.putIfAbsent(key, created) ?: created }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }

    private val lock: Any = lockFor(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })

    @Volatile
    var lastFailure: String? = null
        private set

    fun read(): ImagePlan? = synchronized(lock) {
        if (!file.isFile) return@synchronized null
        runCatching { json.decodeFromString<ImagePlan>(file.readText()) }.getOrNull()
            ?.takeIf { it.buckets.isNotEmpty() }
    }

    fun write(plan: ImagePlan?): Boolean = synchronized(lock) {
        if (plan == null) {
            runCatching { file.delete() }
            lastFailure = null
            return@synchronized true
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(plan).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replaceAtomically(temp)) {
                throw IOException("临时文件改名失败")
            }
            lastFailure = null
            true
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            lastFailure = "分类方案没能保存：${t.message ?: t::class.simpleName ?: "未知原因"}"
            false
        }
    }

    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
