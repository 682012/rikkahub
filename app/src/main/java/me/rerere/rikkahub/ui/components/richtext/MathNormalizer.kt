package me.rerere.rikkahub.ui.components.richtext

/**
 * 把大模型常见的公式写法整理成 Markdown 解析器（BetterText 的 GFM）认得的形状。
 *
 * 解析器只认 `$...$` 和 `$$...$$`，`\( \)`、`\[ \]` 它会自己转换；剩下几类写法会原样显示成源码：
 *
 * 1. 没有定界符的 `\begin{align}...\end{align}`、`\begin{equation}...\end{equation}`；
 * 2. `$$` 块中间夹了空行——解析器要求 `$$` 之间不能有空行，否则整块退回文本；
 * 3. 单个 `$` 里写了换行 `\\`（多行公式）——行内渲染会把整块当错误处理。
 *
 * 这里只动公式字面量：代码围栏、行内代码、`\$` 转义都原样保留；`$5 and $10`、`$20/$5` 这类金额
 * 的判断和解析器保持一致（`$` 后面紧跟空白、闭合 `$` 后面紧跟数字都不算公式），不会被吃掉。
 * 内容没写完时（流式输出到一半）保持原样，等完整了下一帧再转换，不会把半截公式变成乱码。
 *
 * 纯字符串处理，没有 Android 依赖，可以直接单测。
 */
object MathNormalizer {

