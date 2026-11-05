package me.rerere.rikkahub.ui.components.message.tools

import me.rerere.rikkahub.data.ai.tools.MCP_CALL_TOOL_NAME

/**
 * 流式工具入参的草稿解析。
 *
 * 背景：模型一次性生成 `workspace_write_file` / `workspace_edit_file` 的参数时，正文可能有几万字符，
 * 整个 JSON 到流结束才算完整。在此之前 `UIMessagePart.Tool.input` 只是一段**未闭合的 JSON 增量**
 * （`Tool.merge` 里做的是 `input + other.input`），没有任何现成解析器能吃它。
 *
 * 这里用一台可续扫的状态机把已经完整解码的字符串前缀抠出来，只服务于**展示 / 复制 / 导出草稿**：
 *
 * - 解出来的内容**绝不能当执行参数**。执行参数永远来自 JSON 完整后 [me.rerere.ai.ui.UIMessagePart.Tool.inputAsJson]。
 * - 未闭合字符串只保留已完整解码的前缀，不猜补残缺的 escape（`\`、`\u12`、落单的高位代理都留到下一块再判）。
 * - 只解码关心的键（path/text/new_text/old_text/content/name/arguments），其余嵌套值整段跳过；
 *   每次喂入只扫新增的字符，长流不会每帧做全量扫描。
 *
 * 纯 Kotlin、无 Compose / Android 依赖，可以直接单元测试。
 */

/** 草稿指向的目标工具。 */
enum class ToolInputDraftKind {
    WRITE_FILE,
    EDIT_FILE,

    /** 其它工具，或工具名还没流全：只给通用 raw 预览 */
    OTHER,
}

/** 入参 JSON 的流式状态。 */
enum class ToolInputStreamState {
    /** JSON 还没闭合（半截草稿） */
    INCOMPLETE,

    /** JSON 已闭合；此时应当交回正常渲染流程 */
    COMPLETE,
}

/**
 * 一次解析结果的快照。所有字符串都是"目前能可靠解码出来的前缀"。
 *
 * @param body 主内容前缀：写入取 `text`/`content`，编辑取 `new_text`
 * @param bodyKey 实际命中的键名，便于排查
 * @param previousBody 编辑工具的 `old_text` 前缀
 */
data class ToolInputDraft(
    val kind: ToolInputDraftKind,
    val rawToolName: String,
    val targetToolName: String,
    /** 工具名还没流全（例如网关 `name` 还没写完），只能给通用预览 */
    val nameIncomplete: Boolean,
    val path: String?,
    val body: String?,
    val bodyKey: String?,
    val previousBody: String?,
    /** 已收到的原始入参字符数（含 JSON 结构本身） */
    val receivedChars: Int,
    val streamState: ToolInputStreamState,
    /** 正文超过展示上限，解析时被截断（只影响草稿，不影响真实执行） */
    val bodyTruncated: Boolean,
) {
    /** 仍是半截草稿：UI 只在此时接管渲染 */
    val isDraft: Boolean get() = streamState == ToolInputStreamState.INCOMPLETE

    val bodyChars: Int get() = body?.length ?: 0

    val hasBody: Boolean get() = !body.isNullOrEmpty()
}

internal const val WRITE_FILE_TOOL_NAME = "workspace_write_file"
internal const val EDIT_FILE_TOOL_NAME = "workspace_edit_file"

private const val KEY_PATH = "path"
private const val KEY_TEXT = "text"
private const val KEY_CONTENT = "content"
private const val KEY_NEW_TEXT = "new_text"
private const val KEY_OLD_TEXT = "old_text"
private const val KEY_NAME = "name"
private const val KEY_ARGUMENTS = "arguments"

/**
 * 增量 JSON 草稿扫描器。
 *
 * 用法：反复 `feed(累积后的完整入参)`，它只处理 [consumed] 之后的新字符，状态全部保留在字段里，
 * 所以"字符串正解到一半"、"转义还没写完"这种情况可以跨 chunk 续上。
 */
internal class DraftJsonScanner {
    private enum class Phase { START, SEEK, KEY, COLON, VALUE, STRING, SKIP, DONE, BROKEN }

    private var phase = Phase.START

    /** 当前打开的容器层数；顶层对象内部为 1 */
    private var depth = 0

    /** 顶层对象已闭合 */
    var closed = false
        private set

    /** 结构坏掉（例如入参根本不是 JSON 对象），后续只保留已有结果 */
    var broken = false
        private set

