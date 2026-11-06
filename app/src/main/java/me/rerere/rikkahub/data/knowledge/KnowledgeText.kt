package me.rerere.rikkahub.data.knowledge

import java.security.MessageDigest

/**
 * 文本归一化。索引、去重 hash、chunk 偏移都基于归一化后的正文，
 * 所以这里必须是确定性的：同样的输入永远得到同样的输出。
 */
object KnowledgeTextNormalizer {
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        val start = if (raw.startsWith('\uFEFF')) 1 else 0
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> {
                    // \r\n 与单独的 \r 都归一成 \n
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                    appendNewline(out)
                    pendingSpace = false
                }

                c == '\n' -> {
                    appendNewline(out)
                    pendingSpace = false
                }

                c == '\t' || c == ' ' || c == '\u00A0' || c == '\u3000' -> pendingSpace = true
                // NUL 直接丢掉（不占位），其它控制字符当空格
                c == '\u0000' -> Unit
                c.code < 0x20 -> pendingSpace = true

                else -> {
                    if (pendingSpace && out.isNotEmpty() && out.last() != '\n') out.append(' ')
                    pendingSpace = false
                    out.append(c)
                }
            }
            i++
        }
        return out.toString().trim()
    }

    /** 连续空行最多保留一个（最多两个 \n）。 */
    private fun appendNewline(out: StringBuilder) {
        val trailing = out.length - out.indexOfLast { it != '\n' } - 1
        if (trailing < 2) out.append('\n')
    }
}

/** 内容指纹：归一化正文的 sha256（小写十六进制）。 */
object KnowledgeHash {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}

/**
 * 确定性中文分词：不引入词典、模型或向量库。
 *
 * - 连续 CJK（汉字/假名/谚文）按 bigram 切；单字连写时保留 unigram，避免整段丢召回。
 * - 拉丁字母/数字/下划线按词切，转小写。
 * - 其它字符（标点、空白、emoji）只作分隔符，不产生 token。
 *
 * 索引和查询用同一套规则，因此“命中”只取决于字符，不依赖语言模型。
 */
object KnowledgeTokenizer {
    fun indexTokens(text: String): List<String> {
        val tokens = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                isCjk(c) -> {
                    var j = i
                    while (j < text.length && isCjk(text[j])) j++
                    val run = text.substring(i, j)
                    if (run.length == 1) {
                        tokens.add(run)
                    } else {
                        for (k in 0 until run.length - 1) tokens.add(run.substring(k, k + 2))
                    }
                    i = j
                }

                isWordChar(c) -> {
                    var j = i
                    while (j < text.length && isWordChar(text[j])) j++
                    tokens.add(text.substring(i, j).lowercase())
                    i = j
                }

                else -> i++
            }
        }
        return tokens
    }

    fun indexText(text: String): String = indexTokens(text).joinToString(" ")

    /** 查询 token：去重、保序、限量。 */
    fun queryTokens(query: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (token in indexTokens(query)) {
            if (token.isBlank()) continue
            seen.add(token)
            if (seen.size >= KnowledgeLimits.MAX_QUERY_TOKENS) break
        }
        return seen.toList()
    }

    /**
     * 构造 FTS4 MATCH 表达式。多个 token 用 OR：召回交给 FTS，精度交给打分，
     * 这样长中文问句里只要有一部分表述出现在资料里就能命中。
     */
    fun ftsMatchQuery(query: String): String? {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
    }

    internal fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x3040..0x30FF ||
            cp in 0xAC00..0xD7AF
    }

    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
