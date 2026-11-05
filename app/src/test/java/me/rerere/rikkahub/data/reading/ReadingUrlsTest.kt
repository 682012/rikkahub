package me.rerere.rikkahub.data.reading

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import java.net.InetAddress

class ReadingUrlsTest {
    @Test fun directLinksBoundedAndCodeIgnored() {
        val text = "```https://secret.example/a``` 看 https://example.com/x?a=1&b=2 https://u:p@example.com/ https://example.org/"
        assertEquals(listOf("https://example.com/x?a=1&b=2", "https://example.org/"), ReadingUrls.extract(text))
        assertEquals(3, ReadingUrls.extract((1..8).joinToString(" ") { "https://example.com/$it" }).size)
    }
    @Test fun privateAddressesRejected() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1").forEach {
            assertFalse(it, ReadingUrls.isPublic(InetAddress.getByName(it)))
        }
        assertTrue(ReadingUrls.isPublic(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun htmlExtractedWithoutScriptAndBounded() {
        val reader = LinkReader(OkHttpClient())
        val body = reader.extractBody("<html><title>标题</title><body><nav>导航</nav><article>正文<script>evil()</script></article></body></html>", "text/html")
        assertTrue(body.contains("正文")); assertFalse(body.contains("evil")); assertFalse(body.contains("导航"))
        assertTrue(reader.extractBody("x".repeat(25000), "text/plain").contains("截断"))
    }
}
