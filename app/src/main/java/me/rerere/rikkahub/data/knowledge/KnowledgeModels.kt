package me.rerere.rikkahub.data.knowledge

import java.time.Instant

/**
 * 本地知识库的硬边界。纯常量，仓库、工具、UI 共用，避免各处各写一份。
 *
 * 这些上限是“拒绝/截断”策略的依据：文件超过 [MAX_SOURCE_FILE_BYTES] 直接拒绝；
 * 抽出的正文超过 [MAX_TEXT_CHARS] 只保留前面部分并标记 truncated（不静默丢）。
 */
object KnowledgeLimits {
    /** 单个来源文件上限 10 MiB（读取时按字节硬卡，不信任 content provider 声明的大小）。 */
    const val MAX_SOURCE_FILE_BYTES: Long = 10L * 1024 * 1024

    /** 单篇文档参与索引的正文上限 1 MiB 字符。 */
    const val MAX_TEXT_CHARS: Int = 1_048_576

    /** 每段目标长度；chunk 不会超过这个值。 */
    const val CHUNK_TARGET_CHARS: Int = 1_200

    /** 相邻段之间的重叠，保证跨段命中的句子仍能被检索到。 */
    const val CHUNK_OVERLAP_CHARS: Int = 150

    /** 段长硬上限（断言/校验用）。 */
    const val CHUNK_HARD_MAX_CHARS: Int = 1_500

    /** 单篇文档最多切多少段，防止极端文件把索引撑爆。 */
    const val MAX_CHUNKS_PER_DOC: Int = 4_000

    const val SEARCH_DEFAULT_LIMIT: Int = 6
    const val SEARCH_MAX_LIMIT: Int = 20

    /** 自动注入上下文的默认字符预算。 */
    const val CONTEXT_DEFAULT_MAX_CHARS: Int = 6_000

    const val SNIPPET_MAX_CHARS: Int = 240

    /** 检索候选上限：先按 FTS 取一批，再在 Kotlin 里打分排序。 */
    const val SEARCH_CANDIDATE_LIMIT: Int = 200

    /** 查询最多取多少个 token 参与匹配（防止把整句长文直接丢给 FTS）。 */
    const val MAX_QUERY_TOKENS: Int = 24

    /** 单次 read/工具返回的字符上限。 */
    const val READ_DEFAULT_MAX_CHARS: Int = 4_000
}

enum class KnowledgeFormat { TEXT, MARKDOWN, CSV, JSON, HTML, PDF, DOCX, PPTX, EPUB }

enum class KnowledgeSourceType { PASTED, SAF_FILE }

/** 检索实现模式；FTS4 不可用时退化为有上限的扫描，并在 UI 里如实显示。 */
enum class KnowledgeSearchMode { FTS4, LIMITED_SCAN }

data class KnowledgeCapabilities(
    val mode: KnowledgeSearchMode,
    val schemaVersion: Int,
)

data class KnowledgeDocSummary(
    val id: String,
    val assistantId: String,
    val title: String,
    val sourceType: KnowledgeSourceType,
    val sourceName: String?,
    val sourceUri: String?,
    val format: KnowledgeFormat,
    /** 归一化正文的 sha256，用于“同助手同内容”去重。 */
    val contentHash: String,
    val sizeBytes: Long,
    val charCount: Int,
    val chunkCount: Int,
    val revision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val truncated: Boolean,
)

data class KnowledgeSearchHit(
    val docId: String,
    val chunkId: String,
    val ordinal: Int,
    val title: String,
    val sourceName: String?,
    val format: KnowledgeFormat,
    val score: Double,
    val snippet: String,
    val startOffset: Int,
    val endOffset: Int,
    val updatedAt: Instant,
    /** 引用可追溯性：内容 hash 随命中一起返回。 */
    val contentHash: String,
)

data class KnowledgeReadResult(
    val doc: KnowledgeDocSummary,
    val chunkId: String?,
    val ordinal: Int?,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val truncated: Boolean,
)

sealed interface KnowledgeRejectReason {
    data object EmptyText : KnowledgeRejectReason
    data object EmptyDocument : KnowledgeRejectReason
    data object TooLarge : KnowledgeRejectReason
    data class UnsupportedFormat(val supportedExtensions: List<String>) : KnowledgeRejectReason
    data object Cancelled : KnowledgeRejectReason
    data class Failed(val message: String) : KnowledgeRejectReason
}

sealed interface KnowledgeImportResult {
    data class Imported(val doc: KnowledgeDocSummary) : KnowledgeImportResult
    data class Duplicate(val doc: KnowledgeDocSummary) : KnowledgeImportResult
    data class Rejected(val reason: KnowledgeRejectReason) : KnowledgeImportResult

    val docOrNull: KnowledgeDocSummary?
        get() = when (this) {
            is Imported -> doc
            is Duplicate -> doc
            is Rejected -> null
        }
}

/** 助手隔离被破坏时抛出，调用方不允许把另一个助手的文档读/删/搜出来。 */
class KnowledgeScopeViolationException(message: String) : IllegalArgumentException(message)

/**
 * 作用域校验。仓库里每条 SQL 都带 `assistant_id = ?`，这里再做一层显式校验：
 * 一是防止调用方把 Uuid 拼错，二是让“跨助手读取”在代码层面直接失败而不是返回空。
 *
 * 共享库尚未实现：[SHARED_SCOPE_ID] 只是给未来 `includeShared` 预留的保留 id，
 * 现在传入会被拒绝，也不会返回任何其他助手的内容。
 */
object KnowledgeScope {
    const val SHARED_SCOPE_ID: String = "shared"

    fun requireAssistant(assistantId: String): String {
        require(assistantId.isNotBlank()) { "assistantId must not be blank" }
        require(assistantId != SHARED_SCOPE_ID) { "shared knowledge scope is not implemented yet" }
        return assistantId
    }

    fun requireOwned(assistantId: String, ownerAssistantId: String) {
        if (assistantId != ownerAssistantId) {
            throw KnowledgeScopeViolationException(
                "knowledge document belongs to another assistant scope"
            )
        }
    }
}