    /** 已扫描到的字符数 */
    var consumed = 0
        private set

    private val keyBuffer = StringBuilder()
    private var keyDepth = 0
    private var keyEscaped = false
    private var keyUnicodeLeft = 0

    private var valueKey: String? = null
    private var valueBuffer: StringBuilder? = null
    private var valueCap = 0
    private var valueTruncated = false
    private var valueEscaped = false

    /** null = 不在 `\uXXXX` 里；非 null = 已收到的十六进制位 */
    private var valueUnicode: StringBuilder? = null

    /** 落单的高位代理：等下一个码元判断是成对还是残缺 */
    private var pendingHighSurrogate: Char? = null

    private var skipContainer = false
    private var skipInString = false
    private var skipEscaped = false
    private var valueStartDepth = 0

    private val values = LinkedHashMap<String, StringBuilder>()
    private val valueDepths = HashMap<String, Int>()
    private val completeKeys = HashSet<String>()
    private val truncatedKeys = HashSet<String>()

    fun reset() {
        phase = Phase.START
        depth = 0
        closed = false
        broken = false
        consumed = 0
        keyBuffer.setLength(0)
        keyDepth = 0
        keyEscaped = false
        keyUnicodeLeft = 0
        valueKey = null
        valueBuffer = null
        valueCap = 0
        valueTruncated = false
        valueEscaped = false
        valueUnicode = null
        pendingHighSurrogate = null
        skipContainer = false
        skipInString = false
        skipEscaped = false
        valueStartDepth = 0
        values.clear()
        valueDepths.clear()
        completeKeys.clear()
        truncatedKeys.clear()
    }

    /** 喂入累积后的完整入参，只处理新增部分 */
    fun feed(text: String) {
        if (text.length < consumed) reset()
        var index = consumed
        while (index < text.length) {
            step(text[index])
            index++
        }
        consumed = text.length
    }

    /** 目前能可靠解码出来的字符串前缀；不认识的键返回 null */
    fun value(key: String): String? = values[key]?.toString()

    fun valueLength(key: String): Int = values[key]?.length ?: 0

    fun isComplete(key: String): Boolean = key in completeKeys

    fun isTruncated(key: String): Boolean = key in truncatedKeys

    private fun step(c: Char) {
        when (phase) {
            Phase.START -> when {
                c.isJsonWhitespace() -> Unit
                c == '{' -> {
                    depth = 1
                    phase = Phase.SEEK
                }

                else -> {
                    broken = true
                    phase = Phase.BROKEN
                }
            }

            Phase.SEEK -> when {
                c.isJsonWhitespace() || c == ',' -> Unit
                c == '"' -> {
                    keyBuffer.setLength(0)
                    keyEscaped = false
                    keyUnicodeLeft = 0
                    phase = Phase.KEY
                }

                c == '}' || c == ']' -> closeContainer()
                else -> {
                    broken = true
                    phase = Phase.BROKEN
                }
            }

            Phase.KEY -> stepKey(c)
            Phase.COLON -> when {
                c.isJsonWhitespace() -> Unit
                c == ':' -> phase = Phase.VALUE
                else -> {
                    broken = true
                    phase = Phase.BROKEN
                }
            }

            Phase.VALUE -> stepValue(c)
            Phase.STRING -> stepValueString(c)
            Phase.SKIP -> stepSkip(c)
            Phase.DONE, Phase.BROKEN -> Unit
        }
    }

    private fun stepKey(c: Char) {
        if (keyUnicodeLeft > 0) {
            keyUnicodeLeft--
            return
        }
        if (keyEscaped) {
            keyEscaped = false
            if (c == 'u') keyUnicodeLeft = 4
            return
        }
        when {
            c == '\\' -> keyEscaped = true
            c == '"' -> {
                keyDepth = depth
                phase = Phase.COLON
            }

            keyBuffer.length < KEY_MAX_CHARS -> keyBuffer.append(c)
        }
    }

    private fun stepValue(c: Char) {
        if (c.isJsonWhitespace()) return
        when {
            c == '"' -> beginStringValue()

            c == '{' || c == '[' -> {
                // 只有网关包装的 arguments 是对象时才值得进去找字段；其余嵌套值整段跳过，
                // 否则带附件/元数据的长入参每帧都要全量解析
                if (c == '{' && keyDepth == 1 && keyBuffer.toString() == KEY_ARGUMENTS) {
                    depth++
                    phase = Phase.SEEK
                } else {
                    valueStartDepth = depth
                    depth++
                    skipContainer = true
                    skipInString = false
                    skipEscaped = false
                    phase = Phase.SKIP
                }
            }

            c == '}' || c == ']' -> closeContainer()

            else -> {
                // 数字 / true / false / null：整段跳过
                valueStartDepth = depth
                skipContainer = false
                skipInString = false
                skipEscaped = false
                phase = Phase.SKIP
            }
        }
    }