    /**
     * 返回可以交给 Markdown 渲染的正文。没有公式时原样返回（同一个实例）。
     *
     * @param markdown 原始 Markdown 正文，可以是不完整的（[streaming] 时的半截内容）
     */
    fun normalize(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        // 没有反斜杠和美元符号就不可能有公式，省掉一次扫描
        if (!markdown.contains('\\') && !markdown.contains('$')) return markdown
        val out = StringBuilder(markdown.length + 16)
        var i = 0
        val n = markdown.length
        while (i < n) {
            // 缩进代码块：四个空格起头（前面是空行或文首）的整段都是代码
            if (markdown[i] == ' ' && (i == 0 || markdown[i - 1] == '\n')) {
                val codeEnd = indentedCodeEnd(markdown, i)
                if (codeEnd > i) {
                    out.append(markdown, i, codeEnd)
                    i = codeEnd
                    continue
                }
            }
            val c = markdown[i]
            when (c) {
                '`', '~' -> {
                    val fence = fenceEnd(markdown, i)
                    if (fence > i) {
                        out.append(markdown, i, fence)
                        i = fence
                        continue
                    }
                    val span = if (c == '`') codeSpanEnd(markdown, i) else -1
                    if (span > i) {
                        out.append(markdown, i, span)
                        i = span
                    } else {
                        out.append(c)
                        i++
                    }
                }

                '\\' -> i = copyBackslash(markdown, i, out)

                '$' -> i = copyDollar(markdown, i, out)

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // ---- 反斜杠开始的语法：\$ 转义、\( \) \[ \]、没定界符的 \begin{align} ----

    /** 处理 [i] 处的反斜杠，返回新的位置。 */
    private fun copyBackslash(text: String, i: Int, out: StringBuilder): Int {
        val next = text.getOrNull(i + 1)
        if (next == null) {
            out.append('\\')
            return i + 1
        }
        // \$ 是字面量美元符号，两个字符一起复制，后面的 $ 就不会被当作公式
        if (next == '$' || next == '\\') {
            out.append(text, i, i + 2)
            return i + 2
        }
        if (next == '(') {
            val close = text.indexOf("\\)", i + 2)
            if (close < 0) {
                out.append(text, i, i + 2)
                return i + 2
            }
            val formula = text.substring(i + 2, close)
            if (needsDisplay(formula)) {
                // 多行/环境型公式放进 \[ \]，解析器会转成 $$ $$
                out.append("\\[").append(oneLine(formula)).append("\\]")
            } else {
                out.append(text, i, close + 2)
            }
            return close + 2
        }
        if (next == '[') {
            // \[ \] 解析器自己会转换（并且会把换行拼成一行）；这里只把顶层环境换成 $$ 里的写法
            val close = text.indexOf("\\]", i + 2)
            if (close < 0) {
                out.append(text, i, i + 2)
                return i + 2
            }
            out.append("\\[").append(rewriteEnvironments(text.substring(i + 2, close))).append("\\]")
            return close + 2
        }
        if (next == 'b' && text.startsWith("\\begin{", i)) {
            val nameEnd = text.indexOf('}', i + 7)
            if (nameEnd < 0) {
                out.append(text, i, i + 2)
                return i + 2
            }
            val env = text.substring(i + 7, nameEnd)
            if (env in DISPLAY_ENVIRONMENTS) {
                val end = environmentEnd(text, i, env)
                if (end > 0) {
                    // 补上 $$ 让整块变成块级公式，同时把 align/equation 换成 $$ 里合法的写法
                    out.append("$$").append(oneLine(rewriteEnvironments(text.substring(i, end)))).append("$$")
                    return end
                }
            }
            out.append(text, i, i + 2)
            return i + 2
        }
        out.append(text, i, i + 2)
        return i + 2
    }

    // ---- 美元符号：$$ 块级、$ 行内 ----

    /** 处理 [i] 处的美元符号，返回新的位置。 */
    private fun copyDollar(text: String, i: Int, out: StringBuilder): Int {
        if (isEscaped(text, i)) {
            out.append('$')
            return i + 1
        }
        var run = 0
        while (i + run < text.length && text[i + run] == '$') run++
        if (run >= 2) {
            val close = blockMathEnd(text, i, run)
            if (close < 0) {
                // 没写完（流式输出到一半），保持原样，别把半截公式当公式
                out.append(text, i, i + run)
                return i + run
            }
            val body = text.substring(i + run, close)
            out.append(text, i, i + run)
                .append(fixBlock(body))
                .append(text, close, close + run)
            return close + run
        }
        // 单个 $：先按解析器的规则判断是不是公式，金额之类原样放过
        val close = inlineMathEnd(text, i)
        if (close < 0) {
            out.append('$')
            return i + 1
        }
        val body = text.substring(i + 1, close)
        if (needsDisplay(body)) {
            // 行内 $ 里写了 \\ 或环境，行内渲染会失败，升级成块级
            out.append("$$").append(oneLine(body)).append("$$")
        } else {
            out.append(text, i, close + 1)
        }
        return close + 1
    }

    /** 单个 `$` 的公式结束位置（指向闭合的 `$`），不是公式时返回 -1。 */
    private fun inlineMathEnd(text: String, open: Int): Int {
        val first = text.getOrNull(open + 1)
        // 解析器：$ 后面紧跟空白不开公式（$ 20 $ 这种不当公式）
        if (first == null || first.isWhitespace()) return -1
        var from = open + 1
        while (true) {
            val next = text.indexOf('$', from)
            if (next < 0) return -1
            // 行内公式不跨空行
            val blank = text.indexOf("\n\n", from)
            if (blank in 0 until next) return -1
            var run = 0
            while (next + run < text.length && text[next + run] == '$') run++
            if (run == 1 && !isEscaped(text, next)) {
                val before = text[next - 1]
                val after = text.getOrNull(next + 1)
                // 解析器：闭合 $ 前面不能是空白，后面紧跟数字时按金额（$5 and $10）处理
                if (!before.isWhitespace() && (after == null || !after.isDigit())) return next
            }
            from = next + run
        }
    }

    /** `$$` 块的结束位置（指向闭合的第一个 `$`），没写完时返回 -1。 */
    private fun blockMathEnd(text: String, open: Int, run: Int): Int {
        var from = open + run
        while (true) {
            val next = text.indexOf('$', from)
            if (next < 0) return -1
            var closing = 0
            while (next + closing < text.length && text[next + closing] == '$') closing++
            if (closing == run && !isEscaped(text, next) && next > open + run) return next
            from = next + closing
        }
    }

    // ---- 内容整理 ----

    /**
     * 块级公式内容：去掉空行（解析器遇到 `$$` 之间的空行会放弃整块），
     * 把 `align`、`equation` 这类顶层环境换成 `$$` 里能渲染的写法。
     */
    private fun fixBlock(body: String): String {
        val collapsed = BLANK_LINE_RUN.replace(body, "\n")
        return rewriteEnvironments(collapsed)
    }

    /** 需要放进 `$$` 才能渲染的内容：换行符或环境（行内渲染不支持）。 */
    private fun needsDisplay(body: String): Boolean =
        body.contains("\\\\") || "\n" in body || body.contains("\\begin{")

    /** 拼成一行：块级公式里的换行只影响源码可读性，换成空格更不容易把段落结构弄坏。 */
    private fun oneLine(body: String): String =
        LINE_BREAK.replace(fixBlock(body), " ").trim()

    /**
     * 把顶层环境名换成 `$$` 里合法的等价写法：`align`/`gather` 在 `$$` 里直接写会按编号多行排版，
     * `equation` 只是包一层。内部环境（`aligned`、`cases`、`array`…）不在表里，原样保留。
     */
    private fun rewriteEnvironments(body: String): String {
        if (!body.contains("\\begin{")) return body
        return ENVIRONMENT.replace(body) { match ->
            val mapped = DISPLAY_ENVIRONMENTS[match.groupValues[2]] ?: return@replace match.value
            if (mapped.isEmpty()) "" else "\\${match.groupValues[1]}{$mapped}"
        }
    }

    /** 找到与 [start] 处 `\begin{env}` 配对的 `\end{env}` 之后的位置，没写完返回 -1。 */
    private fun environmentEnd(text: String, start: Int, env: String): Int {
        val open = "\\begin{$env}"
        val close = "\\end{$env}"
        var depth = 1
        var from = start + open.length
        while (true) {
            val nextOpen = text.indexOf(open, from)
            val nextClose = text.indexOf(close, from)
            if (nextClose < 0) return -1
            if (nextOpen in 0 until nextClose) {
                depth++
                from = nextOpen + open.length
                continue
            }
            depth--
            if (depth == 0) return nextClose + close.length
            from = nextClose + close.length
        }
    }

    // ---- 代码区：整段复制，绝不改写 ----

    /** [i] 处的代码围栏，返回到围栏结束（含收尾行）的位置，不是围栏返回 -1。 */
    private fun fenceEnd(text: String, i: Int): Int {
        val marker = text[i]
        var lineStart = i
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var indent = 0
        while (lineStart + indent < i && text[lineStart + indent] == ' ') indent++
        // 围栏只能出现在行首（最多三个空格缩进）
        if (indent > 3 || lineStart + indent != i) return -1
        var run = 0
        while (i + run < text.length && text[i + run] == marker) run++
        if (run < 3) return -1
        var line = i
        while (true) {
            val end = text.indexOf('\n', line)
            if (end < 0) return text.length
            val after = end + 1
            if (isClosingFence(text, after, marker, run)) {
                val closeEnd = text.indexOf('\n', after)
                return if (closeEnd < 0) text.length else closeEnd + 1
            }
            line = after
        }
    }

    private fun isClosingFence(text: String, lineStart: Int, marker: Char, minRun: Int): Boolean {
        var k = lineStart
        var indent = 0
        while (k < text.length && text[k] == ' ' && indent <= 3) {
            k++
            indent++
        }
        if (indent > 3) return false
        var run = 0
        while (k + run < text.length && text[k + run] == marker) run++
        if (run < minRun) return false
        var j = k + run
        while (j < text.length && text[j] != '\n') {
            if (text[j] != ' ' && text[j] != '\t' && text[j] != '\r') return false
            j++
        }
        return true
    }

    /**
     * [i] 处（行首、至少四个空格缩进）的缩进代码块结束位置，不是代码块时返回 [i]。
     * 上一行不是空行时不算：那种情况是段落的续行，里面可能有公式。
     */
    private fun indentedCodeEnd(text: String, i: Int): Int {
        if (i > 0 && !isPreviousLineBlank(text, i)) return i
        var spaces = 0
        while (i + spaces < text.length && text[i + spaces] == ' ') spaces++
        if (spaces < 4) return i
        var lineStart = i
        var end = i
        while (lineStart < text.length) {
            var indent = 0
            while (lineStart + indent < text.length && text[lineStart + indent] == ' ') indent++
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline + 1
            val contentEnd = if (newline < 0) text.length else newline
            val blank = text.substring(minOf(lineStart + indent, contentEnd), contentEnd).isBlank()
            if (indent < 4 && !blank) break
            end = lineEnd
            lineStart = lineEnd
        }
        return end
    }

    private fun isPreviousLineBlank(text: String, lineStart: Int): Boolean {
        val previousEnd = lineStart - 1
        if (previousEnd <= 0) return true
        val previousStart = text.lastIndexOf('\n', previousEnd - 1) + 1
        return text.substring(previousStart, previousEnd).isBlank()
    }

    /** [i] 处的行内代码，返回到闭合反引号之后的位置，没有闭合返回 -1。 */
    private fun codeSpanEnd(text: String, i: Int): Int {
        var run = 0
        while (i + run < text.length && text[i + run] == '`') run++
        var from = i + run
        while (true) {
            val next = text.indexOf('`', from)
            if (next < 0) return -1
            var closing = 0
            while (next + closing < text.length && text[next + closing] == '`') closing++
            if (closing == run) return next + closing
            from = next + closing
        }
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var k = index - 1
        while (k >= 0 && text[k] == '\\') {
            backslashes++
            k--
        }
        return backslashes % 2 == 1
    }

    /**
     * 顶层环境名 → 放进 `$$` 里能渲染的等价写法；空字符串表示环境本身直接去掉。
     * 只收 `$$` 里直接写会排版出错的环境，`aligned`/`cases`/`array` 这些保持原样。
     */
    private val DISPLAY_ENVIRONMENTS: Map<String, String> = mapOf(
        "displaymath" to "",
        "math" to "",
        "equation" to "",
        "equation*" to "",
        "align" to "aligned",
        "align*" to "aligned",
        "flalign" to "aligned",
        "flalign*" to "aligned",
        "eqnarray" to "aligned",
        "eqnarray*" to "aligned",
        "alignat" to "alignedat",
        "alignat*" to "alignedat",
        "gather" to "gathered",
        "gather*" to "gathered",
        "multline" to "gathered",
        "multline*" to "gathered",
    )

    private val ENVIRONMENT = Regex("""\\(begin|end)\{([A-Za-z]+\*?)\}""")
    private val BLANK_LINE_RUN = Regex("""[ \t]*\n[ \t]*(?:\n[ \t]*)+""")
    private val LINE_BREAK = Regex("""[ \t]*\r?\n[ \t]*""")
}
