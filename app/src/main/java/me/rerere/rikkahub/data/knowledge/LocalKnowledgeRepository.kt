package me.rerere.rikkahub.data.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

private const val TAG = "LocalKnowledgeRepo"
private const val DB_NAME = "local_knowledge.db"
private const val SCHEMA_VERSION = 1
private const val SOURCE_DIR = "knowledge/sources"

internal object KnowledgeDbSql {
    const val CREATE_DOC = """CREATE TABLE IF NOT EXISTS knowledge_doc (
        id TEXT NOT NULL PRIMARY KEY,
        assistant_id TEXT NOT NULL,
        title TEXT NOT NULL,
        source_type TEXT NOT NULL,
        source_name TEXT,
        source_uri TEXT,
        source_format TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        char_count INTEGER NOT NULL,
        chunk_count INTEGER NOT NULL,
        revision INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        truncated INTEGER NOT NULL,
        local_copy TEXT,
        original_text TEXT NOT NULL
    )"""

    const val UNIQUE_SCOPE_HASH =
        "CREATE UNIQUE INDEX IF NOT EXISTS knowledge_doc_scope_hash " +
            "ON knowledge_doc(assistant_id, content_hash)"
    const val IDX_DOC_SCOPE_UPDATED =
        "CREATE INDEX IF NOT EXISTS knowledge_doc_scope_updated " +
            "ON knowledge_doc(assistant_id, updated_at DESC)"

    const val CREATE_CHUNK = """CREATE TABLE IF NOT EXISTS knowledge_chunk (
        id TEXT NOT NULL PRIMARY KEY,
        doc_id TEXT NOT NULL,
        assistant_id TEXT NOT NULL,
        ordinal INTEGER NOT NULL,
        start_offset INTEGER NOT NULL,
        end_offset INTEGER NOT NULL,
        char_count INTEGER NOT NULL,
        text TEXT NOT NULL,
        tokens TEXT NOT NULL
    )"""

    const val IDX_CHUNK_SCOPE_DOC =
        "CREATE INDEX IF NOT EXISTS knowledge_chunk_scope_doc " +
            "ON knowledge_chunk(assistant_id, doc_id, ordinal)"

    /** FTS4：Android 平台 SQLite 自带 FTS3/4（FTS5 不保证），所以优先 FTS4。 */
    const val CREATE_FTS =
        "CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts4(tokens, tokenize=simple)"

    /**
     * FTS4 虚表除 rowid 外都不能建索引，按助手过滤只能全扫；用普通表把 rowid 映射到
     * chunk/assistant，查候选时先按 scope 过滤，再回表取正文。
     */
    const val CREATE_FTS_MAP = """CREATE TABLE IF NOT EXISTS knowledge_fts_map (
        fts_rowid INTEGER NOT NULL PRIMARY KEY,
        chunk_id TEXT NOT NULL,
        doc_id TEXT NOT NULL,
        assistant_id TEXT NOT NULL
    )"""

    const val IDX_MAP_SCOPE =
        "CREATE INDEX IF NOT EXISTS knowledge_fts_map_scope " +
            "ON knowledge_fts_map(assistant_id, doc_id)"

    const val SELECT_DOC = "SELECT id, assistant_id, title, source_type, source_name, source_uri, " +
        "source_format, content_hash, size_bytes, char_count, chunk_count, revision, created_at, " +
        "updated_at, truncated, local_copy FROM knowledge_doc"

    const val SEARCH_FTS = """SELECT c.id, c.doc_id, c.ordinal, c.start_offset, c.end_offset, c.text,
            d.title, d.source_name, d.source_format, d.updated_at, d.content_hash
        FROM knowledge_fts
        JOIN knowledge_fts_map m ON m.fts_rowid = knowledge_fts.rowid
        JOIN knowledge_chunk c ON c.id = m.chunk_id
        JOIN knowledge_doc d ON d.id = c.doc_id
        WHERE knowledge_fts MATCH ? AND m.assistant_id = ? AND c.assistant_id = ? AND d.assistant_id = ?
        LIMIT ?"""
}

