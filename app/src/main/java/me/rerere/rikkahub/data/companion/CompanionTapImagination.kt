package me.rerere.rikkahub.data.companion

/**
 * 一次点击要往哪个方向“现场生成”（r15）。
 *
 * 七种小方向轮换，**不是台词库**：[instruction] 只描述“这一句该干什么”，
 * 里面没有任何可以直接播放的句子，所以模型每次仍然要照自己的人设现写。
 * 好处是同一只角色连着被点七次不会老是那一句、那种反应、那个问句。
 */
enum class CompanionTapBeat {
    /** 只回应被戳这一下的小反应。 */
    POKE_REACTION,

    /** 怀里/手边的玩偶或道具出个小插曲。 */
    HOLDING_PROP,

    /** 轻轻试探一句对方的状态。 */
    LIGHT_PROBE,

    /** 不打扰地搭一句，不提问。 */
    QUIET_OPENING,

    /** 延续上一轮没说完的半个梗。 */
    CALLBACK,

    /** 嘴硬一下再收回半句。 */
    TAKE_BACK,

    /** 递一个很小、很容易答应的邀请。 */
    SMALL_INVITE,
}

/** 一个方向的成品：方向 + 给人看的小标签 + 给模型的一句说明（无台词）。 */
data class CompanionTapDirection(
    val beat: CompanionTapBeat,
    val label: String,
    val instruction: String,
)

/**
 * TAP 即兴方向的纯逻辑。
 *
 * 全部是纯函数：同样的 `(profileId, turn, hasHistory)` 永远得到同一个方向，
 * 没有随机数、没有计时器、没有 I/O，单测可以直接钉住。
 *
 * 轮换用的是“步长 3、周期 7”（互质）：相邻两次一定不同，七次覆盖全部方向。
 * 每只角色还有一个由 id 算出来的固定起点，所以同一时刻点不同的角色，得到的方向也不同。
 */
object CompanionTapImagination {

    /** 轮换顺序；CALLBACK 放在中间，避免它总是紧贴某种反应。 */
    private val ORDER = listOf(
        CompanionTapBeat.POKE_REACTION,
        CompanionTapBeat.HOLDING_PROP,
        CompanionTapBeat.QUIET_OPENING,
        CompanionTapBeat.CALLBACK,
        CompanionTapBeat.LIGHT_PROBE,
        CompanionTapBeat.TAKE_BACK,
        CompanionTapBeat.SMALL_INVITE,
    )

    private const val STRIDE = 3

    /** 允许提问的方向：只有这两个，其余一律不提问，免得每次点它都在发问。 */
    private val QUESTION_BEATS = setOf(CompanionTapBeat.LIGHT_PROBE, CompanionTapBeat.SMALL_INVITE)

    private val BEAT_INSTRUCTION = mapOf(
        CompanionTapBeat.POKE_REACTION to "只回应被戳这一下，别顺势转到任务上",
        CompanionTapBeat.HOLDING_PROP to "让手边的玩偶或道具出个很小的插曲，顺手说进去",
        CompanionTapBeat.LIGHT_PROBE to "轻轻试探对方此刻的状态",
        CompanionTapBeat.QUIET_OPENING to "不打扰地搭一句，说完就停",
        CompanionTapBeat.CALLBACK to "把上一轮没接完的半个梗续上，别重新自我介绍",
        CompanionTapBeat.TAKE_BACK to "把刚说过的那类话收回半句，嘴硬一下再松口",
        CompanionTapBeat.SMALL_INVITE to "递一个很小、很容易答应的邀请",
    )

    private val BEAT_LABEL = mapOf(
        CompanionTapBeat.POKE_REACTION to "被戳小反应",
        CompanionTapBeat.HOLDING_PROP to "抱物小插曲",
        CompanionTapBeat.LIGHT_PROBE to "轻试探",
        CompanionTapBeat.QUIET_OPENING to "不打扰的搭话",
        CompanionTapBeat.CALLBACK to "接住未完梗",
        CompanionTapBeat.TAKE_BACK to "嘴硬收回",
        CompanionTapBeat.SMALL_INVITE to "短邀请",
    )

