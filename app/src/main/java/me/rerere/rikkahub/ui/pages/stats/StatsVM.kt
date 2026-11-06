package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import me.rerere.rikkahub.ui.pages.chat.CacheHitRate
import me.rerere.rikkahub.ui.pages.chat.CacheSample
import me.rerere.rikkahub.ui.pages.chat.computeCacheHitRate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    // 热力图：每天的对话请求次数
    val requestsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

/** 用量统计的时间范围；[days] 为空表示全部，按月分桶 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

data class UsagePoint(
    // 按天时是当天，按月时是当月 1 号
    val date: LocalDate,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
) {
    val totalTokens get() = promptTokens + completionTokens
}

data class ModelUsageItem(
    // 模型已被删除且没有名称快照时为空
    val name: String?,
    val totalTokens: Long,
)

data class UsageStats(
    val range: UsageRange,
    val totals: UsageTotals,
    val points: List<UsagePoint>,
    val byMonth: Boolean,
    val models: List<ModelUsageItem>,
    val sources: List<SourceUsage>,
    /**
     * 缓存命中率：只由 [computeCacheHitRate] 的口径算出，分子分母同 (provider, model)，
     * 没报过缓存的记录排除在外。`null` 比例 = 未知，页面上不能显示成 0%。
     */
    val cache: CacheHitRate = CacheHitRate(),
    /** 命中率用的来源口径：`UsageSource.CHAT.name` = 只算主对话；null = 显式选择"全部"。 */
    val cacheSource: String? = UsageSource.CHAT.name,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    // 切换范围时先保留旧数据，查完再替换，避免页面闪成加载状态
    private val _usage = MutableStateFlow<UsageStats?>(null)
    val usage = _usage.asStateFlow()

    private val _range = MutableStateFlow(UsageRange.MONTH)
    val range = _range.asStateFlow()

    /**
     * 全局统计页的缓存命中率默认只看主对话（[UsageSource.CHAT]）。
     * 子代理和标题/摘要/翻译这些辅助请求默认混进来会让"我的缓存命中率"变成一锅粥，
     * 想连子代理一起看必须由用户显式切到"全部"。
     */
    private val _cacheAllSources = MutableStateFlow(false)
    val cacheAllSources = _cacheAllSources.asStateFlow()

    private var usageJob: Job? = null

    init {
        viewModelScope.launch { loadStats() }
        loadUsage(_range.value)
        viewModelScope.launch {
            usageRecordDAO.observeRecordCount().drop(1).collectLatest {
                delay(200)
                loadUsage(_range.value)
                loadStats()
            }
        }
    }

    fun setRange(range: UsageRange) {
        if (range == _range.value) return
        _range.value = range
        loadUsage(range)
    }

    /** 切换命中率的来源口径：false = 只算主对话（默认），true = 显式包含全部来源。 */
    fun setCacheAllSources(allSources: Boolean) {
        if (_cacheAllSources.value == allSources) return
        _cacheAllSources.value = allSources
        loadUsage(_range.value)
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日）
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val since = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 按对话请求数统计活跃度，走 usage_record 的 created_at 索引，不用解析消息 JSON；
        // 多步工具调用和重试会多算几次，热力图按四分位分档，只看相对活跃程度
        val requestsPerDay = usageRecordDAO.getRequestCountPerDay(UsageSource.CHAT.name, since)
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()

        val totalConversations = conversationDAO.countAll()
        val totalMessages = messageNodeDAO.countAll()
        val launchCount = settingsStore.launchCountFlow.first()

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            requestsPerDay = requestsPerDay,
            launchCount = launchCount,
        )
    }

    private fun loadUsage(range: UsageRange) {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val startDate = range.days?.let { today.minusDays(it - 1L) }
            val since = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            // 用量流水包含标题、翻译等不落到消息里的请求，删掉的会话也还算在内
            val totals = usageRecordDAO.getTotals(since)
            val byMonth = range.days == null
            val buckets = usageRecordDAO.getTotalsByBucket(if (byMonth) "%Y-%m" else "%Y-%m-%d", since)
            val points = if (byMonth) monthlyPoints(buckets, today) else dailyPoints(buckets, startDate!!, today)
            val settings = settingsStore.awaitLoaded()
            val models = usageRecordDAO.getTotalsByModel(since).map { usage ->
                val model = usage.modelId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?.let { settings.findModelById(it) }
                ModelUsageItem(
                    name = model?.displayName?.ifBlank { null } ?: model?.modelId ?: usage.modelName,
                    totalTokens = usage.promptTokens + usage.completionTokens,
                )
            }
            val cacheSource = if (_cacheAllSources.value) null else UsageSource.CHAT.name
            _usage.value = UsageStats(
                range = range,
                totals = totals,
                points = points,
                byMonth = byMonth,
                models = models,
                sources = usageRecordDAO.getTotalsBySource(since),
                cache = computeCacheHitRate(
                    usageRecordDAO.getCacheCoverage(since, cacheSource).flatMap { row ->
                        val key = "${row.providerId}|${row.modelKey}"
                        val bad = row.invalidCacheReports > 0
                        buildList {
                            if (row.knownRequests > 0 || row.knownPromptTokens > 0 || row.knownCachedTokens > 0) {
                                add(
                                    CacheSample(
                                        modelKey = key,
                                        promptTokens = row.knownPromptTokens,
                                        cachedTokens = row.knownCachedTokens,
                                        requests = row.knownRequests,
                                        reported = true,
                                        invalid = bad,
                                    ),
                                )
                            }
                            if (row.unknownRequests > 0 || row.unknownPromptTokens > 0) {
                                add(
                                    CacheSample(
                                        modelKey = key,
                                        promptTokens = row.unknownPromptTokens,
                                        cachedTokens = 0,
                                        requests = row.unknownRequests,
                                        reported = false,
                                        invalid = bad,
                                    ),
                                )
                            }
                        }
                    },
                ),
                cacheSource = cacheSource,
            )
        }
    }

    // 没有用量的日子补 0，柱子才能按日期均匀排开
    private fun dailyPoints(buckets: List<UsageBucket>, start: LocalDate, today: LocalDate): List<UsagePoint> {
        val byDate = buckets.associateBy { it.bucket }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { date -> byDate[date.toString()].toPoint(date) }
            .toList()
    }

    // 全部范围按月，从最早有用量的月份排到本月
    private fun monthlyPoints(buckets: List<UsageBucket>, today: LocalDate): List<UsagePoint> {
        val byMonth = buckets.mapNotNull { bucket ->
            runCatching { YearMonth.parse(bucket.bucket) to bucket }.getOrNull()
        }.toMap()
        val first = byMonth.keys.minOrNull() ?: return emptyList()
        val current = YearMonth.from(today)
        return generateSequence(first) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(current) }
            .map { month -> byMonth[month].toPoint(month.atDay(1)) }
            .toList()
    }

    private fun UsageBucket?.toPoint(date: LocalDate) = UsagePoint(
        date = date,
        promptTokens = this?.promptTokens ?: 0,
        completionTokens = this?.completionTokens ?: 0,
        cachedTokens = this?.cachedTokens ?: 0,
    )
}