/**
 * 本地知识库仓库：独立 SQLite 文件（不碰 Room 的 schema 36），FTS4 全文检索 + 确定性中文分词。
 *
 * 设计约束（见 knowledge/CONTRACT.md）：
 * - 所有读写都按 [assistantId] 隔离，SQL 带 `assistant_id = ?`，越权读/删直接抛异常；
 * - 所有方法都是 `suspend` 且在 `Dispatchers.IO` 上执行：解析、分词、SQLite 都不在主线程；
 * - 写入是单事务：先删旧段/旧 FTS 行，再插新段；中途失败或取消都回滚，旧文档不会被半写；
 * - 不联网，不调用任何模型，原文与索引都留在本机。
 */
class LocalKnowledgeRepository(
    private val context: Context,
) {
    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var ftsAvailable: Boolean = false

    private val dbLock = Any()
    private val writeLock = Mutex()

    // ---------- 查询 ----------

    suspend fun list(assistantId: Uuid): List<KnowledgeDocSummary> = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        val database = database()
        database.rawQuery(
            "${KnowledgeDbSql.SELECT_DOC} WHERE assistant_id = ? ORDER BY updated_at DESC, id ASC",
            arrayOf(scope),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toDocSummary()) }
        }
    }

    suspend fun capabilities(): KnowledgeCapabilities = withContext(Dispatchers.IO) {
        database()
        KnowledgeCapabilities(
            mode = if (ftsAvailable) KnowledgeSearchMode.FTS4 else KnowledgeSearchMode.LIMITED_SCAN,
            schemaVersion = SCHEMA_VERSION,
        )
    }

    /**
     * 在当前助手的知识库里检索。`includeShared` 是给未来共享库预留的参数：
     * 共享库还没实现，传 true 也只返回本助手的内容，绝不会串到别的助手。
     */
    suspend fun search(
        assistantId: Uuid,
        query: String,
        limit: Int = KnowledgeLimits.SEARCH_DEFAULT_LIMIT,
        @Suppress("UNUSED_PARAMETER") includeShared: Boolean = false,
    ): List<KnowledgeSearchHit> = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        val tokens = KnowledgeTokenizer.queryTokens(query)
        if (tokens.isEmpty()) return@withContext emptyList()
        val cappedLimit = limit.coerceIn(1, KnowledgeLimits.SEARCH_MAX_LIMIT)
        val candidateLimit = (cappedLimit * 10).coerceIn(40, KnowledgeLimits.SEARCH_CANDIDATE_LIMIT)
        val candidates = queryCandidates(database(), scope, query, tokens, candidateLimit)
        val phrase = KnowledgeTextNormalizer.normalize(query).takeIf { it.length in 2..80 }
        candidates
            .map { row ->
                KnowledgeSearchHit(
                    docId = row.docId,
                    chunkId = row.chunkId,
                    ordinal = row.ordinal,
                    title = row.title,
                    sourceName = row.sourceName,
                    format = row.format,
                    score = KnowledgeScoring.score(row.title, row.text, tokens, phrase),
                    snippet = KnowledgeScoring.snippet(row.text, tokens),
                    startOffset = row.startOffset,
                    endOffset = row.endOffset,
                    updatedAt = row.updatedAt,
                    contentHash = row.contentHash,
                )
            }
            .filter { it.score > 0.0 }
            .sortedWith(
                compareByDescending<KnowledgeSearchHit> { it.score }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.chunkId }
            )
            .take(cappedLimit)
    }

    /**
     * 自动注入用的结构化片段。返回 null 表示没有命中，调用方不需要注入任何内容。
     * 每轮对话只应检索“最新一条用户文本”，结果由调用方按轮缓存，避免重复检索。
     */
    suspend fun buildKnowledgeContext(
        assistantId: Uuid,
        query: String,
        maxChars: Int = KnowledgeLimits.CONTEXT_DEFAULT_MAX_CHARS,
    ): String? {
        val hits = search(assistantId, query, limit = KnowledgeLimits.SEARCH_DEFAULT_LIMIT)
        return KnowledgeCitationFormat.build(hits, maxChars)
    }

    /**
     * 读取文档原文或其中一段。`chunkId == null` 时返回整篇（编辑用），
     * 传 chunkId 时返回该段并带 start/end 偏移，便于在原文里定位。
     * 返回 null 表示这个助手下没有该文档/该段。
     */
    suspend fun read(
        assistantId: Uuid,
        docId: String,
        chunkId: String? = null,
        maxChars: Int = KnowledgeLimits.READ_DEFAULT_MAX_CHARS,
    ): KnowledgeReadResult? = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        val database = database()
        val summary = docById(database, docId, scope) ?: return@withContext null
        val cap = maxChars.coerceIn(1, KnowledgeLimits.MAX_TEXT_CHARS)
        if (chunkId == null) {
            val text = database.rawQuery(
                "SELECT original_text FROM knowledge_doc WHERE id = ? AND assistant_id = ?",
                arrayOf(docId, scope),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                cursor.getString(0) ?: ""
            }
            val truncated = text.length > cap
            return@withContext KnowledgeReadResult(
                doc = summary,
                chunkId = null,
                ordinal = null,
                startOffset = 0,
                endOffset = summary.charCount,
                text = if (truncated) text.take(cap) else text,
                truncated = truncated,
            )
        }
        val row = database.rawQuery(
            """SELECT id, ordinal, start_offset, end_offset, text FROM knowledge_chunk
               WHERE id = ? AND doc_id = ? AND assistant_id = ? LIMIT 1""",
            arrayOf(chunkId, docId, scope),
        ).use { cursor ->
            if (!cursor.moveToNext()) null else
                ChunkRow(
                    chunkId = cursor.getString(0),
                    ordinal = cursor.getInt(1),
                    startOffset = cursor.getInt(2),
                    endOffset = cursor.getInt(3),
                    text = cursor.getString(4) ?: "",
                )
        } ?: return@withContext null
        val truncated = row.text.length > cap
        KnowledgeReadResult(
            doc = summary,
            chunkId = row.chunkId,
            ordinal = row.ordinal,
            startOffset = row.startOffset,
            endOffset = row.endOffset,
            text = if (truncated) row.text.take(cap) else row.text,
            truncated = truncated,
        )
    }

    // ---------- 写入 ----------

    suspend fun importText(
        assistantId: Uuid,
        title: String,
        text: String,
        sourceName: String? = null,
    ): KnowledgeImportResult = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        currentCoroutineContext().ensureActive()
        if (text.isBlank()) return@withContext rejected(KnowledgeRejectReason.EmptyText)
        if (text.length > KnowledgeLimits.MAX_TEXT_CHARS) {
            return@withContext rejected(KnowledgeRejectReason.TooLarge)
        }
        val normalized = KnowledgeTextNormalizer.normalize(text)
        if (normalized.isBlank()) return@withContext rejected(KnowledgeRejectReason.EmptyDocument)
        val prepared = prepare(
            title = title,
            normalized = normalized,
            format = KnowledgeFormats.formatOf(sourceName, null) ?: KnowledgeFormat.TEXT,
            sourceType = KnowledgeSourceType.PASTED,
            sourceName = sourceName,
            sourceUri = null,
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            truncated = false,
            localCopy = null,
        ) ?: return@withContext rejected(KnowledgeRejectReason.TooLarge)
        persistSafely(scope, prepared, existingDocId = null)
    }

    /** 编辑：同一 docId 下原子替换全文与索引，revision + 1；旧内容在事务成功前一直可用。 */
    suspend fun updateText(
        assistantId: Uuid,
        docId: String,
        title: String,
        text: String,
    ): KnowledgeImportResult = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        val database = database()
        val existing = docById(database, docId, scope)
            ?: return@withContext rejected(KnowledgeRejectReason.Failed("文档不存在或不属于当前助手"))
        if (text.isBlank()) return@withContext rejected(KnowledgeRejectReason.EmptyText)
        if (text.length > KnowledgeLimits.MAX_TEXT_CHARS) {
            return@withContext rejected(KnowledgeRejectReason.TooLarge)
        }
        val normalized = KnowledgeTextNormalizer.normalize(text)
        if (normalized.isBlank()) return@withContext rejected(KnowledgeRejectReason.EmptyDocument)
        val prepared = prepare(
            title = title.ifBlank { existing.title },
            normalized = normalized,
            format = existing.format,
            sourceType = existing.sourceType,
            sourceName = existing.sourceName,
            sourceUri = existing.sourceUri,
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            truncated = false,
            localCopy = null,
        ) ?: return@withContext rejected(KnowledgeRejectReason.TooLarge)
        persistSafely(scope, prepared, existingDocId = docId)
    }

    suspend fun importUri(
        assistantId: Uuid,
        uri: Uri,
        displayName: String? = null,
    ): KnowledgeImportResult = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        val resolver = context.contentResolver
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "import" }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val format = KnowledgeFormats.formatOf(name, mime)
            ?: return@withContext rejected(KnowledgeFormats.unsupportedReason())
        val declaredSize = querySize(uri)
        if (declaredSize != null && declaredSize > KnowledgeLimits.MAX_SOURCE_FILE_BYTES) {
            return@withContext rejected(KnowledgeRejectReason.TooLarge)
        }
        val temp = File(context.cacheDir, "knowledge-import-${Uuid.random()}.tmp")
        try {
            copyLimited(uri, temp)
            currentCoroutineContext().ensureActive()
            val extracted = try {
                KnowledgeTextExtractor.extract(temp, name, mime, format)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "extract failed for $name", e)
                return@withContext rejected(
                    KnowledgeRejectReason.Failed(e.message ?: "无法解析文件内容")
                )
            }
            val normalized = KnowledgeTextNormalizer.normalize(extracted.text)
            if (normalized.isBlank()) return@withContext rejected(KnowledgeRejectReason.EmptyDocument)
            val sourceDir = File(context.filesDir, SOURCE_DIR).apply { mkdirs() }
            val prepared = prepare(
                title = name.substringBeforeLast('.', name),
                normalized = normalized,
                format = format,
                sourceType = KnowledgeSourceType.SAF_FILE,
                sourceName = name,
                sourceUri = uri.toString(),
                sizeBytes = temp.length(),
                truncated = extracted.truncated,
                localCopyStaging = temp,
                sourceDir = sourceDir,
            ) ?: return@withContext rejected(KnowledgeRejectReason.TooLarge)
            persistSafely(scope, prepared, existingDocId = null)
        } catch (e: SourceTooLargeException) {
            rejected(KnowledgeRejectReason.TooLarge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import failed for $name", e)
            rejected(KnowledgeRejectReason.Failed(e.message ?: "导入失败"))
        } finally {
            // 原文副本已 rename 走时这里 delete 是 no-op
            if (temp.exists()) temp.delete()
        }
    }

    /** 删除文档、全部分段、FTS 行和本地副本；返回 false 表示这个助手下没有该文档。 */
    suspend fun delete(assistantId: Uuid, docId: String): Boolean = withContext(Dispatchers.IO) {
        val scope = KnowledgeScope.requireAssistant(assistantId.toString())
        writeLock.withLock {
            val database = database()
            docById(database, docId, scope) ?: return@withLock false
            val previousCopy = localCopyOf(database, docId, scope)
            database.beginTransactionNonExclusive()
            try {
                deleteFtsRows(database, docId, scope)
                database.delete(
                    "knowledge_chunk",
                    "doc_id = ? AND assistant_id = ?",
                    arrayOf(docId, scope),
                )
                val removed = database.delete(
                    "knowledge_doc",
                    "id = ? AND assistant_id = ?",
                    arrayOf(docId, scope),
                )
                check(removed <= 1) { "deleted more than one knowledge document" }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            previousCopy?.let { runCatching { File(it).delete() } }
            true
        }
    }

    /** 测试/退出用；生产是 Koin singleton，不主动 close。 */
    fun close() {
        synchronized(dbLock) {
            db?.close()
            db = null
        }
    }

    // ---------- 准备与落库 ----------

    private data class PreparedChunk(val spec: KnowledgeChunkSpec, val tokens: String)

    private data class PreparedDocument(
        val title: String,
        val text: String,
        val contentHash: String,
        val chunks: List<PreparedChunk>,
        val format: KnowledgeFormat,
        val sourceType: KnowledgeSourceType,
        val sourceName: String?,
        val sourceUri: String?,
        val sizeBytes: Long,
        val truncated: Boolean,
        val localCopy: File?,
    )

    private data class ChunkRow(
        val chunkId: String,
        val ordinal: Int,
        val startOffset: Int,
        val endOffset: Int,
        val text: String,
    )

    private data class CandidateRow(
        val chunkId: String,
        val docId: String,
        val ordinal: Int,
        val startOffset: Int,
        val endOffset: Int,
        val text: String,
        val title: String,
        val sourceName: String?,
        val format: KnowledgeFormat,
        val updatedAt: Instant,
        val contentHash: String,
    )

    private fun prepare(
        title: String,
        normalized: String,
        format: KnowledgeFormat,
        sourceType: KnowledgeSourceType,
        sourceName: String?,
        sourceUri: String?,
        sizeBytes: Long,
        truncated: Boolean,
        localCopy: File? = null,
        localCopyStaging: File? = null,
        sourceDir: File? = null,
    ): PreparedDocument? {
        val chunks = KnowledgeChunker.split(normalized)
        if (chunks.isEmpty() || chunks.size > KnowledgeLimits.MAX_CHUNKS_PER_DOC) return null
        val preparedChunks = chunks.map {
            PreparedChunk(it, KnowledgeTokenizer.indexText(it.text))
        }
        val finalCopy = when {
            localCopy != null -> localCopy
            localCopyStaging != null && sourceDir != null -> {
                val extension = sourceName?.substringAfterLast('.', "")?.lowercase().orEmpty()
                val name = "src-${Uuid.random()}${
                    if (extension.isBlank()) "" else ".$extension"
                }"
                val target = File(sourceDir, name)
                if (!localCopyStaging.renameTo(target)) {
                    localCopyStaging.copyTo(target, overwrite = true)
                    localCopyStaging.delete()
                }
                target
            }

            else -> null
        }
        return PreparedDocument(
            title = title.trim().ifBlank { defaultTitle(sourceName, format) },
            text = normalized,
            contentHash = KnowledgeHash.sha256Hex(normalized),
            chunks = preparedChunks,
            format = format,
            sourceType = sourceType,
            sourceName = sourceName,
            sourceUri = sourceUri,
            sizeBytes = sizeBytes,
            truncated = truncated,
            localCopy = finalCopy,
        )
    }

    /** 落库失败不能把异常抛到 UI：失败时旧文档仍然完整，调用方拿到可展示的 Rejected。 */
    private suspend fun persistSafely(
        scope: String,
        prepared: PreparedDocument,
        existingDocId: String?,
    ): KnowledgeImportResult = try {
        persistPrepared(scope, prepared, existingDocId)
    } catch (e: CancellationException) {
        prepared.localCopy?.delete()
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "knowledge persist failed", e)
        prepared.localCopy?.delete()
        rejected(KnowledgeRejectReason.Failed(e.message ?: "无法写入本地索引"))
    }

    private suspend fun persistPrepared(
        scope: String,
        prepared: PreparedDocument,
        existingDocId: String?,
    ): KnowledgeImportResult = writeLock.withLock {
        val database = database()
        val duplicate = findByHash(database, scope, prepared.contentHash)
        if (duplicate != null && duplicate.id != existingDocId) {
            prepared.localCopy?.delete()
            KnowledgeImportResult.Duplicate(duplicate)
        } else {
            try {
                KnowledgeImportResult.Imported(
                    writePrepared(database, scope, prepared, existingDocId)
                )
            } catch (e: SQLiteConstraintException) {
                // 并发导入同一内容：唯一索引兜底，回读已有文档
                prepared.localCopy?.delete()
                val existing = findByHash(database, scope, prepared.contentHash)
                if (existing != null) KnowledgeImportResult.Duplicate(existing) else throw e
            }
        }
    }

    private fun writePrepared(
        database: SQLiteDatabase,
        scope: String,
        prepared: PreparedDocument,
        existingDocId: String?,
    ): KnowledgeDocSummary {
        val now = Instant.now()
        val docId = existingDocId ?: Uuid.random().toString()
        val previous = existingDocId?.let { docById(database, it, scope) }
        val previousCopy = existingDocId?.let { localCopyOf(database, it, scope) }
        val values = ContentValues().apply {
            put("id", docId)
            put("assistant_id", scope)
            put("title", prepared.title)
            put("source_type", prepared.sourceType.name)
            put("source_name", prepared.sourceName)
            put("source_uri", prepared.sourceUri)
            put("source_format", prepared.format.name)
            put("content_hash", prepared.contentHash)
            put("size_bytes", prepared.sizeBytes)
            put("char_count", prepared.text.length)
            put("chunk_count", prepared.chunks.size)
            put("revision", (previous?.revision ?: 0) + 1)
            put("created_at", (previous?.createdAt ?: now).toEpochMilli())
            put("updated_at", now.toEpochMilli())
            put("truncated", if (prepared.truncated) 1 else 0)
            put("local_copy", prepared.localCopy?.absolutePath)
            put("original_text", prepared.text)
        }
        database.beginTransactionNonExclusive()
        try {
            deleteFtsRows(database, docId, scope)
            database.delete("knowledge_chunk", "doc_id = ? AND assistant_id = ?", arrayOf(docId, scope))
            if (previous == null) {
                database.insertOrThrow("knowledge_doc", null, values)
            } else {
                val updated = database.update(
                    "knowledge_doc",
                    values,
                    "id = ? AND assistant_id = ?",
                    arrayOf(docId, scope),
                )
                check(updated == 1) { "knowledge document update hit $updated rows" }
            }
            for (chunk in prepared.chunks) {
                val chunkId = Uuid.random().toString()
                database.insertOrThrow("knowledge_chunk", null, ContentValues().apply {
                    put("id", chunkId)
                    put("doc_id", docId)
                    put("assistant_id", scope)
                    put("ordinal", chunk.spec.ordinal)
                    put("start_offset", chunk.spec.start)
                    put("end_offset", chunk.spec.end)
                    put("char_count", chunk.spec.text.length)
                    put("text", chunk.spec.text)
                    put("tokens", chunk.tokens)
                })
                if (ftsAvailable) {
                    val ftsRowid = database.insertOrThrow(
                        "knowledge_fts",
                        null,
                        ContentValues().apply { put("tokens", chunk.tokens) },
                    )
                    database.insertOrThrow("knowledge_fts_map", null, ContentValues().apply {
                        put("fts_rowid", ftsRowid)
                        put("chunk_id", chunkId)
                        put("doc_id", docId)
                        put("assistant_id", scope)
                    })
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // 事务成功后才删旧副本，失败/取消时旧文档和旧副本都还在
        previousCopy
            ?.takeIf { it != prepared.localCopy?.absolutePath }
            ?.let { runCatching { File(it).delete() } }
        return docById(database, docId, scope)
            ?: error("knowledge document vanished after commit")
    }

    private fun deleteFtsRows(database: SQLiteDatabase, docId: String, scope: String) {
        if (!ftsAvailable) return
        database.execSQL(
            "DELETE FROM knowledge_fts WHERE rowid IN " +
                "(SELECT fts_rowid FROM knowledge_fts_map WHERE doc_id = ? AND assistant_id = ?)",
            arrayOf(docId, scope),
        )
        database.delete(
            "knowledge_fts_map",
            "doc_id = ? AND assistant_id = ?",
            arrayOf(docId, scope),
        )
    }

    // ---------- 候选查询 ----------

    private fun queryCandidates(
        database: SQLiteDatabase,
        scope: String,
        query: String,
        tokens: List<String>,
        limit: Int,
    ): List<CandidateRow> {
        if (ftsAvailable) {
            val match = KnowledgeTokenizer.ftsMatchQuery(query) ?: return emptyList()
            val rows = runCatching {
                database.rawQuery(
                    KnowledgeDbSql.SEARCH_FTS,
                    arrayOf(match, scope, scope, scope, limit.toString()),
                ).use { cursor -> cursor.toCandidates() }
            }.getOrElse { error ->
                Log.w(TAG, "FTS query failed, falling back to limited scan", error)
                emptyList()
            }
            if (rows.isNotEmpty()) return rows
        }
        return scanCandidates(database, scope, tokens, limit)
    }

    /** FTS4 不可用时的退化路径：按 token 做有上限的扫描，不静默占用主线程/全表无界。 */
    private fun scanCandidates(
        database: SQLiteDatabase,
        scope: String,
        tokens: List<String>,
        limit: Int,
    ): List<CandidateRow> {
        if (tokens.isEmpty()) return emptyList()
        val patterns = tokens.take(8)
        val where = patterns.joinToString(" OR ") {
            "(' ' || c.tokens || ' ') LIKE ?"
        }
        val args = buildList {
            add(scope)
            add(scope)
            patterns.forEach { add("% $it %") }
            add(limit.toString())
        }.toTypedArray()
        return database.rawQuery(
            """SELECT c.id, c.doc_id, c.ordinal, c.start_offset, c.end_offset, c.text,
                      d.title, d.source_name, d.source_format, d.updated_at, d.content_hash
               FROM knowledge_chunk c
               JOIN knowledge_doc d ON d.id = c.doc_id
               WHERE c.assistant_id = ? AND d.assistant_id = ? AND ($where)
               LIMIT ?""",
            args,
        ).use { cursor -> cursor.toCandidates() }
    }

    private fun Cursor.toCandidates(): List<CandidateRow> = buildList {
        while (moveToNext()) {
            add(
                CandidateRow(
                    chunkId = getString(0),
                    docId = getString(1),
                    ordinal = getInt(2),
                    startOffset = getInt(3),
                    endOffset = getInt(4),
                    text = getString(5) ?: "",
                    title = getString(6) ?: "",
                    sourceName = if (isNull(7)) null else getString(7),
                    format = runCatching { KnowledgeFormat.valueOf(getString(8)) }
                        .getOrDefault(KnowledgeFormat.TEXT),
                    updatedAt = Instant.ofEpochMilli(getLong(9)),
                    contentHash = getString(10) ?: "",
                )
            )
        }
    }

    // ---------- 文档读取 ----------

    private fun docById(database: SQLiteDatabase, docId: String, scope: String): KnowledgeDocSummary? =
        database.rawQuery(
            "${KnowledgeDbSql.SELECT_DOC} WHERE id = ? AND assistant_id = ? LIMIT 1",
            arrayOf(docId, scope),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocSummary() else null }

    private fun findByHash(
        database: SQLiteDatabase,
        scope: String,
        hash: String,
    ): KnowledgeDocSummary? = database.rawQuery(
        "${KnowledgeDbSql.SELECT_DOC} WHERE assistant_id = ? AND content_hash = ? LIMIT 1",
        arrayOf(scope, hash),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocSummary() else null }

    private fun Cursor.toDocSummary(): KnowledgeDocSummary = KnowledgeDocSummary(
        id = getString(0),
        assistantId = getString(1),
        title = getString(2) ?: "",
        sourceType = runCatching { KnowledgeSourceType.valueOf(getString(3)) }
            .getOrDefault(KnowledgeSourceType.PASTED),
        sourceName = if (isNull(4)) null else getString(4),
        sourceUri = if (isNull(5)) null else getString(5),
        format = runCatching { KnowledgeFormat.valueOf(getString(6)) }
            .getOrDefault(KnowledgeFormat.TEXT),
        contentHash = getString(7) ?: "",
        sizeBytes = getLong(8),
        charCount = getInt(9),
        chunkCount = getInt(10),
        revision = getInt(11),
        createdAt = Instant.ofEpochMilli(getLong(12)),
        updatedAt = Instant.ofEpochMilli(getLong(13)),
        truncated = getInt(14) != 0,
    )

    private fun localCopyOf(database: SQLiteDatabase, docId: String, scope: String): String? =
        database.rawQuery(
            "SELECT local_copy FROM knowledge_doc WHERE id = ? AND assistant_id = ? LIMIT 1",
            arrayOf(docId, scope),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    // ---------- 数据库 ----------

    private fun database(): SQLiteDatabase {
        db?.let { return it }
        synchronized(dbLock) {
            db?.let { return it }
            val opened = openDatabase()
            db = opened
            return opened
        }
    }

    private fun openDatabase(): SQLiteDatabase {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        database.execSQL("PRAGMA synchronous=NORMAL")
        database.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        database.execSQL(KnowledgeDbSql.CREATE_DOC)
        database.execSQL(KnowledgeDbSql.UNIQUE_SCOPE_HASH)
        database.execSQL(KnowledgeDbSql.IDX_DOC_SCOPE_UPDATED)
        database.execSQL(KnowledgeDbSql.CREATE_CHUNK)
        database.execSQL(KnowledgeDbSql.IDX_CHUNK_SCOPE_DOC)
        database.execSQL(KnowledgeDbSql.CREATE_FTS_MAP)
        database.execSQL(KnowledgeDbSql.IDX_MAP_SCOPE)
        ftsAvailable = try {
            database.execSQL(KnowledgeDbSql.CREATE_FTS)
            // 老库里可能残留不可用的虚表，确认能用再标记
            database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'knowledge_fts'",
                null,
            ).use { cursor -> cursor.moveToFirst() && !cursor.isNull(0) && cursor.getString(0) != null }
        } catch (e: SQLiteException) {
            Log.w(TAG, "FTS4 unavailable, knowledge search degrades to limited scan", e)
            false
        }
        if (database.version < SCHEMA_VERSION) database.version = SCHEMA_VERSION
        return database
    }

    // ---------- content resolver helpers ----------

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    private fun copyLimited(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开所选文件")
        input.use { copyLimited(it, target) }
    }

    /** 按字节硬卡 10 MiB：不信任 provider 声明的 SIZE，读的时候就停。 */
    private fun copyLimited(input: InputStream, target: File) {
        var total = 0L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > KnowledgeLimits.MAX_SOURCE_FILE_BYTES) throw SourceTooLargeException()
                output.write(buffer, 0, read)
            }
        }
    }

    private fun defaultTitle(sourceName: String?, format: KnowledgeFormat): String {
        val fromName = sourceName?.substringBeforeLast('.', "")?.trim().orEmpty()
        if (fromName.isNotBlank()) return fromName
        return if (format == KnowledgeFormat.TEXT) "粘贴文本 ${LocalDate.now()}" else "导入文档"
    }

    private fun rejected(reason: KnowledgeRejectReason): KnowledgeImportResult =
        KnowledgeImportResult.Rejected(reason)

    private class SourceTooLargeException : Exception("文件超过 10 MiB")
}