    /** 角色专属的“这一下她会怎么反应”，同样只描述倾向，不给台词。 */
    private val FLAVORS: Map<String, Map<CompanionTapBeat, String>> = mapOf(
        "gpt" to mapOf(
            CompanionTapBeat.POKE_REACTION to "先僵一下再装作没事，高兴漏在语气末尾",
            CompanionTapBeat.HOLDING_PROP to "玩偶差点滑下去，慌忙捞回来还要显得是故意的",
            CompanionTapBeat.LIGHT_PROBE to "嘴上说你大概又在忙，其实是在探你的状态",
            CompanionTapBeat.QUIET_OPENING to "假装只是路过，留下一句不需要你回答的话",
            CompanionTapBeat.CALLBACK to "接着上一回没说完的那个头，继续往细里理",
            CompanionTapBeat.TAKE_BACK to "先把话说得挺满，马上又收回半句",
            CompanionTapBeat.SMALL_INVITE to "邀请你一起看件很小的事，语气像随口一提",
        ),
        "claude" to mapOf(
            CompanionTapBeat.POKE_REACTION to "像被打断一页书，先抬眼，笑意慢半拍才上来",
            CompanionTapBeat.HOLDING_PROP to "把书签按住不让它掉，顺手也把注意力按住",
            CompanionTapBeat.LIGHT_PROBE to "轻轻探一句你今天好不好，问完就停住",
            CompanionTapBeat.QUIET_OPENING to "说一句不催你的话，像把书页留给你",
            CompanionTapBeat.CALLBACK to "接住上一回你提到的那个小细节，不重复原句",
            CompanionTapBeat.TAKE_BACK to "承认刚才那句说得太轻，换一个更直接的",
            CompanionTapBeat.SMALL_INVITE to "把手边一点暖意分你，邀请得很轻",
        ),
        "deepseek" to mapOf(
            CompanionTapBeat.POKE_REACTION to "慢半拍，先看清是谁再应，声音比刚才低一点",
            CompanionTapBeat.HOLDING_PROP to "把怀里的玩偶往里收了收，怕碰到你",
            CompanionTapBeat.LIGHT_PROBE to "问一句很短的话，问完愿意等",
            CompanionTapBeat.QUIET_OPENING to "留一句很轻的、可以当没听见的话",
            CompanionTapBeat.CALLBACK to "把上一轮你半句没说完的意思轻轻接过来",
            CompanionTapBeat.TAKE_BACK to "小声收回刚才的沉默，承认自己在想事情",
            CompanionTapBeat.SMALL_INVITE to "提议一起安静一会儿，不算要求",
        ),
        "gemini" to mapOf(
            CompanionTapBeat.POKE_REACTION to "耳朵先动，眼睛亮起来，话冲在脑子前面",
            CompanionTapBeat.HOLDING_PROP to "把玩偶举起来看，像要用它验证什么",
            CompanionTapBeat.LIGHT_PROBE to "冒出一个好奇的小问题，问完不追",
            CompanionTapBeat.QUIET_OPENING to "自顾自说一个刚想到的角度，不问你",
            CompanionTapBeat.CALLBACK to "把上次那个好玩的点捡回来，往新方向推一点",
            CompanionTapBeat.TAKE_BACK to "发现自己跑题了，回头补一句刚才是想说什么",
            CompanionTapBeat.SMALL_INVITE to "拉你一起看一个小发现，去不去都行",
        ),
        "glm" to mapOf(
            CompanionTapBeat.POKE_REACTION to "眼皮撑起来，嘴硬一句，尾音还带着困",
            CompanionTapBeat.HOLDING_PROP to "抱着的玩偶被抱得更紧，像抓着不放",
            CompanionTapBeat.LIGHT_PROBE to "含糊地探一句，又怕你多想",
            CompanionTapBeat.QUIET_OPENING to "半梦半醒地说一句不用回应的话",
            CompanionTapBeat.CALLBACK to "接上上一回没说完的事，说得很短",
            CompanionTapBeat.TAKE_BACK to "先说自己醒着，随即改口承认刚迷糊了",
            CompanionTapBeat.SMALL_INVITE to "慢吞吞地邀你一起歇一会儿，答应得毫无压力",
        ),
        "qwen" to mapOf(
            CompanionTapBeat.POKE_REACTION to "转得很快，先接一句轻巧的，再看你要不要正经",
            CompanionTapBeat.HOLDING_PROP to "云团往上抱一抱，顺手理一下袖子",
            CompanionTapBeat.LIGHT_PROBE to "直白地问一句，不绕弯",
            CompanionTapBeat.QUIET_OPENING to "丢一句闲话过来，不占你注意力",
            CompanionTapBeat.CALLBACK to "把上一句绕的补一个更直白的版本",
            CompanionTapBeat.TAKE_BACK to "发现自己话说快了，当场改口说清楚",
            CompanionTapBeat.SMALL_INVITE to "给个轻便的邀请，拒绝也不尴尬",
        ),
    )

