package me.rerere.rikkahub.data.ai.learning

/**
 * 指标 → 自动经验的唯一入口，纯函数，不碰文件也不碰 Android。
 *
 * 规则：
 * - 自动经验只由固定模板生成，模板文本只填计数（轮数、次数），不写任何来自对话的内容；
 * - 必须有重复证据（≥ [LearningLimits.MIN_EVIDENCE_TURNS] 轮）才成立，单次失败一律不算；
 * - 只看最近 [LearningLimits.EVIDENCE_SCAN_TURNS] 轮、[LearningLimits.EVIDENCE_WINDOW_MS] 内的指标，
 *   旧证据会自然过期；
 * - 模板只描述「怎么做更省事」，不会提权限、工具开关、系统提示或模型参数，更不会自动改它们。
 */
object LearningAnalyzer {

    private val WHITESPACE = Regex("\\s+")

    /** id 允许的字符：UUID、模型名（含 openai/gpt-4o 这类斜杠）、工具名 */
    private val ID_DISALLOWED = Regex("[^A-Za-z0-9_.:/-]")

    /** 允许的时钟误差：指标时间稍微超前于本机时间时仍纳入统计 */
    private const val CLOCK_SKEW_MS: Long = 60L * 1000

    private class Template(
        val id: String,
        val minTurns: Int,
        val matches: (LearningTurn) -> Boolean,
        val text: (evidence: Int, matched: List<LearningTurn>) -> String,
    )

    /** 模板顺序只影响同证据数时的稳定排序 */
    private val templates: List<Template> = listOf(
        Template(
            id = "tool-error-retry",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { it.toolErrors > 0 },
            text = { evidence, _ ->
                "最近 $evidence 轮都出现过工具调用失败。失败后不要用同样的参数原样重试：" +
                    "先读报错信息；只有证据表明参数或路线有问题时才调整，遵循现有重试与审批规则。"
            },
        ),
        Template(
            id = "tool-heavy",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { it.toolCalls >= 6 },
            text = { evidence, matched ->
                val maxCalls = matched.maxOfOrNull { it.toolCalls } ?: 0
                "最近 $evidence 轮包含较多工具调用（最多 $maxCalls 次）。" +
                    "独立读取可合并并发；已有且未变化的资料可复用。调用多不等于浪费，不能省掉任务需要的操作和验证。"
            },
        ),
        Template(
            id = "step-limit",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { it.stepLimitReached },
            text = { evidence, _ ->
                "最近 $evidence 轮触到了步数上限。优先推进关键依赖、及时记录完成状态；" +
                    "确实到达运行上限时交代未完成部分，不提前放弃任务或冒称完成。"
            },
        ),
        Template(
            id = "retry",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { it.retries > 0 },
            text = { evidence, _ ->
                "最近 $evidence 轮发生过请求重试，这可能来自网络或服务波动，不能据此归因于回答。" +
                    "沿用客户端恢复状态，不重新执行已经完成或效果未知的写操作。"
            },
        ),
        Template(
            id = "regenerated",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { it.regenerations > 0 },
            text = { evidence, _ ->
                "最近 $evidence 轮的回答被用户重新生成过。输出前先确认要回答的具体问题和格式，" +
                    "一次给出对题、长度合适的答案，避免让人再重来一遍。"
            },
        ),
        Template(
            id = "interrupted",
            minTurns = LearningLimits.MIN_EVIDENCE_TURNS,
            matches = { !it.completed && !it.stepLimitReached },
            text = { evidence, _ ->
                "最近 $evidence 轮没有正常跑完。被中断时先说清楚停在哪一步、下一步要做什么，" +
                    "方便下一轮直接接上。"
            },
        ),
    )

