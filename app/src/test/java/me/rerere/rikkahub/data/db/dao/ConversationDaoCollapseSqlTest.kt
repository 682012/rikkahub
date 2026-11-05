package me.rerere.rikkahub.data.db.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 折叠查询的 SQL 守门。
 *
 * Room 的 `@Query` 只在编译期被解析，运行期看不出「家族根认错了」「置顶被折掉了」这类语义错误，
 * 而 JVM 单元测试又跑不了 Android 的 SQLite，所以这里直接读 DAO 源码做静态核对：家族键、置顶单出、
 * 代表选择与并列打破、familySize 只数未置顶、以及「同助手 + 同文件夹」的严格 scope。
 *
 * 这些 SQL 在真实 SQLite 上的行为（schema 35 下的执行结果、并列、跨助手/跨文件夹隔离、Room 分页包装）
 * 由 r21-agent-core/autofile/a-db-checks/check_db_r21_a.py 跑实际数据库验证。
 */
class ConversationDaoCollapseSqlTest {

    private val familyKeyAliased = "COALESCE(NULLIF(s.lineage_id, ''), s.id)"

    private fun daoSource(): String =
        listOf(
            File("src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("找不到 ConversationDAO.kt（cwd=${File(".").absolutePath}）")

    /**
     * 每个 `@Query` 的 SQL 原文（空白压平后），按紧随其后的函数名索引。
     *
     * 多行查询是 `"""..."""` 原样字符串，单行的是普通字符串，两种都要能取出来；取字面量而不是整段源码，
     * 这样 `assertEquals` 比对的是真正交给 Room 的 SQL。
     */
    private fun queries(): Map<String, String> = daoSource()
        .split("@Query(")
        .drop(1)
        .mapNotNull { raw ->
            val funMatch = Regex("""fun\s+(\w+)\s*\(""").find(raw) ?: return@mapNotNull null
            val head = raw.substring(0, funMatch.range.first)
            val literal = Regex("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(head)
                ?.let { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                ?: head
            funMatch.groupValues[1] to literal.replace(Regex("\\s+"), " ").trim()
        }
        .toMap()

    @Test
    fun `collapsed queries keep the documented family rules`() {
        val queries = queries()
        for (name in listOf("getCollapsedUnfiledPaging", "getCollapsedFolderPaging")) {
            val sql = queries.getValue(name)

            assertTrue("$name 用 COALESCE(NULLIF(lineage, ''), id) 作家族根", sql.contains(familyKeyAliased))
            assertTrue("$name 置顶的每条单独露出来", sql.contains("s.is_pinned = 1"))
            assertTrue("$name 未置顶的家族只留一条代表", sql.contains("NOT EXISTS"))
            assertTrue("$name 代表取 update_at 最新", sql.contains("o.update_at > s.update_at"))
            assertTrue(
                "$name 时间并列时用 id 打破，分页顺序才稳定",
                sql.contains("o.update_at = s.update_at AND o.id > s.id"),
            )
            assertTrue("$name familySize 只数未置顶成员", sql.contains("f.is_pinned = 0"))
            assertTrue(
                "$name familySize 与成员落在同一个家族键上",
                sql.contains("COALESCE(NULLIF(f.lineage_id, ''), f.id) = $familyKeyAliased"),
            )
            assertTrue("$name 限定助手", sql.contains("s.assistant_id = :assistantId"))
            assertTrue(
                "$name 家族成员也限定助手和文件夹，不跨会话串门",
                sql.contains("o.assistant_id = s.assistant_id") && sql.contains("o.folder_id = s.folder_id"),
            )
            assertTrue("$name 排序置顶优先", sql.contains("ORDER BY s.is_pinned DESC"))
            assertTrue("$name 用 id 兜底排序", sql.contains("s.id DESC"))
        }

        assertTrue(
            "未归档查询只看 folder_id = ''",
            queries.getValue("getCollapsedUnfiledPaging").contains("s.folder_id = ''"),
        )
        assertTrue(
            "文件夹查询同时限定助手和文件夹",
            queries.getValue("getCollapsedFolderPaging").contains("s.folder_id = :folderId"),
        )
    }

    @Test
    fun `no query relies on a CTE prefix or a window function`() {
        // Room 的分页会把查询包成 SELECT * FROM (<query>) LIMIT ? OFFSET ?；窗口函数要 SQLite 3.25
        // （Android 11）才有，而 minSdk 是 26，所以代表选择只能用相关子查询。
        for ((name, sql) in queries()) {
            assertFalse("$name 不要以 WITH 开头", sql.trim().uppercase().startsWith("WITH "))
            assertFalse("$name 不要用窗口函数", sql.contains("ROW_NUMBER("))
        }
    }

    @Test
    fun `lineage list is strictly one assistant one folder one family`() {
        val sql = queries().getValue("getLineagePaging")

        assertTrue(sql.contains("s.assistant_id = :assistantId"))
        assertTrue(sql.contains("s.folder_id = :folderId"))
        assertTrue(
            "家族键要和传入的根严格相等",
            sql.contains("$familyKeyAliased = :lineageId"),
        )
        assertFalse("家族列表里置顶成员照样出现，不折叠", sql.contains("NOT EXISTS"))
        assertTrue("家族列表仍然置顶优先", sql.contains("ORDER BY s.is_pinned DESC"))
    }

    @Test
    fun `light projections carry the new fields everywhere`() {
        val queries = queries()
        val lightQueries = listOf(
            "observeAllSummaries",
            "observeAssistantSummaries",
            "getConversationsOfAssistantPaging",
            "getUnfiledConversationsOfAssistantPaging",
            "getConversationsOfFolderPaging",
            "getCollapsedUnfiledPaging",
            "getCollapsedFolderPaging",
            "getLineagePaging",
            "searchConversationsPaging",
            "searchConversationsOfAssistantPaging",
        )

        for (name in lightQueries) {
            val sql = queries.getValue(name)
            assertTrue("$name 要给出 lineageId", sql.contains("AS lineageId") || sql.contains("AS lineage_id"))
            assertTrue("$name 要给出 folderAssignmentLocked", sql.contains("AS folderAssignmentLocked"))
            assertTrue("$name 要给出 familySize", sql.contains("AS familySize"))
        }
    }

    @Test
    fun `search stays uncollapsed`() {
        val queries = queries()
        for (name in listOf("searchConversationsPaging", "searchConversationsOfAssistantPaging", "searchConversations")) {
            val sql = queries.getValue(name)
            assertTrue("$name 还是按标题匹配", sql.contains("title LIKE '%' || :searchText || '%'"))
            assertFalse("$name 不能折叠：搜索结果要给出全部同族会话", sql.contains("NOT EXISTS"))
            assertFalse("$name 不该借用 familySize 的统计", sql.contains("f.is_pinned = 0"))
        }
    }

    @Test
    fun `narrow update statements only touch their own columns`() {
        val queries = queries()

        assertEquals(
            "UPDATE conversationentity SET lineage_id = :lineageId WHERE id = :id AND assistant_id = :assistantId",
            queries.getValue("updateLineageId"),
        )
        assertEquals(
            "UPDATE conversationentity SET folder_id = :folderId, folder_assignment_locked = :locked WHERE id = :id",
            queries.getValue("updateFolderAssignment"),
        )
    }

    @Test
    fun `pinned conversations are never folded away`() {
        for (name in listOf("getCollapsedUnfiledPaging", "getCollapsedFolderPaging")) {
            val sql = queries().getValue(name)
            assertTrue(
                "$name 的折叠条件把置顶排除在外",
                sql.contains("s.is_pinned = 1 OR NOT EXISTS ("),
            )
            assertFalse("$name 代表选择里不能再筛置顶", sql.contains("o.is_pinned = 1"))
        }
    }
}
