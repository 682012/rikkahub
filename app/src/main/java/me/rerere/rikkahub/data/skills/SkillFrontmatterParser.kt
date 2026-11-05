package me.rerere.rikkahub.data.skills

import java.io.File
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

object SkillFrontmatterParser {
    /**
     * 列技能时最多读 SKILL.md 开头这么多字符来找 frontmatter 收尾。
     * frontmatter 只有 name/description 这类短字段，8 KiB 足够；正文多大都不影响列表性能。
     */
    internal const val FRONTMATTER_HEAD_CHARS = 8 * 1024

    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    /**
     * 只解析文件里的 frontmatter，不为了列技能把整份 SKILL.md 读进内存。
     *
     * 正常情况下只读开头 [FRONTMATTER_HEAD_CHARS] 个字符：frontmatter 以 `---` 收尾，一定在开头这一段里。
     * 开头一段里看不到收尾的 `---`（frontmatter 特别长，或者正文第一行本身就是一条分隔线）时退回整份读取，
     * 结果和 [parse] 完全一致，不猜内容。
     */
    fun parseFile(file: File, headChars: Int = FRONTMATTER_HEAD_CHARS): SkillFrontmatter {
        val head = readHead(file, headChars)
        if (!head.startsWith("---")) return SkillFrontmatter.Empty
        if (findFrontmatterEndRange(head) != null) return parse(head)
        // 整份读取只在上面那种异常情况下发生；保持旧语义（能解析就解析），只是不再作为常态
        return parse(file.readText())
    }

    /** 读前 [maxChars] 个字符（按字符读，不会把多字节 UTF-8 拆坏） */
    internal fun readHead(file: File, maxChars: Int): String {
        if (maxChars <= 0) return ""
        return file.bufferedReader().use { reader ->
            val buffer = CharArray(maxChars)
            var filled = 0
            while (filled < maxChars) {
                val read = reader.read(buffer, filled, maxChars - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled)
        }
    }

    /** 把 [values] 写成 frontmatter 接在 [body] 前面，[parse] 和 [extractBody] 能原样读回来 */
    fun compose(values: Map<String, Any?>, body: String): String = buildString {
        append("---\n")
        append(createDumper().dump(values))
        append("---\n")
        val text = body.trimStart('\r', '\n').trimEnd()
        if (text.isNotEmpty()) {
            append('\n')
            append(text)
            append('\n')
        }
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }

    private fun createDumper(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            // 一个值占一行：长句子折行后，按行替换文本的工具就对不上了
            splitLines = false
        }
        // 列表写成 [a, b]，和模型按格式约定写出来的一样
        val representer = object : Representer(options) {
            override fun representSequence(
                tag: Tag,
                sequence: Iterable<*>,
                flowStyle: DumperOptions.FlowStyle,
            ): Node = super.representSequence(tag, sequence, DumperOptions.FlowStyle.FLOW)
        }
        return Yaml(representer, options)
    }
}

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    fun toMap(): Map<String, Any?> = values

    fun getList(key: String): List<String> =
        (values[key] as? List<*>).orEmpty().mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
