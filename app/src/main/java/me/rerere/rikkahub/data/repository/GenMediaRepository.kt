package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    /** 一次性取全部生成记录：图片整理要按 source_paths 判断哪些图被当成编辑源引用过。 */
    suspend fun getAllMediaList(): List<GenMediaEntity> = dao.getAllMedia()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
