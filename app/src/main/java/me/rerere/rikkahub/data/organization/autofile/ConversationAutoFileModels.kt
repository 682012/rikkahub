package me.rerere.rikkahub.data.organization.autofile

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 可关闭的「话题归库」设置。
 *
 * 默认关闭：打开之后会把**限长摘要**（标题 + 首尾几段正文）发给已经配置好的快速模型，
 * 由模型建议把闲置会话归到哪个文件夹。所以这是用户显式动作，不在装好后自动打开。
 */
/**
 * 整理模式。
 *
 * - [AI]：沿用 r21 的快速模型分类（默认值，保持既有行为）。会把标题 + 首尾限长摘要发给已配置的快速模型，
 *   花 API 额度，所以开关默认关闭、手动整理前要确认一次。
 * - [LOCAL]：完全本地规则（[ConversationAutoFileLocalClassifier]），只用标题/标签/词频/相似度做
 *   可解释的确定性判断，**不发任何网络请求、不花额度、不调用模型**。它不是 AI，也不承诺准确率。
 *
 * 老版本设置文件没有 `mode` 字段，解析后落回 [AI]，与 r21 行为一致。
 */
@Serializable
enum class AutoFileMode {
    AI,
    LOCAL,
}

@Serializable
data class ConversationAutoFileSettings(
    val enabled: Boolean = false,
    /** 整理模式，默认 [AutoFileMode.AI]（r21 既有行为）。 */
    val mode: AutoFileMode = AutoFileMode.AI,
    /** 会话闲置多少小时才算候选。默认 36 小时，值域 [MIN_IDLE_HOURS]..[MAX_IDLE_HOURS]。 */
    val idleHours: Int = DEFAULT_IDLE_HOURS,
    /** 抽屉里是否按会话家族折叠（只是展示口径，不影响归库）。 */
    val collapseFamilies: Boolean = true,
) {
    /** 把闲置时长夹回合法区间：小于 24 小时会让「刚聊完又被搬走」变成常态，大于 168 小时基本不会触发。 */
    fun clamped(): ConversationAutoFileSettings =
        if (idleHours in MIN_IDLE_HOURS..MAX_IDLE_HOURS) this
        else copy(idleHours = idleHours.coerceIn(MIN_IDLE_HOURS, MAX_IDLE_HOURS))

    fun idleMillis(): Long = clamped().idleHours.toLong() * 60L * 60L * 1000L

    companion object {
        const val MIN_IDLE_HOURS = 24
        const val MAX_IDLE_HOURS = 168
        const val DEFAULT_IDLE_HOURS = 36
        val DEFAULT = ConversationAutoFileSettings()
    }
}

/**
 * 界面看到的整理状态。字段与契约一致，D 的 panel 直接读这里：
 * [running] 忙态、[moved] 本批搬了几个、[canUndo] 能不能撤销上一批、[error] 失败原因（可重试）。
 */
data class ConversationAutoFileState(
    val settings: ConversationAutoFileSettings = ConversationAutoFileSettings.DEFAULT,
    val running: Boolean = false,
    val moved: Int = 0,
    val canUndo: Boolean = false,
    val error: String? = null,
)

/** 助手内的一个文件夹，只用 id 和名字（判断「已有库优先」）。 */
data class AutoFileFolderRef(
    val id: Uuid,
    val name: String,
)

/**
 * 一个可参与整理的会话。
 *
 * [familyKey] 来自 `lineageId ?: id`，用来做「同族只挑最早一条」——**不看标题**，
 * 同标题不等于同一家族，这是契约里的保守口径。
 */
data class AutoFileCandidate(
    val id: Uuid,
    val title: String,
    val createAt: Long,
    val updateAt: Long,
    val folderId: Uuid?,
    val pinned: Boolean,
    val locked: Boolean,
    val familyKey: Uuid,
)

/** 发给模型的单条输入：标题 + 首尾限长文本（不含思考、工具调用、附件）。 */
data class AutoFileClassifyItem(
    val id: Uuid,
    val title: String,
    val excerpt: String,
)

/**
 * 一条建议。
 *
 * [reason] 只由**本地规则模式**填（可解释的命中理由，例如「标题命中 3 次『模型』，与库『模型与工具』相似度 0.62」）；
 * AI 模式留空，界面用「模型分类」概括。
 */
data class AutoFileAssignment(
    val conversationId: Uuid,
    val folderName: String,
    val isNew: Boolean,
    val confidence: Double,
    val reason: String? = null,
)

/** 严格 JSON 解析结果：[error] 非空时一条都不采纳（畸形输出一律不动）。 */
data class AutoFileParsedClassification(
    val assignments: List<AutoFileAssignment> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = assignments.isEmpty()
}

/** 规划后要执行的一次搬迁：[existingFolderId] 为空表示需要先建这个新库。 */
data class AutoFilePlannedMove(
    val conversationId: Uuid,
    val folderName: String,
    val existingFolderId: Uuid?,
    /** 可解释理由（本地模式才有），随日志落盘给历史详情用。 */
    val reason: String? = null,
)

data class AutoFileRejection(
    val conversationId: Uuid,
    val reason: String,
)

/** 规划结果。只会来自 [ConversationAutoFilePlanner]，不碰数据库。 */
data class AutoFilePlan(
    val moves: List<AutoFilePlannedMove> = emptyList(),
    val newFolders: List<String> = emptyList(),
    val rejected: List<AutoFileRejection> = emptyList(),
) {
    val isEmpty: Boolean get() = moves.isEmpty()
}

/** 一次整理批次的详情：日志批次 + 会话标题（标题读不到就为空，界面退回显示 id）。 */
data class AutoFileBatchDetail(
    val batch: AutoFileJournalBatch,
    val titles: Map<Uuid, String> = emptyMap(),
)

/**
 * 一次「立即整理」排到后台之后的真实回执。
 *
 * [workName] 是每助手**唯一**的名字；[workId] 是 WorkManager 里那个任务的 id（查不到时为 null，
 * 不会编一个假的）；[enqueued] 为 false 表示同名任务还在队列/运行中，本次没有新入队；
 * [state] 是 WorkInfo 的真实状态名（ENQUEUED / RUNNING / SUCCEEDED …）。
 */
data class AutoFileManualWork(
    val workName: String,
    val workId: String? = null,
    val state: String = "ENQUEUED",
    val enqueued: Boolean = false,
)

/** 会话当前的归属快照，撤销前用来确认「还没被用户动过」。 */
data class AutoFilePlacement(
    val conversationId: Uuid,
    val folderId: Uuid?,
    val updateAt: Long,
    val pinned: Boolean,
    val locked: Boolean,
)
