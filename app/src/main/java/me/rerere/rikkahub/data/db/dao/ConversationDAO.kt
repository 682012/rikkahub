package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity

/**
 * 折叠查询的约定（[getCollapsedUnfiledPaging] / [getCollapsedFolderPaging]）：
 *
 * - 家族 = 同助手 + 同文件夹 + 同根（`COALESCE(NULLIF(lineage_id, ''), id)`）。空 lineage 就是旧数据、
 *   自己当根，所以不必回填老行也能分组。
 * - 置顶的会话每条单独露出来，不参与折叠：用户置顶就是为了让它一直在顶上。
 * - 未置顶的家族只留一条代表：`update_at` 最新的那条；时间完全相同（比如同一次批量导入）时用 `id`
 *   字典序打破并列，保证分页顺序稳定、不会漏行或重复。
 * - `familySize` 只数当前 scope 里未置顶的家族成员；单条会话就是 1。置顶行也带上同家族的成员数，
 *   方便卡片上显示角标（没有未置顶成员时是 0）。
 * - 搜索和普通列表不折叠，仍然按原来的查询走。
 *
 * `lineageId` 这一列在两组查询里含义不同，别混用：
 * - 普通/搜索/摘要查询返回表里的原值：旧数据是空串（= 自己是根），和 [ConversationEntity] 一致，
 *   这样「读出来再按原位比对写回」的乐观并发判断不会被凭空改写。
 * - 折叠查询和家族列表返回**有效的家族根** `COALESCE(NULLIF(lineage_id, ''), id)`：界面拿它直接去查
 *   家族列表，不用自己判空，也不会误用某个分叉自己的 id。
 */
