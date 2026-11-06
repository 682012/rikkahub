package me.rerere.rikkahub.data.ai.execution

import me.rerere.ai.core.Tool

/**
 * 「高效执行」的短指引。
 *
 * 只做一件事：按**这一轮真正拿到的工具**挑几句怎么用的话，写进系统提示的一个小节。
 *
 * 边界（别越界）：
 * - 不裁工具、不改工具定义、不动 `needsApproval`/权限，只是把已有的入口写清楚；
 * - 不承诺「最快/最优/一定成功」，也不编造没接上的能力；
 * - 没有工作区/代码类工具时返回空串：普通聊天不该被塞一套代码流程；
 * - 纯函数、无 IO、无 Android 依赖，输出在一轮内恒定（放系统提示，动态时钟仍留在请求尾部）。
 */
object ExecutionGuidance {
    /** 系统提示里这一节的名字；也用于测试断言。 */
    const val SECTION_TITLE: String = "执行方式"

    /** 独立的只读检索/读取入口：这些先用来定位，再决定读哪一段。 */
    private val LOCATOR_TOOLS = listOf("workspace_glob", "workspace_grep", "workspace_read_file")

    /** 只读查询类入口：相互独立时可以一起发。 */
    private val READ_ONLY_HINTS = setOf(
        "workspace_read_file", "workspace_grep", "workspace_glob",
        "knowledge_search", "knowledge_read", "web_search", "conversation_search",
    )

    private const val MAX_CHARS = 700

    /**
     * 给本轮的工具体系生成指引；没有可用工具、或只有纯聊天能力时返回 `""`（什么都不加）。
     */
    fun forTools(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        val names = tools.mapTo(HashSet(tools.size)) { it.name }
        val lines = ArrayList<String>(5)

        val locators = LOCATOR_TOOLS.filter { it in names }
        if (locators.isNotEmpty()) {
            lines += "定位先于通读：用 ${locators.joinToString(" / ")} 找到确切位置，只读需要的那段，别整篇重读。"
        }

        val writers = listOf("workspace_edit_file", "workspace_write_file").filter { it in names }
        if (writers.isNotEmpty()) {
            lines += "改文件只替换目标片段（${writers.joinToString(" / ")}）；同一个文件的多次写入按顺序做，不要并发。"
        }

        if (READ_ONLY_HINTS.count { it in names } >= 2) {
            lines += "相互独立的读取/检索可以一起发起；有依赖的（先读后改）等结果回来再动手。"
        }

        if ("tool_batch" in names) {
            lines += "已经计划好的多个独立调用用一次 tool_batch 发出去，不用一个个串着等。"
        }

        if (names.any { it.startsWith("workspace_shell") }) {
            lines += "验证只跑和这次改动相关的最小检查；没实际跑过就直接说没跑，别写成已经验证。"
        }

        if (lines.isEmpty()) return ""
        return lines.joinToString("\n").take(MAX_CHARS)
    }
}
