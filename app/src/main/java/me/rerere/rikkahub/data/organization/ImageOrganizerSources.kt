package me.rerere.rikkahub.data.organization

import android.content.Context
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 收集 app 自己的图片：聊天上传图、旧生成图、媒体创作产出图。
 *
 * 只读 app 的 filesDir 和数据库，**不扫手机相册**，也不碰外部文件。
 * 返回值里的 [ScanResult.references] 是「被别处引用」的路径计数，删除时用来保护。
 */
class ImageOrganizerSources(
    private val context: Context,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
    private val mediaCreationRepository: MediaCreationRepository? = null,
) {
    data class ScanResult(
        val items: List<ImageOrganizerItem>,
        val references: Map<String, Int> = emptyMap(),
    )

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val items = mutableListOf<ImageOrganizerItem>()
        val referenceBlobs = mutableListOf<String>()

        items += scanUploads()
        val genMedia = runCatching { genMediaRepository.getAllMediaList() }.getOrDefault(emptyList())
        items += scanGenMedia(filesDir, genMedia, referenceBlobs)
        items += scanMediaCreation(referenceBlobs)

        val seen = HashSet<String>()
        val unique = items.filter { seen.add(it.id) }
        ScanResult(
            items = unique,
            references = ImageReferenceIndex.build(filesDir, referenceBlobs),
        )
    }

    private suspend fun scanUploads(): List<ImageOrganizerItem> {
        val entities = runCatching { filesManager.list(FileFolders.UPLOAD) }.getOrDefault(emptyList())
        return entities.mapNotNull { entity ->
            if (!entity.mimeType.startsWith("image/") && !isImagePath(entity.relativePath)) return@mapNotNull null
            val file = filesManager.getFile(entity)
            if (!file.isFile) return@mapNotNull null
            ImageOrganizerItem(
                id = "upload:${entity.id}",
                name = entity.displayName,
                path = file.absolutePath,
                relativePath = entity.relativePath,
                bytes = file.length(),
                source = ImageSource.UPLOAD,
                lastModified = file.lastModified(),
            )
        }
    }

    private fun scanGenMedia(
        filesDir: File,
        entities: List<GenMediaEntity>,
        referenceBlobs: MutableList<String>,
    ): List<ImageOrganizerItem> {
        val imagesDir = filesManager.getImagesDir()
        return entities.mapNotNull { entity ->
            // source_paths 记录这张图是从哪些图编辑来的，用来保护被引用的图
            entity.sourcePaths?.takeIf { it.isNotBlank() }?.let(referenceBlobs::add)
            val file = ImageMediaPaths.resolveGenMediaFile(imagesDir, entity.path, entity.modelId, entity.createAt)
            if (!file.isFile || !isImagePath(file.name)) return@mapNotNull null
            ImageOrganizerItem(
                id = "gen:${entity.id}",
                name = file.name,
                path = file.absolutePath,
                relativePath = file.relativeTo(filesDir).path,
                bytes = file.length(),
                source = ImageSource.GEN_MEDIA,
                lastModified = file.lastModified(),
            )
        }
    }

    private suspend fun scanMediaCreation(
        referenceBlobs: MutableList<String>,
    ): List<ImageOrganizerItem> {
        val repository = mediaCreationRepository ?: return emptyList()
        val sessions = runCatching { repository.observeSessions().first() }.getOrDefault(emptyList())
        // 同一个产出可能被多条记录列着（切换版本、复制产出），按路径归并：
        // 只要有一条记录允许删，就按「可删」算（真正的判断在 removeImageOutput 里再做一次）
        val byPath = LinkedHashMap<String, ImageOrganizerItem>()
        sessions.forEach { session ->
            val records = runCatching { repository.getRecordsOfSession(session.id) }.getOrDefault(emptyList())
            records.forEach { record ->
                // 记录自己的输入素材被别处引用时同样要保护
                record.inputs
                    .filter { it.type == MediaCreationAssetType.IMAGE }
                    .forEach { referenceBlobs.add(it.path) }

                val busy = record.status.isActive
                record.outputs
                    .filter { it.mimeType.startsWith("image/") }
                    .forEach { output ->
                        val file = repository.resolve(output.path)
                        if (!file.isFile) return@forEach
                        // 带封面/尾帧的产出不在这里单独删（和 removeImageOutput 的规则保持一致）
                        val hasSideFiles = output.posterPath != null || output.lastFramePath != null
                        val blocked = busy || hasSideFiles
                        val item = ImageOrganizerItem(
                            id = "media:${output.path}",
                            name = file.name,
                            path = file.absolutePath,
                            relativePath = output.path,
                            bytes = file.length(),
                            source = ImageSource.MEDIA_CREATION,
                            lastModified = file.lastModified(),
                            deletable = !blocked,
                            blockReason = when {
                                busy -> "所属的生成记录还在进行中，等它完成后再删"
                                hasSideFiles -> "这条产出带着封面/尾帧，请在对应的创作会话里删除"
                                else -> null
                            },
                        )
                        val existing = byPath[output.path]
                        if (existing == null || (existing.blockReason != null && item.blockReason == null)) {
                            byPath[output.path] = item
                        }
                    }
            }
        }
        return byPath.values.toList()
    }

    private fun isImagePath(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
    }
}
