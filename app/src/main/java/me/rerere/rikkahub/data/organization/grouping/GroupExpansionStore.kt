package me.rerere.rikkahub.data.organization.grouping

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * 「哪些组是展开的」的持久化。
 *
 * 组本身（`conversation_folder` + `conversationentity.folder_id`）已经在库里，这里只记这一份纯展示状态。
 *
 * 为什么不用 SavedStateHandle：它只在「配置变更 / 系统回收进程后重建」这一小段里有效，用户杀掉应用再
 * 打开就回到全收起，等于每次重启都要重新点一遍。展开状态是跨重启的用户偏好，所以落到独立的
 * Preferences DataStore 文件里，按助手分键，不进 Settings（Settings 是整份 JSON 落盘，改一次会
 * 触发所有读设置的地方重组，而且那块正有别的改动在进行）。
 *
 * 文件单独一份、没有 migration：老用户盘上没这个文件，读出来就是「全收起」，和以前的行为一致。
 */
class GroupExpansionStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** 生产入口：进程内单例，同一个文件只能有一个 DataStore 实例。 */
    constructor(context: Context) : this(context.applicationContext.groupExpansionDataStore)

    /**
     * 某个助手展开的组 id。
     *
     * 读失败（IO/损坏）时按「全收起」处理并记一条日志：这只是列表展开状态，
     * 不该因为它让整个抽屉打不开。取消仍然照常传播。
     */
    fun expandedIdsOf(assistantId: Uuid): Flow<Set<Uuid>> = dataStore.data
        .map { preferences -> decodeExpandedGroups(preferences[expandedGroupsKey(assistantId)]) }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to read expanded conversation groups", e)
            emit(emptySet())
        }

    /**
     * 写入某个助手展开的组 id。
     *
     * 不跟着调用方取消：用户点了展开，页面刚好被回收也要把这一次写下去。
     * DataStore 在调用方上下文里编码，所以切到 IO。
     */
    suspend fun persist(assistantId: Uuid, expandedIds: Set<Uuid>) {
        withContext(NonCancellable + Dispatchers.IO) {
            dataStore.edit { preferences ->
                val key = expandedGroupsKey(assistantId)
                if (expandedIds.isEmpty()) {
                    preferences.remove(key)
                } else {
                    preferences[key] = encodeExpandedGroups(expandedIds)
                }
            }
        }
    }

    private fun expandedGroupsKey(assistantId: Uuid) =
        stringPreferencesKey(expandedGroupsKeyName(assistantId))

    private companion object {
        const val TAG = "GroupExpansionStore"
    }
}

private const val GROUP_EXPANSION_STORE_NAME = "conversation_group_expansion"

@Volatile
private var sharedGroupExpansionDataStore: DataStore<Preferences>? = null

private val Context.groupExpansionDataStore: DataStore<Preferences>
    get() = sharedGroupExpansionDataStore ?: synchronized(GroupExpansionStore::class) {
        sharedGroupExpansionDataStore ?: PreferenceDataStoreFactory.create(
            produceFile = { applicationContext.preferencesDataStoreFile(GROUP_EXPANSION_STORE_NAME) },
        ).also { sharedGroupExpansionDataStore = it }
    }