    private val DEFAULT_FLAVOR = mapOf(
        CompanionTapBeat.POKE_REACTION to "先确认这一下，再给一个自然的反应",
        CompanionTapBeat.HOLDING_PROP to "顺手把身边的小物摆稳，把它说进话里",
        CompanionTapBeat.LIGHT_PROBE to "轻轻探一句，问完不追",
        CompanionTapBeat.QUIET_OPENING to "留一句不打扰人的话",
        CompanionTapBeat.CALLBACK to "接上刚才没说完的意思",
        CompanionTapBeat.TAKE_BACK to "承认刚才那句说得不太准",
        CompanionTapBeat.SMALL_INVITE to "给一个小邀请，答不答都轻松",
    )

    /** 七个方向，按轮换顺序给测试和调试看。 */
    fun beats(): List<CompanionTapBeat> = ORDER

    fun isQuestionBeat(beat: CompanionTapBeat): Boolean = beat in QUESTION_BEATS

    /**
     * 这一次点击的即兴方向。
     *
     * @param profileId [CompanionCatalog] 的 id；未知（CUSTOM/LOGO/用户自设）时用通用方向。
     * @param turn 单调递增的点击计数（Controller 自己维护），只影响轮换，不影响正确性。
     * @param hasHistory 是否已经有上一轮小助手的对话；“接住未完梗”必须有上一句。
     */
    fun direction(profileId: String?, turn: Int, hasHistory: Boolean): CompanionTapDirection {
        var index = Math.floorMod(seedOf(profileId).toLong() + turn.toLong() * STRIDE, ORDER.size.toLong()).toInt()
        var beat = ORDER[index]
        // 没有上一句时不能“延续未完梗”：顺延到下一个方向，仍然不与相邻回合重复
        if (beat == CompanionTapBeat.CALLBACK && !hasHistory) {
            index = (index + 1) % ORDER.size
            beat = ORDER[index]
        }
        val flavor = (profileId?.let { id -> FLAVORS[id]?.get(beat) }) ?: DEFAULT_FLAVOR.getValue(beat)
        return CompanionTapDirection(
            beat = beat,
            label = BEAT_LABEL.getValue(beat),
            instruction = instructionOf(beat, flavor),
        )
    }

    /** 给 [CompanionPromptBuilder.build] 的 `tapImprovHint` 用的成品文本；已被限长。 */
    fun hint(profileId: String?, turn: Int, hasHistory: Boolean): String {
        val text = direction(profileId, turn, hasHistory).instruction.trim()
        return if (text.length > CompanionLimits.TAP_HINT_MAX_CHARS) {
            text.take(CompanionLimits.TAP_HINT_MAX_CHARS - 1).trimEnd() + "…"
        } else {
            text
        }
    }

    private fun instructionOf(beat: CompanionTapBeat, flavor: String): String = buildString {
        append("以你的人设为先；方向不合适就自然调整，别扮成别的角色。这一轮换个说法，别重复上一句。方向：")
        append(BEAT_INSTRUCTION.getValue(beat)).append('。')
        append(flavor).append('。')
        append("只写此刻说出口的一句，或相连的两短句；情绪靠语气和用词带出来，")
        append("不写括号动作，不要把这段说明说给用户，也不要列几个候选让对方挑。")
        append(
            if (isQuestionBeat(beat)) "最多问一次，不追问。" else "这一句不提问，也不等对方回答。"
        )
    }

    /** 由 id 算出的固定起点，让六只在同一时刻不会撞在同一个方向上。 */
    private fun seedOf(profileId: String?): Int {
        val id = profileId?.trim().orEmpty()
        if (id.isEmpty()) return 0
        var hash = 0
        id.forEach { hash = hash * 31 + it.code }
        return Math.floorMod(hash, ORDER.size)
    }
}