    private fun beginStringValue() {
        val key = keyBuffer.toString()
        val atDepth = keyDepth
        val previousDepth = valueDepths[key]
        val capture = key in TRACKED_KEYS && (previousDepth == null || atDepth <= previousDepth)
        valueKey = key
        if (capture) {
            valueDepths[key] = atDepth
            val buffer = StringBuilder()
            values[key] = buffer
            completeKeys.remove(key)
            truncatedKeys.remove(key)
            valueBuffer = buffer
            valueCap = capacityOf(key)
            valueTruncated = false
        } else {
            valueBuffer = null
            valueCap = 0
            valueTruncated = false
        }
        valueEscaped = false
        valueUnicode = null
        pendingHighSurrogate = null
        phase = Phase.STRING
    }

    private fun stepValueString(c: Char) {
        val unicode = valueUnicode
        if (unicode != null) {
            if (c.isHexDigit()) {
                unicode.append(c)
                if (unicode.length == 4) {
                    valueUnicode = null
                    emitUnit(unicode.toString().toIntOrNull(16) ?: 0xFFFD)
                }
                return
            }
            // 残缺的 \u：不猜补，丢掉这个转义继续按普通字符处理
            valueUnicode = null
        }
        if (valueEscaped) {
            valueEscaped = false
            when (c) {
                '"' -> emitUnit('"'.code)
                '\\' -> emitUnit('\\'.code)
                '/' -> emitUnit('/'.code)
                'b' -> emitUnit('\b'.code)
                'f' -> emitUnit(0x0C)
                'n' -> emitUnit('\n'.code)
                'r' -> emitUnit('\r'.code)
                't' -> emitUnit('\t'.code)
                'u' -> valueUnicode = StringBuilder(4)
                // 非法转义：按字面保留，不丢字符也不猜
                else -> emitUnit(c.code)
            }
            return
        }
        when (c) {
            '\\' -> valueEscaped = true
            '"' -> endStringValue()
            else -> emitUnit(c.code)
        }
    }

    private fun endStringValue() {
        val key = valueKey
        if (valueBuffer != null && key != null) {
            if (pendingHighSurrogate != null) {
                // 字符串已闭合，落单的高位代理不可能再配对
                pushUnit(REPLACEMENT_CHAR)
                pendingHighSurrogate = null
            }
            if (valueTruncated) truncatedKeys.add(key)
            completeKeys.add(key)
        }
        valueBuffer = null
        valueKey = null
        valueEscaped = false
        valueUnicode = null
        phase = Phase.SEEK
    }

    /**
     * 写入一个 UTF-16 码元，顺带把代理对拼好：高位代理先挂起，等到下一个码元再决定
     * 是拼成一对、还是自己残缺（补 U+FFFD 后继续）。
     */
    private fun emitUnit(code: Int) {
        if (valueBuffer == null) return
        val pending = pendingHighSurrogate
        if (pending != null) {
            pendingHighSurrogate = null
            if (code in 0xDC00..0xDFFF) {
                pushUnit(pending)
                pushUnit(code.toChar())
            } else {
                pushUnit(REPLACEMENT_CHAR)
                if (code in 0xD800..0xDBFF) pendingHighSurrogate = code.toChar() else pushUnit(code.toChar())
            }
        } else if (code in 0xD800..0xDBFF) {
            pendingHighSurrogate = code.toChar()
        } else {
            pushUnit(code.toChar())
        }
    }

    private fun pushUnit(unit: Char) {
        val buffer = valueBuffer ?: return
        if (buffer.length >= valueCap) {
            valueTruncated = true
            // 立刻记到 key 上：草稿在字符串还没闭合时就要能提示"已截断"
            valueKey?.let { truncatedKeys.add(it) }
            return
        }
        buffer.append(unit)
    }

