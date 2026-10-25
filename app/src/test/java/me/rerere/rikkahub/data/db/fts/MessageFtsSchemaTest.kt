package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `message_fts` 的结构检查：只看分词器不够，列不同同样会让开库回填、索引写入和查询一起抛错。
 */
class MessageFtsSchemaTest {
    private val simpleSql =
        "CREATE VIRTUAL TABLE message_fts USING fts5(text, node_id UNINDEXED, message_id UNINDEXED, " +
            "conversation_id UNINDEXED, title UNINDEXED, update_at UNINDEXED, tokenize = 'simple')"

    @Test
    fun `create statement declares exactly the columns the code reads and writes`() {
        val declared = CREATE_MESSAGE_FTS_SQL
            .substringAfter("fts5(")
            .substringBefore("tokenize")
            .split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
        assertEquals(messageFtsColumns, declared)
    }

    @Test
    fun `a matching simple table is reused`() {
        assertTrue(isUsableMessageFtsSchema(simpleSql, messageFtsColumns))
    }

    @Test
    fun `a missing table is never usable`() {
        assertFalse(isUsableMessageFtsSchema(null, emptyList()))
    }

    @Test
    fun `a different tokenizer is rebuilt`() {
        val unicode = simpleSql.replace("'simple'", "'unicode61'")
        assertFalse(isUsableMessageFtsSchema(unicode, messageFtsColumns))
    }

    @Test
    fun `a legacy table with other columns is rebuilt even with a simple tokenizer`() {
        // 旧分支/原型留下的索引：分词器对、列不对。开库时的 rowid 回填会 SELECT conversation_id 而失败，
        // 让整个数据库打不开，所以这种表必须判为不可用。
        assertFalse(isUsableMessageFtsSchema(simpleSql, listOf("text", "conversation")))
        assertFalse(isUsableMessageFtsSchema(simpleSql, messageFtsColumns - "conversation_id"))
    }
}
