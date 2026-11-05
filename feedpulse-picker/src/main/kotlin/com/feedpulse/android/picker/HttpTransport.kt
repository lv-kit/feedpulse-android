package com.feedpulse.android.picker

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** HTTP の抽象。テストで差し替える。通信失敗は [IOException] を投げる。header 名は小文字で返す。 */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/** 既定の実装。`HttpURLConnection`（追加依存なし）。ブロッキングなので IO ディスパッチャーから呼ぶ。 */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
            }
            return HttpResponse(status, headers, text)
        } finally {
            conn.disconnect()
        }
    }
}
