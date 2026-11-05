package me.rerere.rikkahub.data.organization

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 自动整理的策略层：配置夹取、候选挑选、付费请求的持久记录、失败归类。
 *
 * 这里不 import 任何 Android 类，全部能在纯 JVM 单测里直接跑。识别与落盘在
 * [ImageOrganizerController] 里做，付费节奏（发几张、哪些不再发）由这里判断。
 */

/**
 * 自动整理的开关与范围。
 *
 * 默认关闭：自动整理会自己发付费请求，必须由用户在这里显式打开。
 * 打开后仍然受 [maxPerRun] 限制，一次后台唤醒最多处理这么多张，不会整库一口气传上去。
 */
@Serializable
data class ImageAutoConfig(
    val enabled: Boolean = false,
    val sources: Set<ImageSource> = ImageSource.entries.toSet(),
    /** 新图少于这个数就先不动，避免为了一两张图反复唤醒。 */
    val minNewImages: Int = MIN_NEW_IMAGES,
    /** 单次后台唤醒最多处理多少张，防止费用失控。 */
    val maxPerRun: Int = MAX_PER_RUN,
) {
    companion object {
        const val MIN_NEW_IMAGES = 1
        const val MAX_PER_RUN = 20
        /** 阈值下限：0 会让「新图不够就先不动」的防抖彻底失效。 */
        const val MIN_NEW_IMAGES_FLOOR = 1
        /** 阈值上限：比一次唤醒能处理的张数更大没有意义，只会让自动整理永远不触发。 */
        const val MIN_NEW_IMAGES_CEILING = MAX_PER_RUN
    }

    /**
     * 把配置夹回合法范围：
     *
     * - 来源为空时关闭，绝不扩大用户选择的上传范围；
     * - [minNewImages] 夹到 [MIN_NEW_IMAGES_FLOOR]..[MIN_NEW_IMAGES_CEILING]，不会大到永远触发不了；
     * - [maxPerRun] 夹到 1..[MAX_PER_RUN]，单次唤醒的付费上限不允许被配置放大。
     */
    fun coerce(): ImageAutoConfig {
        val minImages = minNewImages.coerceIn(MIN_NEW_IMAGES_FLOOR, MIN_NEW_IMAGES_CEILING)
        return copy(
            enabled = enabled && sources.isNotEmpty(),
            sources = sources,
            minNewImages = minOf(minImages, maxPerRun.coerceIn(1, MAX_PER_RUN)),
            maxPerRun = maxPerRun.coerceIn(1, MAX_PER_RUN),
        )
    }
}

/**
 * 从当前扫描结果里挑出「自动整理该处理哪些图」。
 *
 * 规则（纯函数，可单测）：
 * - 只在 [ImageAutoConfig.sources] 里的来源；
 * - 只处理还没有分类结果的图（分类缓存命中就不重复发请求）；
 * - 上次失败过的不自动重试（可能已经扣过费）；
 * - [blockedIds]（队列里的、以及已经发过请求的）不重复入队；
 * - 最多 [ImageAutoConfig.maxPerRun] 张。
 *
 * 关键点：**先排除再去截断**。反过来的话，一旦队列最前面那批都在 [blockedIds] 里，
 * 每轮都会选出同一批、又全部被过滤掉，后面的新图永远轮不到（starvation）。
 *
 * @return 需要识别的图片 id，按扫描顺序
 */
fun selectAutoCandidates(
    items: List<ImageOrganizerItem>,
    config: ImageAutoConfig,
    blockedIds: Set<String> = emptySet(),
): List<String> {
    if (!config.enabled) return emptyList()
    val effective = config.coerce()
    return items.asSequence()
        .filter { it.source in effective.sources }
        .filter { it.category == null }
        .filter { !it.failed && it.error == null }
        .filter { it.id !in blockedIds }
        .map { it.id }
        .take(effective.maxPerRun)
        .toList()
}

/** 队列里已经占位、或者已经处理过的 id：这些不能再被自动整理重新排一遍。 */
fun queuedOrSettledIds(job: ImageOrganizerJob?): Set<String> {
    if (job == null) return emptySet()
    return (job.pendingIds + job.doneIds + job.failedIds).toSet()
}

/**
 * 持久化的「请求已经发出去过」记录。
 *
 * 为什么需要：一次识别一旦把请求发出去就可能已经扣费。进程被杀、用户点停止、后台任务被回收
 * 都会让「结果到底拿到没」变成未知，重启后静默重发等于重复计费。所以发送前先落标记，
 * 之后看到标记就把这张图当成「结果不确定」，等用户手动重新识别。
 *
 * key 用 [ImageCacheKeys.of]：文件被替换（大小 / mtime 变了）自然失效，不会把新文件挡在外面。
 */
interface ImageAttemptStore {
    fun contains(key: String): Boolean

