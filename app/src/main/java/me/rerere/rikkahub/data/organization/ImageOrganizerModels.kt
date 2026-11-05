package me.rerere.rikkahub.data.organization

import java.io.File
import kotlin.uuid.Uuid

/**
 * 图片整理用到的状态模型。这里不 import 任何 Android 类，方便在纯 JVM 单测里直接构造。
 */

/** 图片来源，决定后面走哪条删除路径。 */
enum class ImageSource {
    /** 用户在聊天里上传的图：`filesDir/upload/`，登记在 managed_files。 */
    UPLOAD,

    /** 旧图片生成页的生成图：`filesDir/images/`，登记在 gen_media。 */
    GEN_MEDIA,

    /** 媒体创作记录里的产出图：`filesDir/media_creation/`，生命周期归 MediaCreationRepository。 */
    MEDIA_CREATION,
}

/**
 * 固定的视觉内容标签。只是「这张图看起来是什么」，不推断人物身份、年龄、健康等敏感特征。
 */
enum class ImageCategory(val label: String) {
    PORTRAIT("人物/动漫"),
    SCREENSHOT("截图/文字"),
    SCENERY("风景/实物"),
    DESIGN("设计/图表"),
    OTHER("其他"),

    /** 模型没给出可用结果、或请求失败。 */
    UNRECOGNIZED("未识别");

    companion object {
        /** 除「未识别」外的可选类别，UI 的筛选器按这个顺序展示。 */
        val selectable: List<ImageCategory> = listOf(PORTRAIT, SCREENSHOT, SCENERY, DESIGN, OTHER)
    }
}

data class ImageOrganizerItem(
    /** 稳定 ID：`upload:<managed id>` / `gen:<gen_media id>` / `media:<相对路径>`。 */
    val id: String,
    val name: String,
    /** 绝对路径，预览组件直接用。 */
    val path: String,
    /** 相对 filesDir 的路径。 */
    val relativePath: String,
    val bytes: Long,
    val source: ImageSource,
    val lastModified: Long = 0L,
    val category: ImageCategory? = null,
    val description: String? = null,
    /** 用户勾了「保留」：永不被删，也不受缓存过期影响。 */
    val protected: Boolean = false,
    /** 后端是否允许删这一项；false 时看 [blockReason]。 */
    val deletable: Boolean = true,
    val blockReason: String? = null,
    /** 被别处引用了几次（生成记录的编辑源、媒体创作记录）。 */
    val sharedReferences: Int = 0,
    val classifiedAt: Long? = null,
    /** 这次显示的分类是否直接来自本地缓存。 */
    val cached: Boolean = false,
    /** 上一次识别失败。 */
    val failed: Boolean = false,
    /** 失败原因（简短），成功时为 null。 */
    val error: String? = null,
) {
    val file: File get() = File(path)
}

data class ImageProgress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val currentName: String? = null,
) {
    val finished: Boolean get() = done >= total
}

data class ImageOrganizerState(
    val items: List<ImageOrganizerItem> = emptyList(),
    val selectedModelId: Uuid? = null,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val progress: ImageProgress? = null,
    val error: String? = null,
    /** 当前后台任务 id（null 表示没有任务）。 */
    val jobId: String? = null,
    /** 后台任务状态，进程重启后从 queue.json 恢复，页面重新进来也能看到。 */
    val jobStatus: ImageJobStatus? = null,
    /** 后台任务里还没处理完的张数。 */
    val jobRemaining: Int = 0,
    /** 自动整理开关（默认关闭，必须用户显式打开）。 */
    val auto: ImageAutoConfig = ImageAutoConfig(),
    /** AI 生成的虚拟分类方案，可编辑；只记 id，不动文件。 */
    val plan: ImagePlan? = null,
    val planBusy: Boolean = false,
    /**
     * 这一批识别同时放几个请求在飞。
     *
     * 默认 [DEFAULT_WORKERS]，范围 [MIN_WORKERS]..[MAX_WORKERS]，越界值读出来会被夹回范围。
     * worker 数量是固定个工作协程，不随图片张数增长；请求层的全局限流仍由 ProviderManager 保证。
     */
    val workers: Int = DEFAULT_WORKERS,
) {
    val classifying: Boolean get() = progress != null
    val totalBytes: Long get() = items.sumOf { it.bytes }

    companion object {
        /** 默认并发：3 个请求同时飞。 */
        const val DEFAULT_WORKERS: Int = 3
        const val MIN_WORKERS: Int = 1
        const val MAX_WORKERS: Int = 16

        /** 把任意输入夹到合法范围，坏值（比如 0、负数、99）一律回落到边界值。 */
        fun coerceWorkers(value: Int): Int = value.coerceIn(MIN_WORKERS, MAX_WORKERS)
    }
}

enum class ImageDeleteSkipReason {
    /** 用户标记了保留。 */
    PROTECTED,

    /** 文件已经不在。 */
    NOT_FOUND,

    /** 被别处引用，后端保护。 */
    SHARED_REFERENCE,

    /** 该来源不支持单独删图（媒体创作记录）。 */
    NOT_SUPPORTED,

    /** 传进来的 id 当前状态里没有。 */
    NOT_SELECTED,
}

data class ImageDeleteFailure(val id: String, val reason: String)

data class ImageDeleteSkip(val id: String, val reason: ImageDeleteSkipReason)

data class ImageDeleteResult(
    val deleted: List<String> = emptyList(),
    val failed: List<ImageDeleteFailure> = emptyList(),
    val skipped: List<ImageDeleteSkip> = emptyList(),
    /** 共享引用一类的提示，UI 建议原样展示。 */
    val warnings: List<String> = emptyList(),
)
