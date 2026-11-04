package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/** Saved send policy; the chosen batch size is snapshotted into message metadata. */
@Serializable
data class MultiImageSendSettings(
    val enabled: Boolean = true,
    /** Strictly more than this count starts batching; zero includes single images. */
    val threshold: Int = 1,
    val batchSize: Int = 1,
) {
    fun normalized(): MultiImageSendSettings = copy(
        threshold = threshold.coerceIn(0, MAX_IMAGES),
        batchSize = batchSize.coerceIn(1, MAX_IMAGES),
    )

    fun shouldBatch(imageCount: Int, supportsVision: Boolean): Boolean =
        enabled && supportsVision && imageCount > normalized().threshold

    companion object {
        const val MAX_IMAGES = 100
    }
}
