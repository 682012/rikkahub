package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.organization.*
import kotlin.uuid.Uuid

/** Page lifecycle never owns or cancels the background queue. */
class ImageOrganizerVM(private val controller: ImageOrganizerController) : ViewModel() {
    val state = controller.state
    fun refresh() = controller.refresh()
    fun setModel(id: Uuid?) = controller.setModel(id)
    fun setWorkers(value: Int) = controller.setWorkers(value)
    fun classify(ids: Set<String>) = controller.classify(ids)
    fun cancelClassification() = controller.cancelClassification()
    fun setProtected(ids: Set<String>, value: Boolean) = controller.setProtected(ids, value)
    suspend fun delete(ids: Set<String>) = controller.delete(ids)
    fun dismissError() = controller.dismissError()
    fun clear() = Unit
    fun setAuto(config: ImageAutoConfig) = controller.setAuto(config)
    fun resume() = controller.resume()
    fun generatePlan() = controller.generatePlan()
    fun renameBucket(id: String, name: String) = controller.renameBucket(id, name)
}
