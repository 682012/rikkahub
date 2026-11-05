package me.rerere.rikkahub.data.reading

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ReadingUrls {
    private val urls = Regex("https?://[^\\s<>\\\"`]+", RegexOption.IGNORE_CASE)
    fun extract(text: String): List<String> = urls.findAll(text.replace(Regex("(?s)```.*?```"), ""))
        .map { it.value.trimEnd('.', ',', ';', '!', ')', ']', '}', '。', '，', '；', '）') }
        .filter { it.toHttpUrlOrNull()?.let { u -> u.username.isEmpty() && u.password.isEmpty() } == true }
        .distinct().take(3).toList()
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address
        if (b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc) return false
        if (b.size == 4) {
            val a = b[0].toInt() and 255; val c = b[1].toInt() and 255
            if (a == 0 || a >= 224 || (a == 100 && c in 64..127) || (a == 198 && c in 18..19)) return false
        }
        return true
    }
}

/** No provider headers/cookies/authentication, no crawler; each redirect is validated separately. */
class LinkReader(base: OkHttpClient) {
    private val client = OkHttpClient.Builder().connectionPool(base.connectionPool)
        .dispatcher(base.dispatcher).proxy(Proxy.NO_PROXY)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !ReadingUrls.isPublic(it) })
                    throw UnknownHostException("链接指向本机或内网，请用已授权的工作区工具读取")
            }
        }).build()

    private data class Page(val redirect: String? = null, val text: String? = null)
    suspend fun read(url: String): String = kotlinx.coroutines.withTimeoutOrNull(30000) {
        var target = url
        repeat(5) {
            val parsed = target.toHttpUrlOrNull() ?: error("网址格式无效")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "不自动读取带账号密码的网址" }
            // Numeric literals are checked explicitly as well as by DNS.
            if (':' in parsed.host || parsed.host.all { it.isDigit() || it == '.' }) {
                require(ReadingUrls.isPublic(InetAddress.getByName(parsed.host))) { "不自动读取本机或内网网址" }
            }
            val page = fetch(parsed)
            page.text?.let { return@withTimeoutOrNull it }
            target = parsed.resolve(page.redirect ?: error("重定向缺少地址"))?.toString() ?: error("无效重定向")
        }
        error("网页重定向超过5次")
    } ?: error("网页读取超过30秒，请稍后重试")

    private suspend fun fetch(url: HttpUrl): Page = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "RikkaHub-Reader/1.0").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val page = response.use {
                        if (response.code in setOf(301, 302, 303, 307, 308)) Page(redirect = response.header("Location") ?: error("重定向无地址"))
                        else {
                            require(response.isSuccessful) { "网页返回 HTTP ${response.code}" }
                            val body = response.body ?: error("网页正文为空")
                            val type = body.contentType()?.toString().orEmpty()
                            require(type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.isEmpty()) {
                                "链接是文件（$type），请下载后作为附件发送"
                            }
                            require(body.contentLength() <= MAX_BYTES) { "网页超过2 MiB" }
                            val output = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val n = input.read(buffer); if (n < 0) break
                                    require(output.size() + n <= MAX_BYTES) { "网页超过2 MiB" }
                                    output.write(buffer, 0, n)
                                }
                            }
                            val raw = output.toString(body.contentType()?.charset(Charsets.UTF_8)?.name() ?: "UTF-8")
                            Page(text = "来源：$url\n" + extractBody(raw, type))
                        }
                    }
                    if (continuation.isActive) continuation.resume(page)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    internal fun extractBody(raw: String, type: String): String {
        val text = if (type.contains("html") || raw.trimStart().startsWith("<!DOCTYPE html", true) || raw.trimStart().startsWith("<html", true)) {
            val doc = Jsoup.parse(raw)
            doc.select("script,style,noscript,nav,footer,form,iframe,svg").remove()
            val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            "${doc.title()}\n\n${main?.wholeText().orEmpty()}"
        } else raw
        val clean = text.trim()
        require(clean.isNotBlank()) { "未提取到正文，可能需要登录或脚本渲染" }
        return clean.take(MAX_CHARS) + if (clean.length > MAX_CHARS) "\n[正文已截断，仅包含前 $MAX_CHARS 字符]" else ""
    }
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024; const val MAX_CHARS = 24000 }
}
