package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /**
     * Responses API `file_citation`：模型引用了向量库 / file_search 里的文件。
     *
     * 只记录“引用了什么”，不含文件内容，也不含任何凭据；字符偏移是输出文本里的锚点，
     * 上游没给时保持 -1（file_citation 用的是单点 `index`，只有 [startIndex] 有值）。
     */
    @Serializable
    @SerialName("file_citation")
    data class FileCitation(
        val title: String,
        val fileId: String,
        val containerId: String? = null,
        val startIndex: Int = UNKNOWN_INDEX,
        val endIndex: Int = UNKNOWN_INDEX,
    ) : UIMessageAnnotation()

    /**
     * Responses API `container_file_citation`：模型引用了 code_interpreter 容器里生成的文件
     * （图表 GIF/PNG、CSV、ZIP 等）。[containerId] + [fileId] 才能定位内容。
     */
    @Serializable
    @SerialName("container_file_citation")
    data class ContainerFileCitation(
        val containerId: String,
        val fileId: String,
        val title: String = "",
        val startIndex: Int = UNKNOWN_INDEX,
        val endIndex: Int = UNKNOWN_INDEX,
    ) : UIMessageAnnotation()

    companion object {
        /** 上游没有提供字符偏移时的哨兵值。 */
        const val UNKNOWN_INDEX: Int = -1
    }
}
