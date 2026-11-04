package me.rerere.rikkahub.data.companion

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.characterKey

/**
 * 当前这份人设是从哪来的。
 *
 * [BUILTIN] 是只读资产目录里的完整卡；[USER_OVERRIDE] 是用户自己写的那一整段；
 * [FALLBACK] 是自定义图/应用图标这类没有内置卡的角色，沿用原来的短兜底文案。
 */
enum class PersonaSource { BUILTIN, USER_OVERRIDE, FALLBACK }

/**
 * 一次解析的结果：界面"查看当前生效提示词"和真正发出去的请求必须用同一份。
 *
 * [id] 是内置卡的稳定 id（`CUSTOM`/`LOGO` 这类没有卡的角色为 null）；
 * [version] 是内置卡的版本（用户覆盖时也带上所属卡的版本，方便排查）；
 * [hash] 是 [prompt] 的 UTF-8 SHA-256，用来判断"改没改"和隔离旧请求；
 * [ownerCharacterKey] 是原数据所有者（`whale_maid`/`dragon_girl`…），不是预设别名。
 */
data class ResolvedCompanionPersona(
    val id: String?,
    val source: PersonaSource,
    val version: String,
    val hash: String,
    val prompt: String,
    val name: String,
    val ownerCharacterKey: String,
)

/**
 * 只读内置卡的一条。
 *
 * [promptTemplate] 含 `{{char}}`/`{{user}}`，[defaultEffectivePrompt] 是默认名字下渲染好的全文；
 * 默认渲染结果和 `prompts/<id>.txt` 逐字节相同，所以 hash 可以直接对上 [promptSha256]。
 */
@Serializable
data class CompanionPersonaEntry(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("storage_key") val storageKey: String = "",
    @SerialName("legacy_aliases") val legacyAliases: List<String> = emptyList(),
    val version: String = "",
    @SerialName("prompt_template") val promptTemplate: String = "",
    @SerialName("default_effective_prompt") val defaultEffectivePrompt: String = "",
    @SerialName("prompt_sha256") val promptSha256: String = "",
)

/** 运行时目录文件的 schema；只读这几个字段，未知字段保留在文件里不动。 */
@Serializable
internal data class CompanionPersonaCatalogDocument(
    val schema: String = "",
    @SerialName("pack_version") val packVersion: String = "",
    val characters: List<CompanionPersonaEntry> = emptyList(),
)

/**
 * 解析后的只读内置目录（纯 Kotlin：不碰 Android、不联网、不调模型）。
 *
 * 同一个角色可以用 `id`、`storage_key` 或旧别名查到；旧 `cat` 始终指向小鲸，
 * 不会因为小星有猫耳就改指 Gemini。
 */
class CompanionPersonaCatalog(
    entries: List<CompanionPersonaEntry>,
    val packVersion: String = "",
    val schema: String = "",
) {
    val entries: List<CompanionPersonaEntry> = entries.toList()

    /** id / storage_key 先登记，旧别名后登记：别名永远不会抢走另一只的主身份。 */
    private val index: Map<String, CompanionPersonaEntry> = run {
        val map = LinkedHashMap<String, CompanionPersonaEntry>()
        fun register(key: String, entry: CompanionPersonaEntry) {
            if (key.isNotBlank() && !map.containsKey(key)) map[key] = entry
        }
        for (entry in entries) {
            register(entry.id, entry)
            register(entry.storageKey, entry)
        }
        for (entry in entries) {
            for (alias in entry.legacyAliases) register(alias, entry)
        }
        map
    }

    fun entryFor(characterKey: String?): CompanionPersonaEntry? {
        val key = characterKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        return index[key]
    }

    fun entryForPreset(preset: FloatingCompanionPreset): CompanionPersonaEntry? = entryFor(preset.characterKey)

    fun entryById(id: String): CompanionPersonaEntry? = entries.firstOrNull { it.id == id }

    companion object {
        /** 没有注入目录时的空表：解析会落到 [PersonaSource.FALLBACK]，行为和旧版一致。 */
        val EMPTY = CompanionPersonaCatalog(emptyList())

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /**
         * 读目录文本。坏文件直接抛异常（由调用方记录并保留兜底），
         * 不静默当成"全部没有内置人设"。
         */
        fun parse(text: String): CompanionPersonaCatalog {
            val document = json.decodeFromString<CompanionPersonaCatalogDocument>(text)
            require(document.schema == "six-companions-personas/v1") {
                "unsupported persona catalog schema: ${document.schema}"
            }
            require(document.characters.isNotEmpty()) { "persona catalog has no characters" }
            val duplicates = document.characters.map { it.id }.filter { it.isNotBlank() }.groupBy { it }.filterValues { it.size > 1 }
            require(duplicates.isEmpty()) { "duplicate persona ids: ${duplicates.keys}" }
            return CompanionPersonaCatalog(
                entries = document.characters.filter { it.id.isNotBlank() && it.promptTemplate.isNotBlank() },
                packVersion = document.packVersion,
                schema = document.schema,
            )
        }
    }
}

/** 模板渲染、hash 和默认变量：查看、请求、测试共用同一份实现。 */
object CompanionPersonaText {
    /** 默认用户名；包里的默认导出就是按这个渲染的，换掉会让默认 hash 对不上。 */
    const val DEFAULT_USER_NAME = "你"

    /**
     * 单次替换 `{{char}}` / `{{user}}`。
     *
     * 只扫一遍原文：用户把名字改成 `{{user}}` 之类也不会被二次展开，
     * 不 trim、不截断，结尾换行原样保留，默认名字下结果和 `prompts/<id>.txt` 逐字节一致。
     */
    fun render(template: String, characterName: String, userName: String = DEFAULT_USER_NAME): String {
        if (!template.contains("{{")) return template
        val out = StringBuilder(template.length + 16)
        var index = 0
        while (index < template.length) {
            when {
                template.startsWith("{{char}}", index) -> {
                    out.append(characterName)
                    index += "{{char}}".length
                }

                template.startsWith("{{user}}", index) -> {
                    out.append(userName)
                    index += "{{user}}".length
                }

                else -> {
                    out.append(template[index])
                    index += 1
                }
            }
        }
        return out.toString()
    }

    /** UTF-8 SHA-256，小写十六进制；和包里的 `prompt_sha256` 同一口径。 */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * 进程内的当前目录。
 *
 * 只在 `Application.onCreate` 里从 assets 装一次；测试可以直接 [install] 自己的一份。
 * [revision] 变了说明目录换了版本（覆盖安装、将来的热导入），界面观察它可以主动让旧 run 失效。
 */
object CompanionPersonaCatalogStore {
    @Volatile
    var catalog: CompanionPersonaCatalog = CompanionPersonaCatalog.EMPTY
        private set

    private val _revision = MutableStateFlow(0L)

    /** 每次 [install] 加一；0 表示还没装过任何目录。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun install(next: CompanionPersonaCatalog): Long = synchronized(this) {
        catalog = next
        _revision.value = _revision.value + 1
        _revision.value
    }

    /** 仅测试用：清回空目录，避免测试之间互相污染。 */
    fun resetForTest() = synchronized(this) {
        catalog = CompanionPersonaCatalog.EMPTY
        _revision.value = 0L
    }
}