@Dao
interface ConversationDAO {
    @Query("SELECT assistant_id FROM ConversationEntity WHERE id = :id")
    suspend fun getAssistantId(id: String): String?

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    // Organizer only needs lightweight metadata, including chats whose assistant was removed.
    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun observeAllSummaries(): Flow<List<LightConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun observeAssistantSummaries(assistantId: String): Flow<List<LightConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT title FROM conversationentity WHERE assistant_id = :assistantId")
    suspend fun getTitlesOfAssistant(assistantId: String): List<String>

    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC"
    )
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' ORDER BY is_pinned DESC, CASE WHEN :sortByCreateTime THEN create_at ELSE update_at END DESC"
    )
    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE folder_id = :folderId ORDER BY is_pinned DESC, CASE WHEN :sortByCreateTime THEN create_at ELSE update_at END DESC"
    )
    fun getConversationsOfFolderPaging(
        folderId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    /** 未归档会话，按家族折叠；置顶的每条单独出。 */
    @Query(
        """
        SELECT
            s.id AS id,
            s.assistant_id AS assistantId,
            s.title AS title,
            s.is_pinned AS isPinned,
            s.create_at AS createAt,
            s.update_at AS updateAt,
            s.folder_id AS folderId,
            COALESCE(NULLIF(s.lineage_id, ''), s.id) AS lineageId,
            s.folder_assignment_locked AS folderAssignmentLocked,
            (SELECT COUNT(*) FROM conversationentity f
                WHERE f.assistant_id = s.assistant_id
                    AND f.folder_id = s.folder_id
                    AND f.is_pinned = 0
                    AND COALESCE(NULLIF(f.lineage_id, ''), f.id) = COALESCE(NULLIF(s.lineage_id, ''), s.id)
            ) AS familySize
        FROM conversationentity s
        WHERE s.assistant_id = :assistantId
            AND s.folder_id = ''
            AND (
                s.is_pinned = 1
                OR NOT EXISTS (
                    SELECT 1 FROM conversationentity o
                    WHERE o.assistant_id = s.assistant_id
                        AND o.folder_id = s.folder_id
                        AND o.is_pinned = 0
                        AND COALESCE(NULLIF(o.lineage_id, ''), o.id) = COALESCE(NULLIF(s.lineage_id, ''), s.id)
                        AND (o.update_at > s.update_at OR (o.update_at = s.update_at AND o.id > s.id))
                )
            )
        ORDER BY s.is_pinned DESC,
            CASE WHEN :sortByCreateTime THEN s.create_at ELSE s.update_at END DESC,
            s.id DESC
        """
    )
    fun getCollapsedUnfiledPaging(
        assistantId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    /** 某个文件夹里的会话，按家族折叠；助手和文件夹都限定，不串到别的助手。 */
    @Query(
        """
        SELECT
            s.id AS id,
            s.assistant_id AS assistantId,
            s.title AS title,
            s.is_pinned AS isPinned,
            s.create_at AS createAt,
            s.update_at AS updateAt,
            s.folder_id AS folderId,
            COALESCE(NULLIF(s.lineage_id, ''), s.id) AS lineageId,
            s.folder_assignment_locked AS folderAssignmentLocked,
            (SELECT COUNT(*) FROM conversationentity f
                WHERE f.assistant_id = s.assistant_id
                    AND f.folder_id = s.folder_id
                    AND f.is_pinned = 0
                    AND COALESCE(NULLIF(f.lineage_id, ''), f.id) = COALESCE(NULLIF(s.lineage_id, ''), s.id)
            ) AS familySize
        FROM conversationentity s
        WHERE s.assistant_id = :assistantId
            AND s.folder_id = :folderId
            AND (
                s.is_pinned = 1
                OR NOT EXISTS (
                    SELECT 1 FROM conversationentity o
                    WHERE o.assistant_id = s.assistant_id
                        AND o.folder_id = s.folder_id
                        AND o.is_pinned = 0
                        AND COALESCE(NULLIF(o.lineage_id, ''), o.id) = COALESCE(NULLIF(s.lineage_id, ''), s.id)
                        AND (o.update_at > s.update_at OR (o.update_at = s.update_at AND o.id > s.id))
                )
            )
        ORDER BY s.is_pinned DESC,
            CASE WHEN :sortByCreateTime THEN s.create_at ELSE s.update_at END DESC,
            s.id DESC
        """
    )
    fun getCollapsedFolderPaging(
        assistantId: String,
        folderId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    /**
     * 一个家族的全部成员（供「点开折叠卡片」后的列表用）。
     *
     * 严格同助手、同文件夹、同根：别的助手里就算有相同 lineage 也不带出来。置顶的成员照样出现（用户在
     * 这里看到完整的家族），排序仍是置顶优先、再按 [sortByCreateTime]。
     */
    @Query(
        """
        SELECT
            s.id AS id,
            s.assistant_id AS assistantId,
            s.title AS title,
            s.is_pinned AS isPinned,
            s.create_at AS createAt,
            s.update_at AS updateAt,
            s.folder_id AS folderId,
            COALESCE(NULLIF(s.lineage_id, ''), s.id) AS lineageId,
            s.folder_assignment_locked AS folderAssignmentLocked,
            (SELECT COUNT(*) FROM conversationentity f
                WHERE f.assistant_id = s.assistant_id
                    AND f.folder_id = s.folder_id
                    AND f.is_pinned = 0
                    AND COALESCE(NULLIF(f.lineage_id, ''), f.id) = COALESCE(NULLIF(s.lineage_id, ''), s.id)
            ) AS familySize
        FROM conversationentity s
        WHERE s.assistant_id = :assistantId
            AND s.folder_id = :folderId
            AND COALESCE(NULLIF(s.lineage_id, ''), s.id) = :lineageId
        ORDER BY s.is_pinned DESC,
            CASE WHEN :sortByCreateTime THEN s.create_at ELSE s.update_at END DESC,
            s.id DESC
        """
    )
    fun getLineagePaging(
        assistantId: String,
        lineageId: String,
        folderId: String,
        sortByCreateTime: Boolean,
    ): PagingSource<Int, LightConversationEntity>

    @Query(
        "SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit"
    )
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query(
        "SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC"
    )
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC"
    )
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query(
        "SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC"
    )
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, lineage_id AS lineageId, folder_assignment_locked AS folderAssignmentLocked, 1 AS familySize FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC"
    )
    fun searchConversationsOfAssistantPaging(
        assistantId: String,
        searchText: String
    ): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET assistant_id = :assistantId, folder_id = '' WHERE id = :id")
    suspend fun updateAssistantId(id: String, assistantId: String)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    /** 只改家族根一列；带上 assistant_id 是为了不把别的助手的行改掉。 */
    @Query("UPDATE conversationentity SET lineage_id = :lineageId WHERE id = :id AND assistant_id = :assistantId")
    suspend fun updateLineageId(id: String, assistantId: String, lineageId: String)

    /** 只改归属和归库锁两列，不碰标题、消息和配置。 */
    @Query("UPDATE conversationentity SET folder_id = :folderId, folder_assignment_locked = :locked WHERE id = :id")
    suspend fun updateFolderAssignment(id: String, folderId: String, locked: Boolean)

    @Query("UPDATE conversationentity SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE conversationentity SET custom_system_prompt = :customSystemPrompt WHERE id = :id")
    suspend fun updateCustomSystemPrompt(id: String, customSystemPrompt: String)

    @Query("UPDATE conversationentity SET suggestions = :chatSuggestions WHERE id = :id")
    suspend fun updateChatSuggestions(id: String, chatSuggestions: String)

    @Query(
        "UPDATE conversationentity SET config = :config, mode_injection_ids = :modeInjectionIds, lorebook_ids = :lorebookIds, workspace_cwd = :workspaceCwd WHERE id = :id"
    )
    suspend fun updateConfig(
        id: String,
        config: String,
        modeInjectionIds: String,
        lorebookIds: String,
        workspaceCwd: String,
    )

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query("SELECT assistant_id AS assistantId, COUNT(*) AS count FROM conversationentity GROUP BY assistant_id")
    suspend fun countByAssistant(): List<AssistantConversationCount>

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

data class AssistantConversationCount(val assistantId: String, val count: Int)