    private fun stepSkip(c: Char) {
        if (skipInString) {
            when {
                skipEscaped -> skipEscaped = false
                c == '\\' -> skipEscaped = true
                c == '"' -> {
                    skipInString = false
                    if (!skipContainer) phase = Phase.SEEK
                }
            }
            return
        }
        when (c) {
            '"' -> skipInString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (skipContainer) {
                    if (depth <= valueStartDepth) {
                        skipContainer = false
                        phase = Phase.SEEK
                    }
                } else if (depth <= 0) {
                    closed = true
                    phase = Phase.DONE
                } else {
                    phase = Phase.SEEK
                }
            }

            ',' -> if (!skipContainer) phase = Phase.SEEK
        }
    }

    private fun closeContainer() {
        depth--
        if (depth <= 0) {
            closed = true
            phase = Phase.DONE
        } else {
            phase = Phase.SEEK
        }
    }

    private fun capacityOf(key: String): Int = when (key) {
        KEY_PATH -> PATH_MAX_CHARS
        KEY_ARGUMENTS -> ARGUMENTS_MAX_CHARS
        else -> BODY_MAX_CHARS
    }

    private companion object {
        const val KEY_MAX_CHARS = 64
        const val PATH_MAX_CHARS = 512
        const val BODY_MAX_CHARS = 200_000
        const val ARGUMENTS_MAX_CHARS = 400_000
        const val REPLACEMENT_CHAR = '\uFFFD'

        val TRACKED_KEYS = setOf(
            KEY_PATH,
            KEY_TEXT,
            KEY_CONTENT,
            KEY_NEW_TEXT,
            KEY_OLD_TEXT,
            KEY_NAME,
            KEY_ARGUMENTS,
            "code", "script", "command",
        )
    }
}

/**
 * 增量草稿跟踪器：UI 每次重组把最新的 `toolName` + `input` 交给 [draft]，
 * 内部只在入参变长时扫新增字符，其余情况直接返回上一次的结果。
 *
 * 一个实例只服务一次工具调用（在 Composable 里 `remember(toolCallId)`），
 * 会话切换 / 重新生成导致入参不是"追加"时会自动整体重扫。
 */
class ToolInputDraftTracker {
    private var rawToolName: String = ""
    private var rawText: String = ""
    private var cached: ToolInputDraft? = null
    private val scanner = DraftJsonScanner()

    // 网关把 arguments 当字符串传时（部分 MCP 网关就是这么吐的），再套一台子扫描器
    private val argumentsScanner = DraftJsonScanner()
    private var argumentsText: String = ""

    fun draft(rawToolName: String, raw: String): ToolInputDraft? {
        if (rawToolName == this.rawToolName && raw === this.rawText) return cached
        if (!isAppendOf(rawText, raw)) {
            scanner.reset()
            argumentsScanner.reset()
            argumentsText = ""
            rawText = ""
        }
        this.rawToolName = rawToolName
        this.rawText = raw
        scanner.feed(raw)

        val argumentsLength = scanner.valueLength(KEY_ARGUMENTS)
        if (argumentsLength != argumentsText.length) {
            val text = scanner.value(KEY_ARGUMENTS).orEmpty()
            if (!isAppendOf(argumentsText, text)) argumentsScanner.reset()
            argumentsText = text
            argumentsScanner.feed(text)
        }

        val draft = buildToolInputDraft(
            rawToolName = rawToolName,
            raw = raw,
            outer = scanner,
            inner = if (argumentsText.isEmpty()) null else argumentsScanner,
        )
        cached = draft
        return draft
    }
}

/**
 * 一次性解析（测试与无状态场景）。
 *
 * @return 需要专门展示的草稿；与本次草稿无关的工具返回 null
 */
fun parseToolInputDraft(rawToolName: String, raw: String): ToolInputDraft? =
    ToolInputDraftTracker().draft(rawToolName, raw)

