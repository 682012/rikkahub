package me.rerere.rikkahub.ui.components.message

/**
 * R8 长消息正文折叠的纯判定逻辑。
 *
 * 这里只做"要不要折、折了要不要给按钮"的决定，不碰 Compose，方便直接单测。
 * 真正的折叠是**显示层裁切**：正文照旧整段交给 Markdown 渲染，布局测量的是全文，
 * 只是把可见高度裁到一屏左右（见 CollapsibleMessageText.kt 的 clipToMaxHeight）。
 * 所以：
 * - 不做字符串截断，不会把没写完的 Markdown / 代码块 / 宽表格切成半截语法；
 * - 选择、复制、"选择并复制"拿到的仍是全文；
 * - 代价是折叠态依然要完整测量并组合这段正文（不是 lazy），只是绘制被裁掉。
 */

internal object MessageCollapseThresholds {
    /** 字符数达到这个量，一律认为是长消息 */
    const val MIN_CHARS: Int = 700

    /** 行数达到这个量、且是多段，也算长消息（短句换行的碎片不算） */
    const val MIN_LINES: Int = 12
    const val MIN_PARAGRAPHS: Int = 2

    /** 走"多行"规则时的兜底字符数，避免 12 行短句列表被折 */
    const val MIN_CHARS_FOR_LINE_RULE: Int = 240

    /** DisplaySetting.collapseLongMessages 缺省值（字段没落地时也按这个走） */
    const val DEFAULT_ENABLED: Boolean = true
}

/** 一段消息正文（可能由多个 Text part 拼成）的静态特征 */
internal data class MessageTextMetrics(
    val charCount: Int = 0,
    val nonWhitespaceCharCount: Int = 0,
    val lineCount: Int = 0,
    val paragraphCount: Int = 0,
    val partCount: Int = 0,
    val hasUnclosedCodeFence: Boolean = false,
    val hasTable: Boolean = false,
) {
    val isEmpty: Boolean get() = nonWhitespaceCharCount == 0
}

/** 折叠状态的原因，方便测试和排查，也可用于无障碍描述 */
internal enum class MessageCollapseReason {
    /** 用户关闭了 collapseLongMessages，保持旧行为 */
    DISABLED,

    /** 没有正文（纯图片/文件/工具） */
    NO_TEXT,

    /** 跳转/查找要求定位，先展开 */
    FORCED_OPEN,

    /** 用户手动展开过，以后一直保持 */
    MANUAL_OPEN,

    /** 用户手动收起过，以后一直保持 */
    MANUAL_COLLAPSED,

    /** 正在生成：不折，避免每个 chunk 折一次抖一下 */
    STREAMING,

    /** 不够长 */
    SHORT,

    /** 默认折叠 */
    AUTO_COLLAPSED,
}

internal data class MessageCollapseInput(
    val messageId: String = "",
    /** 参与折叠的正文，按 part 顺序；多 part 时合并判定 */
    val textParts: List<String> = emptyList(),
    val isAssistant: Boolean = true,
    /** 这条消息还在流式生成 */
    val streaming: Boolean = false,
    /** DisplaySetting.collapseLongMessages */
    val enabled: Boolean = MessageCollapseThresholds.DEFAULT_ENABLED,
    /** 本次会话里用户手动展开过这条消息 */
    val manualOpen: Boolean = false,
    /** 本次会话里用户手动收起过这条消息 */
    val manualCollapsed: Boolean = false,
    /** 跳转锚点/搜索结果要求展开 */
    val anchorRequested: Boolean = false,
)

internal data class MessageCollapseDecision(
    /** 默认折叠（不是"用户折起来了"） */
    val collapsed: Boolean = false,
    /** 是否显示展开/收起按钮 */
    val showToggle: Boolean = false,
    val reason: MessageCollapseReason = MessageCollapseReason.SHORT,
    val metrics: MessageTextMetrics = MessageTextMetrics(),
    /** 生成结束时应该自动折起来（流式期间先不折） */
    val collapseWhenComplete: Boolean = false,
) {
    val expanded: Boolean get() = !collapsed
}

/**
 * 判定一条消息的正文折叠状态。
 *
 * 顺序很重要：
 * 1. 关掉选项 → 完全旧行为；
 * 2. 没正文 → 不处理；
 * 3. 跳转/查找锚点 → 先展开（至少别让用户滚到一块被裁掉的正文上）；
 * 4. 手动展开 / 手动收起 → 稳定保持，流式期间也不抖；
 * 5. 流式生成中 → 不折（等生成完再判）；
 * 6. 够长 → 默认折叠。
 */
