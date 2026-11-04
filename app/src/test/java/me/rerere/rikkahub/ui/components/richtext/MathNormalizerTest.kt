package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.richtext.MathNormalizer.normalize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MathNormalizer] 的纯 JVM 单测：只做字符串处理，没有 Android 依赖，`testDebugUnitTest` 能直接跑。
 *
 * 重点是不该动的地方一动不动：金额、代码围栏、行内代码、`\$` 转义、已有公式。
 */
class MathNormalizerTest {

    // ---- 金额不能被当成公式 ----

    @Test
    fun `currency amounts are left alone`() {
        val inputs = listOf(
            "It costs \$20 in the US and \$5 in India, total \$25.",
            "price \$5 and \$10 for two",
            "range \$5-\$10 per unit",
            "按 \$20/\$5 的比例收费",
            "预算 \$1,000.50 左右",
            "\$ 20 \$ 前后有空格时不算公式",
        )
        for (input in inputs) assertEquals(input, normalize(input))
    }

    // ---- 已经能渲染的写法保持原样 ----

    @Test
    fun `existing math delimiters are untouched`() {
        val inputs = listOf(
            "inline \$x^2\$ ok",
            "inline \$ a + b \$ ok",
            "display \$\$E=mc^2\$\$ inline",
            "\\( a + b \\)",
            "\\[\na = b\n\\]",
            "\\begin{cases}1, & x>0\\\\0, & x\\le 0\\end{cases}",
        )
        for (input in inputs) assertEquals(input, normalize(input))
    }

    @Test
    fun `align inside bracket delimiters is normalized`() {
        assertEquals(
            "\\[\n\\begin{aligned}\na &= b\n\\end{aligned}\n\\]",
            normalize("\\[\n\\begin{align}\na &= b\n\\end{align}\n\\]"),
        )
    }

    @Test
    fun `display block keeps its layout but loses blank lines`() {
        assertEquals(
            "\$\$\nE = mc^2\nF = ma\n\$\$",
            normalize("\$\$\nE = mc^2\n\nF = ma\n\$\$"),
        )
    }

    // ---- 没有定界符的环境补上 \$\$ ----

    @Test
    fun `bare align becomes a display block`() {
        assertEquals(
            "\$\$\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}\$\$",
            normalize("\\begin{align}\na &= b \\\\\nc &= d\n\\end{align}"),
        )
    }

    @Test
    fun `bare equation loses the environment wrapper`() {
        assertEquals("\$\$E=mc^2\$\$", normalize("\\begin{equation}\nE=mc^2\n\\end{equation}"))
        assertEquals("\$\$E=mc^2\$\$", normalize("\\begin{equation*}\nE=mc^2\n\\end{equation*}"))
    }

    @Test
    fun `align inside an existing display block is normalized`() {
        assertEquals(
            "\$\$\n\\begin{aligned}\na &= b\n\\end{aligned}\n\$\$",
            normalize("\$\$\n\\begin{align}\na &= b\n\\end{align}\n\$\$"),
        )
    }

    @Test
    fun `gather and alignat map to their inner forms`() {
        assertEquals(
            "\$\$\\begin{gathered} a = 1 \\\\ b = 2 \\end{gathered}\$\$",
            normalize("\\begin{gather}\na = 1 \\\\\nb = 2\n\\end{gather}"),
        )
        assertEquals(
            "\$\$\\begin{alignedat}{2} a &= 1 \\end{alignedat}\$\$",
            normalize("\\begin{alignat}{2}\na &= 1\n\\end{alignat}"),
        )
    }

    // ---- 行内容器写成块级的形式 ----

    @Test
    fun `inline dollar with a row break is promoted to display`() {
        assertEquals("\$\$a \\\\ b\$\$", normalize("\$a \\\\ b\$"))
        assertEquals("\\[a \\\\ b\\]", normalize("\\(a \\\\ b\\)"))
    }

    // ---- 代码、表格、列表、引用一律不动 ----

    @Test
    fun `code is never rewritten`() {
        val doc = buildString {
            appendLine("```latex")
            appendLine("\\begin{align}")
            appendLine("a &= b")
            appendLine("\\end{align}")
            appendLine()
            appendLine("\$\$")
            appendLine()
            appendLine("\$x\$")
            appendLine("```")
            appendLine()
            appendLine("行内 `\$x\$` 和 `\\begin{align}` 也原样")
            appendLine()
            appendLine("    \\begin{equation}E=1\\end{equation}")
        }
        assertEquals(doc, normalize(doc))
    }

    @Test
    fun `tables lists and quotes survive untouched`() {
        val doc = buildString {
            appendLine("| 名称 | 价格 |")
            appendLine("| --- | --- |")
            appendLine("| 苹果 | \$5 |")
            appendLine()
            appendLine("- 第一项 \$10")
            appendLine("- 第二项 **加粗**")
            appendLine()
            appendLine("> 引用里的 \$20 也不算公式")
            appendLine()
            appendLine("1. 有序列表")
            appendLine("2. 还是列表")
        }
        assertEquals(doc, normalize(doc))
    }

    @Test
    fun `document with formulas keeps the rest of the structure`() {
        val doc = buildString {
            appendLine("| a | b |")
            appendLine("| - | - |")
            appendLine("| 1 | 2 |")
            appendLine()
            appendLine("\\begin{align}")
            appendLine("f(x) &= x^2")
            appendLine("\\end{align}")
            appendLine()
            appendLine("```kotlin")
            appendLine("val price = \$5")
            appendLine("```")
        }
        val expected = buildString {
            appendLine("| a | b |")
            appendLine("| - | - |")
            appendLine("| 1 | 2 |")
            appendLine()
            appendLine("\$\$\\begin{aligned} f(x) &= x^2 \\end{aligned}\$\$")
            appendLine()
            appendLine("```kotlin")
            appendLine("val price = \$5")
            appendLine("```")
        }
        assertEquals(expected, normalize(doc))
    }

    // ---- 流式输出：写到一半的内容保持原样 ----

    @Test
    fun `incomplete math is left as source`() {
        val inputs = listOf(
            "text \$\$\\frac{1}{2}",
            "text \\begin{align}\na &= b",
            "text `\$x\$",
            "text ```\n\$\$x",
            "a \$\$x\$ b",
            "\\(\nx = 1",
        )
        for (input in inputs) assertEquals(input, normalize(input))
    }

    // ---- 其它 ----

    @Test
    fun `escaped dollars stay literal`() {
        val inputs = listOf("costs \\\$5 and \\\$10", "\\\$x\$ 不是公式", "\\\\\$x\$ 美元符号")
        for (input in inputs) assertEquals(input, normalize(input))
    }

    @Test
    fun `normalizing twice changes nothing more`() {
        val doc = buildString {
            appendLine("价格 \$5 and \$10，公式：\\(a+b\\)")
            appendLine()
            appendLine("\\begin{align}")
            appendLine("a &= b \\\\")
            appendLine("c &= d")
            appendLine("\\end{align}")
            appendLine()
            appendLine("\$\$")
            appendLine("E = 1")
            appendLine()
            appendLine("F = 2")
            appendLine("\$\$")
            appendLine()
            appendLine("```")
            appendLine("\$x\$")
            appendLine("```")
        }
        val once = normalize(doc)
        assertEquals(once, normalize(once))
    }

    @Test
    fun `plain text without math is returned unchanged`() {
        val doc = "# 标题\n\n普通段落，没有公式。\n\n- 列表\n- 列表"
        assertEquals(doc, normalize(doc))
    }
}