internal fun buildToolInputDraft(
    rawToolName: String,
    raw: String,
    outer: DraftJsonScanner,
    inner: DraftJsonScanner?,
): ToolInputDraft? {
    if (raw.isBlank()) return null

    val gateway = rawToolName.isGatewayCall()
    val declaredName = if (gateway) outer.value(KEY_NAME).orEmpty() else rawToolName
    val nameIncomplete = if (gateway) {
        !outer.isComplete(KEY_NAME) || declaredName.isProperPrefixOfKnownTool()
    } else {
        declaredName.isProperPrefixOfKnownTool()
    }
    val kind = when (declaredName.substringAfterLast("__").substringAfterLast('.')) {
        WRITE_FILE_TOOL_NAME -> ToolInputDraftKind.WRITE_FILE
        EDIT_FILE_TOOL_NAME -> ToolInputDraftKind.EDIT_FILE
        else -> ToolInputDraftKind.OTHER
    }

    fun lookup(key: String): String? = inner?.value(key) ?: outer.value(key)

    val bodyKey = when (kind) {
        ToolInputDraftKind.WRITE_FILE -> listOf(KEY_TEXT, KEY_CONTENT).firstOrNull { lookup(it) != null }
        ToolInputDraftKind.EDIT_FILE -> listOf(KEY_NEW_TEXT, KEY_TEXT, KEY_CONTENT).firstOrNull { lookup(it) != null }
        ToolInputDraftKind.OTHER -> listOf("code", "script", "command").firstOrNull { lookup(it) != null }
    }
    val previousKey = if (kind == ToolInputDraftKind.EDIT_FILE && lookup(KEY_OLD_TEXT) != null) KEY_OLD_TEXT else null
    val bodyTruncated = listOfNotNull(bodyKey, previousKey).any { key ->
        (inner != null && inner.value(key) != null && inner.isTruncated(key)) || outer.isTruncated(key)
    }

    val draft = ToolInputDraft(
        kind = kind,
        rawToolName = rawToolName,
        targetToolName = declaredName,
        nameIncomplete = nameIncomplete,
        path = lookup(KEY_PATH)?.takeIf { it.isNotEmpty() },
        body = bodyKey?.let { lookup(it) },
        bodyKey = bodyKey,
        previousBody = previousKey?.let { lookup(it) },
        receivedChars = raw.length,
        streamState = if (outer.closed) ToolInputStreamState.COMPLETE else ToolInputStreamState.INCOMPLETE,
        bodyTruncated = bodyTruncated,
    )

    // 其它工具、且名字已经明确，就交回原有渲染流程；只有"名字还没流全"才退化成通用 raw 预览
    if (draft.kind == ToolInputDraftKind.OTHER && !draft.nameIncomplete && !draft.isDraft) return null
    return draft
}

/** `tool_call`，以及撞车后退化出来的 `tool_call_2`。 */
private fun String.isGatewayCall(): Boolean =
    this == MCP_CALL_TOOL_NAME || startsWith("${MCP_CALL_TOOL_NAME}_")

/** 名字是已知工具名的真前缀（还没流全）。 */
private fun String.isProperPrefixOfKnownTool(): Boolean {
    if (isEmpty()) return true
    return (length < WRITE_FILE_TOOL_NAME.length && WRITE_FILE_TOOL_NAME.startsWith(this)) ||
        (length < EDIT_FILE_TOOL_NAME.length && EDIT_FILE_TOOL_NAME.startsWith(this))
}

/** Verify the whole prefix: a same-length edit or rewritten middle must reset the scanner.
 * A tail-window heuristic can silently show obsolete code after a regeneration.
 */
private fun isAppendOf(previous: String, next: String): Boolean =
    previous.isEmpty() || (next.length >= previous.length && next.startsWith(previous))

/** Bounded display must never silently turn into a truncated exported draft. */
internal fun draftCopyOrExportText(draft: ToolInputDraft, raw: String): String =
    if (draft.bodyTruncated) raw else draft.body?.takeIf { it.isNotEmpty() } ?: raw

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * 取尾部若干行，用于流式摘要（只展示最近写入的内容）。
 *
 * @param maxChars 硬上限，避免一行几万字符的压缩产物把摘要撑爆
 */
internal fun String.draftTailLines(maxLines: Int, maxChars: Int = 1_200): String {
    if (isEmpty() || maxLines <= 0) return ""
    var start = 0
    var lines = 0
    var index = length - 1
    // 反向找换行要有预算：单行几十万字符（压缩过的产物、base64）不能每帧从尾扫到头
    val scanFloor = maxOf(0, length - maxLines * TAIL_LINE_SCAN_CHARS)
    while (index >= scanFloor) {
        if (this[index] == '\n') {
            lines++
            if (lines >= maxLines) {
                start = index + 1
                break
            }
        }
        index--
    }
    if (length - start > maxChars) start = length - maxChars
    // 不要从半个代理对开始截
    if (start in 1 until length && this[start].isLowSurrogate() && this[start - 1].isHighSurrogate()) start++
    return substring(start)
}

/** 反向找尾部行时最多回看的字符数（每行预算） */
private const val TAIL_LINE_SCAN_CHARS = 512