    /** @return true 表示记录**已经真的落盘**；false 时不允许再发请求。 */
    fun mark(key: String): Boolean

    /** 用户显式重新识别时允许重试：清掉这些 key。 */
    fun clear(keys: Collection<String>): Boolean

    /** 当前所有 key，用于扫描后清理已经不存在的图片。 */
    fun keys(): Set<String>

    /** 只保留 [liveKeys] 里的记录（图片被删、或文件被替换后的旧 key）。 */
    fun retainOnly(liveKeys: Set<String>): Int
}

/** 内存实现：单测和「存储不可用」的降级路径用。 */
class InMemoryImageAttemptStore(initially: Collection<String> = emptyList()) : ImageAttemptStore {
    private val keys = LinkedHashSet<String>(initially)

    @Synchronized
    override fun contains(key: String): Boolean = key in keys

    @Synchronized
    override fun mark(key: String): Boolean {
        keys += key
        return true
    }

    @Synchronized
    override fun clear(keys: Collection<String>): Boolean {
        this.keys.removeAll(keys.toSet())
        return true
    }

    @Synchronized
    override fun keys(): Set<String> = keys.toSet()

    @Synchronized
    override fun retainOnly(liveKeys: Set<String>): Int {
        val removed = keys.count { it !in liveKeys }
        keys.retainAll(liveKeys)
        return removed
    }
}

/**
 * 一次后台运行为什么结束。Worker 据此决定要不要让 WorkManager 重试。
 *
 * 付费请求只允许在「确定没发出去」的情况下重跑：能重跑的是存储类失败；
 * 单张请求失败、用户取消、被系统中断都不重试，交给用户显式点继续。
 */
enum class ImageJobFailure {
    /** 任务单 / 请求记录 / 设置没能落盘：重跑不会更糟。 */
    STORAGE,

    /** 任务单损坏或找不到：重跑没用。 */
    INVALID_JOB,

    /** 识别模型不可用（被删、提供商停用）：重跑没用。 */
    MODEL_UNAVAILABLE,

    /** 用户主动取消。 */
    CANCELLED,

    /** 系统中断（进程被杀 / 后台任务被回收）。 */
    INTERRUPTED,

    /** 单张请求级失败，已经逐条记进任务单。 */
    ITEM_ERRORS,
    ;

    /** 只有「确定没花钱」的失败才值得让系统再试一次。 */
    val retryable: Boolean get() = this == STORAGE
}

/**
 * AI 规划方案一次最多看多少张图。
 *
 * 上限来自提示词体量：每行 `id | 文件名 | 粗分类 | 描述` 约 200 字节，200 行配上 4096 的输出预算仍然安全。
 * 界面说明和控制器取数必须用同一个常量，不能各写一个数字。
 */
const val IMAGE_PLAN_MAX_ITEMS: Int = 200

/** 能进 AI 规划清单的图：必须已经有本地识别结果（分类或描述）。 */
fun ImageOrganizerItem.eligibleForPlan(): Boolean = category != null || description != null

/**
 * 自动整理一次后台唤醒要不要开单。
 *
 * [carryOver] 是上一单没走完的剩余张数：只要还有没走完的，就继续处理，
 * 不受 [ImageAutoConfig.minNewImages] 影响（否则「新图不够」会把手头的活永远挂住）。
 */
fun shouldStartAutoRun(
    candidateCount: Int,
    carryOver: Int,
    config: ImageAutoConfig,
): Boolean {
    if (!config.enabled) return false
    if (carryOver > 0) return true
    return candidateCount >= config.coerce().minNewImages
}

/** `filesDir/image_organizer/auto.json`。写法和其它小 JSON 一致：临时文件 + rename，读坏了当默认（关闭）。 */
class ImageOrganizerAutoStore(private val file: File) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(key: String): Any =
            locks[key] ?: Any().let { created -> locks.putIfAbsent(key, created) ?: created }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }

    private val lock: Any = lockFor(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })

    @Volatile
    var lastFailure: String? = null
        private set

    /** 读不出来就是「关闭」，绝不因为文件坏了就默认开启自动整理。 */
    fun read(): ImageAutoConfig = synchronized(lock) {
        if (!file.isFile) return@synchronized ImageAutoConfig()
        runCatching { json.decodeFromString<ImageAutoConfig>(file.readText()).coerce() }
            .getOrDefault(ImageAutoConfig())
    }

    fun write(config: ImageAutoConfig): Boolean = synchronized(lock) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(config.coerce()).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file) && !replaceAtomically(temp)) {
                throw IOException("临时文件改名失败")
            }
            lastFailure = null
            true
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            lastFailure = "自动整理设置没能保存：${t.message ?: t::class.simpleName ?: "未知原因"}"
            false
        }
    }

    private fun replaceAtomically(temp: File): Boolean = runCatching {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
