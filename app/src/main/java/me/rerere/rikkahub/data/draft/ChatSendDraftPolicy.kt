package me.rerere.rikkahub.data.draft

import me.rerere.rikkahub.data.model.ChatDraft

/**
 * 发送落库确认之后「清不清输入框 / 清哪些 key」的纯判定。
 *
 * ChatVM 只负责在主线程上现取 key 再调用这里，判定本身不碰 Android/Compose，
 * 这样 A->B 串会话、确认先于/后于 draftKey 迁移、续写、发送失败这几种时序都能在
 * 纯 JVM 单测里跑一遍（见 ChatSendDraftPolicyTest）。
 *
 * 不变量：
 * 1. 只有落库成功（[ChatDraftStore.ack] 的 persisted=true）才清；
 * 2. 归属没变才清——key 从「助手 key」迁到「本会话的会话 key」仍算同一份输入；
 * 3. 内容仍是发出去的那份才清，等确认期间续写的不清；
 * 4. 只 ack 本次发送自己的 key（原 key + 迁移后的本会话 key），绝不 ack 别的会话。
 */
object ChatSendDraftPolicy {

    /** 输入框是否还归这次发送所有。key 可能已经从助手 key 迁到会话 key。 */
    fun ownsInput(originKey: String?, currentKey: String?): Boolean =
        originKey == null ||
            currentKey == null ||
            currentKey == originKey ||
            isDraftKeyMigration(originKey, currentKey)

    /** 内容是否仍是发出去的那份（光标移动不算）。 */
    fun stillSameContent(current: ChatDraft, sent: ChatDraft): Boolean = current.sameContent(sent)

    /** 清输入框的完整条件。 */
    fun shouldClearInput(
        originKey: String?,
        currentKey: String?,
        persisted: Boolean,
        current: ChatDraft,
        sent: ChatDraft,
    ): Boolean = persisted && ownsInput(originKey, currentKey) && stillSameContent(current, sent)

    /**
     * 这次确认要 ack 的 key：原 key，加上（如果 key 已经迁移到本会话的会话 key）迁移后的那份。
     * 返回顺序稳定，重复去重；不含其它会话的 key。
     */
    fun keysToAck(originKey: String?, currentKey: String?): List<String> {
        val keys = ArrayList<String>(2)
        if (originKey != null) keys.add(originKey)
        if (originKey != null && currentKey != null && currentKey != originKey &&
            isDraftKeyMigration(originKey, currentKey)
        ) {
            keys.add(currentKey)
        }
        return keys.distinct()
    }
}
