package me.rerere.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `rkresponses-file://` 引用必须能往返解析，且绝不接受 http(s)/file/data 等可被直接请求的地址——
 * 这些引用只携带定位信息，凭据由 App 侧按配置的 provider 现取。
 */
class ResponsesFileReferencesTest {

    @Test
    fun `container reference round trips`() {
        val url = responsesContainerFileReference("cntr_abc", "cfile_def")
        assertEquals("rkresponses-file://container?container_id=cntr_abc&file_id=cfile_def", url)

        val parsed = requireNotNull(parseResponsesFileReference(url))
        assertEquals(ResponsesFileReference.Kind.CONTAINER, parsed.kind)
        assertEquals("cntr_abc", parsed.containerId)
        assertEquals("cfile_def", parsed.fileId)
    }

    @Test
    fun `store reference round trips without container`() {
        val url = responsesStoreFileReference("file-abc123")
        assertEquals("rkresponses-file://file?file_id=file-abc123", url)

        val parsed = requireNotNull(parseResponsesFileReference(url))
        assertEquals(ResponsesFileReference.Kind.STORE, parsed.kind)
        assertNull(parsed.containerId)
        assertEquals("file-abc123", parsed.fileId)
    }

    @Test
    fun `ids with reserved characters are encoded`() {
        val url = responsesContainerFileReference("cntr/a b&c", "cfile?d=e")
        val parsed = requireNotNull(parseResponsesFileReference(url))
        assertEquals("cntr/a b&c", parsed.containerId)
        assertEquals("cfile?d=e", parsed.fileId)
        // 引用里不能出现未编码的 & 造成参数串位
        assertEquals(2, url.substringAfter('?').split('&').size)
    }

    @Test
    fun `blank ids produce no reference`() {
        assertEquals("", responsesContainerFileReference("", "cfile"))
        assertEquals("", responsesContainerFileReference("cntr", " "))
        assertEquals("", responsesStoreFileReference(""))
    }

    @Test
    fun `foreign and fetchable urls are rejected`() {
        assertNull(parseResponsesFileReference(null))
        assertNull(parseResponsesFileReference(""))
        assertNull(parseResponsesFileReference("https://api.openai.com/v1/containers/c/files/f/content"))
        assertNull(parseResponsesFileReference("file:///data/user/0/a.png"))
        assertNull(parseResponsesFileReference("data:image/png;base64,AA"))
        assertNull(parseResponsesFileReference("sandbox:/mnt/data/chart.png"))
        assertNull(parseResponsesFileReference("OTHER://container?container_id=c&file_id=f"))
    }

    @Test
    fun `missing required parts are rejected`() {
        assertNull(parseResponsesFileReference("rkresponses-file://container?file_id=f"))
        assertNull(parseResponsesFileReference("rkresponses-file://container?container_id=c"))
        assertNull(parseResponsesFileReference("rkresponses-file://container?container_id=c&file_id="))
        assertNull(parseResponsesFileReference("rkresponses-file://unknown?file_id=f"))
    }

    @Test
    fun `reference never embeds a token or host`() {
        val url = responsesContainerFileReference("c", "f")
        listOf("key", "token", "authorization", "secret", "@", "http").forEach { forbidden ->
            assertEquals("must not contain $forbidden", -1, url.indexOf(forbidden, ignoreCase = true))
        }
        // 只有两个参数：container_id / file_id
        assertEquals(
            setOf("container_id", "file_id"),
            url.substringAfter('?').split('&').map { it.substringBefore('=') }.toSet(),
        )
    }
}
