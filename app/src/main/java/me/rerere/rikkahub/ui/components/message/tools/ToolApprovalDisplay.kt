package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 审批弹窗要显示什么：精简的工具名 + 脱敏后的关键参数。
 *
 * 之前待审批卡片把整段 JSON 原样贴出来：外部 MCP 的大参数会刷满一屏，里面的
 * token / 密码 / 私钥也直接露在屏幕上（截图、旁边的人都能看到）。这里做两件事：
 * 1. 只挑关键参数（命令、路径、包名、文本…）优先排在前面，其余按原来的顺序跟在后面；
 * 2. 键名或值看起来像凭据的一律打码，值本身也按长度截断。
 *
 * 纯函数，不依赖 Compose / Android，方便单测。
 */
object ToolApprovalDisplay {

    /** 单条参数值最多显示多少个字符（超出用 … 收尾）。 */
    const val MAX_VALUE_CHARS: Int = 200

    /** 最多显示多少条参数；超出的用一行"还有 N 项"概括，避免一屏塞满。 */
    const val MAX_PARAMS: Int = 12

    /** 打码后的占位。 */
    const val REDACTED: String = "\u2022\u2022\u2022\u2022\u2022\u2022"

    /**
     * 参数键名命中这些片段就认为整条是敏感值（不区分大小写）。
     * 用"包含"而不是精确匹配：`api_key` / `x-api-key` / `accessToken` 都要覆盖。
     */
    private val SENSITIVE_KEY_HINTS = listOf(
        "password", "passwd", "secret", "token", "api_key", "apikey", "api-key",
        "credential", "authorization", "auth", "cookie", "session", "private_key", "privatekey",
        "access_key", "accesskey", "signature", "pin", "otp", "seed", "mnemonic",
    )

    /** 参数值命中这些模式就整值打码（私钥块、Bearer、sk- 前缀等）。 */
    private val SENSITIVE_VALUE_PATTERNS = listOf(
        "-----begin",
        "bearer ",
        "sk-",
        "ghp_",
        "github_pat_",
        "xoxb-",
    )

    /** 优先展示的关键参数，按重要性排序；第一个出现的排最前。 */
    private val KEY_PARAMS = listOf(
        "command", "cmd", "script", "path", "file", "package", "app", "package_name",
        "url", "query", "name", "tool", "text", "content", "old_string", "new_string",
        "snapshot_id", "node_id", "cwd", "timeout",
    )

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_KEY_HINTS.any { lower.contains(it) }
    }

    fun isSensitiveValue(value: String): Boolean {
        if (value.isEmpty()) return false
        if (SENSITIVE_VALUE_PATTERNS.any { value.contains(it, ignoreCase = true) }) return true
        // 很长且不含空白的串通常是 key/token/哈希，不当明文展示
        return value.length >= 40 && value.none { it.isWhitespace() }
    }

    /** 单个值：脱敏 + 截断 + 换行缩进。 */
    fun displayValue(key: String, value: String): String {
        if (isSensitiveKey(key) || isSensitiveValue(value)) return REDACTED
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "\"\""
        val clipped = if (trimmed.length > MAX_VALUE_CHARS) {
            trimmed.take(MAX_VALUE_CHARS) + "\u2026"
        } else {
            trimmed
        }
        return clipped.replace("\r\n", "\n").replace("\n", "\n  ")
    }

    /** 把 JSON 参数转成若干行 `key: value`，敏感项打码，关键项优先。 */
    fun summarize(arguments: JsonObject, maxParams: Int = MAX_PARAMS): List<String> {
        if (arguments.isEmpty()) return emptyList()
        val entries = arguments.entries.toList()
        val (keyed, rest) = entries.partition { it.key.lowercase() in KEY_PARAMS }
        val ordered = keyed.sortedBy { e -> KEY_PARAMS.indexOf(e.key.lowercase()) } +
            rest.sortedBy { it.key }
        val shown = ordered.take(maxParams)
        val lines = shown.map { (key, value) -> "$key: ${render(key, value)}" }.toMutableList()
        if (ordered.size > shown.size) {
            lines += "\u2026 +${ordered.size - shown.size} more"
        }
        return lines
    }

    private fun render(key: String, value: JsonElement): String = when (value) {
        is JsonPrimitive -> displayValue(key, value.content)
        is JsonArray -> if (isSensitiveKey(key)) REDACTED else "[${value.size} items]"
        is JsonObject -> if (isSensitiveKey(key)) REDACTED else "{...}"
        else -> REDACTED
    }

    /**
     * 审批卡片正文：第一行是精简工具名，后面是脱敏参数。
     * 工具名本身按"最多 60 字符"截断，避免超长 MCP 名撑爆标题。
     */
    fun previewLines(toolName: String, arguments: JsonObject): List<String> {
        val name = toolName.trim().ifEmpty { "(unknown tool)" }
        val shortName = if (name.length > 60) name.take(60) + "\u2026" else name
        return buildList {
            add(shortName)
            addAll(summarize(arguments))
        }
    }

    fun previewText(toolName: String, arguments: JsonObject): String =
        previewLines(toolName, arguments).joinToString("\n")

    /**
     * tool_batch 的成员摘要：列出"批准后会执行什么"，每条一行，参数脱敏。
     * 成员解析失败时用 "(unparsable)" 明确写出来，用户不至于盲签。
     */
    fun batchMemberLines(members: List<Pair<String, JsonObject>>): List<String> =
        members.mapIndexed { index, (name, args) ->
            val params = summarize(args, maxParams = 4).joinToString(", ")
            if (params.isEmpty()) "${index + 1}. $name" else "${index + 1}. $name ($params)"
        }
}