    /**
     * 按指标算出自动经验。
     *
     * [modelId] 不为空时只用这个模型自己的指标：换模型后旧模型的证据不再支撑新模型的注入，
     * 也不会拿别的模型的统计冒充当前模型的表现。样本不足就是没有自动经验（用户经验不受影响）。
     */
    fun analyze(turns: List<LearningTurn>, modelId: String?, now: Long): List<LearningGuidance> {
        val recent = turns
            .takeLast(LearningLimits.EVIDENCE_SCAN_TURNS)
            .filter { it.at <= now + CLOCK_SKEW_MS && now - it.at <= LearningLimits.EVIDENCE_WINDOW_MS }
        if (recent.isEmpty()) return emptyList()
        val scoped = if (modelId.isNullOrBlank()) {
            recent
        } else {
            val sameModel = recent.filter { it.modelId == modelId }
            sameModel
        }
        if (scoped.isEmpty()) return emptyList()
        val lastAt = scoped.maxOf { it.at }
        return templates
            .mapNotNull { template ->
                val matched = scoped.filter(template.matches)
                if (matched.size < template.minTurns) return@mapNotNull null
                LearningGuidance(
                    id = "auto:" + template.id,
                    text = template.text(matched.size, matched).take(LearningLimits.MAX_RULE_CHARS),
                    kind = GuidanceKind.AUTO,
                    enabled = true,
                    evidenceTurns = matched.size,
                    updatedAt = lastAt,
                )
            }
            .sortedWith(compareByDescending<LearningGuidance> { it.evidenceTurns }.thenBy { it.id })
            .take(LearningLimits.MAX_AUTO_GUIDANCE)
    }

    /** 纯计数汇总，不做任何推断。 */
    fun stats(turns: List<LearningTurn>): LearningStats = LearningStats(
        turns = turns.size,
        completedTurns = turns.count { it.completed },
        toolCalls = turns.sumOf { it.toolCalls },
        toolErrors = turns.sumOf { it.toolErrors },
        retries = turns.sumOf { it.retries },
        stepLimitTurns = turns.count { it.stepLimitReached },
        regeneratedTurns = turns.count { it.regenerations > 0 },
    )

    /**
     * 落盘前的清洗：id 截断到 [LearningLimits.MAX_ID_CHARS]、计数去负、
     * 时间落在 (0, now] 内、工具失败数不超过调用数。
     */
    fun sanitizeTurn(turn: LearningTurn, assistantId: String, now: Long): LearningTurn {
        val toolErrors = clamp(turn.toolErrors)
        return LearningTurn(
            assistantId = assistantId,
            conversationId = sanitizeId(turn.conversationId),
            messageId = sanitizeId(turn.messageId),
            modelId = sanitizeId(turn.modelId).orEmpty(),
            at = if (turn.at in 1..now) turn.at else now,
            steps = clamp(turn.steps),
            toolCalls = maxOf(clamp(turn.toolCalls), toolErrors),
            toolErrors = toolErrors,
            retries = clamp(turn.retries),
            completed = turn.completed,
            stepLimitReached = turn.stepLimitReached,
            regenerations = turn.regenerations.coerceIn(0, 1_000),
            firstContentMs = turn.firstContentMs?.coerceAtLeast(0),
            requestMs = turn.requestMs?.coerceAtLeast(0),
        )
    }

    /** 用户经验文本：单行、去控制符与零宽/方向控制字符、限长。 */
    fun sanitizeGuidanceText(text: String): String = text
        .replace(WHITESPACE, " ")
        .filterNot { it.isInvisible() }
        .trim()
        .take(LearningLimits.MAX_GUIDANCE_TEXT)
        .trim()

    /** id 清洗：去掉空白、非白名单字符，超长截断；空串返回 null。 */
    fun sanitizeId(raw: String?, max: Int = LearningLimits.MAX_ID_CHARS): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text
            .replace(WHITESPACE, "")
            .replace(ID_DISALLOWED, "")
            .take(max)
            .ifEmpty { null }
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 10_000)

    private fun Char.isInvisible(): Boolean {
        val code = code
        return isISOControl() || code == 0xFEFF || code in 0x200B..0x200F || code in 0x202A..0x202E
    }
}
