package me.rerere.rikkahub.data.organization.autofile

import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class RoomConversationAutoFileGateway(
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val chatService: ChatService,
) : ConversationAutoFileGateway {

    override suspend fun canAutoFile(conversationId: Uuid): Boolean =
        chatService.canAutoFileConversation(conversationId)

    override suspend fun loadCandidates(assistantId: Uuid): List<AutoFileCandidate> {
        val result = ArrayList<AutoFileCandidate>(PAGE_SIZE)
        var cursor: Int? = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val offset = cursor ?: break
            val page = conversationRepository.getUnfiledConversationsOfAssistantPage(assistantId, offset, PAGE_SIZE)
            page.items.forEach { conversation ->
                result += conversation.toCandidate()
            }
            cursor = page.nextOffset
            pages += 1
        }
        return result
    }

    override suspend fun loadExcerpt(conversationId: Uuid, headChars: Int, tailChars: Int): String =
        conversationRepository.getAutoFileExcerpt(conversationId, null, headChars, tailChars)

    override suspend fun loadExcerptForAssistant(assistantId: Uuid, conversationId: Uuid, headChars: Int, tailChars: Int): String =
        conversationRepository.getAutoFileExcerpt(conversationId, assistantId, headChars, tailChars)

    override suspend fun loadConversationTitles(assistantId: Uuid, conversationIds: List<Uuid>): Map<Uuid, String> =
        conversationRepository.getConversationTitles(assistantId, conversationIds)

    override suspend fun loadFolders(assistantId: Uuid): List<AutoFileFolderRef> =
        folderRepository.getFoldersOfAssistant(assistantId).first().map { AutoFileFolderRef(it.id, it.name) }

    override suspend fun createFolder(assistantId: Uuid, name: String): Uuid? =
        folderRepository.createFolder(assistantId, name).id

    override suspend fun move(
        assistantId: Uuid,
        conversationId: Uuid,
        expectedUpdateAt: Long,
        expectedFolderId: Uuid?,
        folderId: Uuid?,
        undo: Boolean,
    ): Boolean = chatService.applyAutoFileMove(
            conversationId = conversationId,
            assistantId = assistantId,
            expectedUpdateAt = expectedUpdateAt,
            expectedFolderId = expectedFolderId,
            folderId = folderId,
            undo = undo,
        )

    override suspend fun placement(conversationId: Uuid): AutoFilePlacement? =
        conversationRepository.getConversationSummary(conversationId)?.let { conversation ->
            AutoFilePlacement(
                conversationId = conversation.id,
                folderId = conversation.folderId,
                updateAt = conversation.updateAt.toEpochMilli(),
                pinned = conversation.isPinned,
                locked = conversation.folderAssignmentLocked,
            )
        }

    override suspend fun postSummary(assistantId: Uuid, text: String) {
        val target = conversationRepository.getConversationsOfAssistantPage(assistantId, 0, 1).items.firstOrNull() ?: return
        if (conversationRepository.getConversationAssistantId(target.id.toString()) != assistantId.toString()) return
        try {
            chatService.postNotice(target.id, NOTICE_KEY, UIMessagePart.Notice(title = "自动归库", text = text),
                expectedAssistantId = assistantId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.w("ConversationAutoFile", "Could not deliver filing summary", e)
        }
    }

    private fun Conversation.toCandidate(): AutoFileCandidate = AutoFileCandidate(
        id = id,
        title = title,
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
        folderId = folderId,
        pinned = isPinned,
        locked = folderAssignmentLocked,
        familyKey = lineageRootId,
    )

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 3
        const val NOTICE_KEY = "conversation_autofile"
    }
}