internal fun decideMessageCollapse(input: MessageCollapseInput): MessageCollapseDecision {
    val metrics = measureMessageText(input.textParts)
    if (!input.enabled) {
        return MessageCollapseDecision(
            collapsed = false,
            showToggle = false,
            reason = MessageCollapseReason.DISABLED,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    if (metrics.isEmpty) {
        return MessageCollapseDecision(
            collapsed = false,
            showToggle = false,
            reason = MessageCollapseReason.NO_TEXT,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    val longEnough = isLongMessage(metrics)
    if (input.anchorRequested) {
        return MessageCollapseDecision(
            collapsed = false,
            showToggle = longEnough,
            reason = MessageCollapseReason.FORCED_OPEN,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    if (input.manualOpen) {
        return MessageCollapseDecision(
            collapsed = false,
            // 正文被替换变短之后不该再挂一个"收起全文"
            showToggle = longEnough,
            reason = MessageCollapseReason.MANUAL_OPEN,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    if (input.manualCollapsed && longEnough) {
        return MessageCollapseDecision(
            collapsed = true,
            showToggle = true,
            reason = MessageCollapseReason.MANUAL_COLLAPSED,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    if (input.streaming) {
        return MessageCollapseDecision(
            collapsed = false,
            showToggle = false,
            reason = MessageCollapseReason.STREAMING,
            metrics = metrics,
            collapseWhenComplete = longEnough,
        )
    }
    if (!longEnough) {
        return MessageCollapseDecision(
            collapsed = false,
            showToggle = false,
            reason = MessageCollapseReason.SHORT,
            metrics = metrics,
            collapseWhenComplete = false,
        )
    }
    return MessageCollapseDecision(
        collapsed = true,
        showToggle = true,
        reason = MessageCollapseReason.AUTO_COLLAPSED,
        metrics = metrics,
        collapseWhenComplete = false,
    )
}

/** 是否够长到值得折叠：700 字以上，或者 12 行以上且多段 */
internal fun isLongMessage(metrics: MessageTextMetrics): Boolean {
    if (metrics.nonWhitespaceCharCount >= MessageCollapseThresholds.MIN_CHARS) return true
    return metrics.lineCount >= MessageCollapseThresholds.MIN_LINES &&
        metrics.paragraphCount >= MessageCollapseThresholds.MIN_PARAGRAPHS &&
        metrics.nonWhitespaceCharCount >= MessageCollapseThresholds.MIN_CHARS_FOR_LINE_RULE
}

/** 统计正文特征。只数不计内容，不做任何截断。 */
internal fun measureMessageText(textParts: List<String>): MessageTextMetrics {
    var chars = 0
    var nonWhitespace = 0
    var lines = 0
    var paragraphs = 0
    var parts = 0
    var fences = 0
    var table = false
    textParts.forEach { text ->
        if (text.isBlank()) return@forEach
        parts++
        chars += text.length
        nonWhitespace += text.count { !it.isWhitespace() }
        val partLines = text.split('\n')
        var paragraphHasText = false
        partLines.forEach { line ->
            if (line.isNotBlank()) {
                lines++
                paragraphHasText = true
            } else if (paragraphHasText) {
                paragraphs++
                paragraphHasText = false
            }
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) fences++
            if (!table && isTableRow(trimmed)) table = true
        }
        if (paragraphHasText) paragraphs++
    }
    return MessageTextMetrics(
        charCount = chars,
        nonWhitespaceCharCount = nonWhitespace,
        lineCount = lines,
        paragraphCount = paragraphs,
        partCount = parts,
        hasUnclosedCodeFence = fences % 2 == 1,
        hasTable = table,
    )
}

private fun isTableRow(trimmedLine: String): Boolean {
    if (trimmedLine.length < 3) return false
    val hasPipe = trimmedLine.startsWith("|") || trimmedLine.indexOf('|') in 1 until trimmedLine.lastIndex
    if (!hasPipe) return false
    // 表头分隔行：只由 | - : 空格组成，且至少两个 -
    if (!trimmedLine.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) return false
    return trimmedLine.windowed(2).any { it == "--" }
}

/**
 * 安全读取 `DisplaySetting.collapseLongMessages`。
 *
 * 收成 `Any?` + 反射读，是为了这条 feature 能单独合入：字段还没加进 DisplaySetting 时
 * 按 [MessageCollapseThresholds.DEFAULT_ENABLED] 走，主代理把字段补上后自动读到真实值，
 * 这里不用再改。（字段落地后也可以直接换成 `settings.collapseLongMessages`。）
 */
internal fun readCollapseLongMessagesSetting(settings: Any?): Boolean {
    if (settings == null) return MessageCollapseThresholds.DEFAULT_ENABLED
    val value = runCatching {
        val cls = settings.javaClass
        val viaGetter = listOf("getCollapseLongMessages", "isCollapseLongMessages")
            .firstNotNullOfOrNull { name ->
                cls.methods
                    .firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(settings) as? Boolean
            }
        viaGetter ?: run {
            val field = cls.declaredFields
                .firstOrNull { it.name == "collapseLongMessages" }
                ?.apply { isAccessible = true }
            field?.get(settings) as? Boolean
        }
    }.getOrNull()
    return value ?: MessageCollapseThresholds.DEFAULT_ENABLED
}
