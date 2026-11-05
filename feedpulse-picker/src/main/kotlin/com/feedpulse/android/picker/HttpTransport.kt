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

/** 通信中の接続を別スレッドから切るためのハンドル。コルーチンのキャンセル時に [cancel] する。 */
internal class CancelHandle {
    private val lock = Any()
    private var conn: HttpURLConnection? = null
    private var cancelled = false

    /** 接続を登録する。既にキャンセル済みなら即座に切る。 */
    fun attach(c: HttpURLConnection) {
        val cancelNow = synchronized(lock) {
            conn = c
            cancelled
        }
        if (cancelNow) c.disconnect()
    }

    /** キャンセル済みなら [IOException]。接続前の `disconnect()` は効かないことがあるので、接続を張る前に確認する。 */
    @Throws(IOException::class)
    fun throwIfCancelled() {
        if (synchronized(lock) { cancelled }) throw IOException("cancelled")
    }

    fun cancel() {
        val c = synchronized(lock) {
            cancelled = true
            conn
        }
        c?.disconnect()
    }
}

/** キャンセル時に通信中の接続を切れる [HttpTransport]。 */
internal interface CancellableHttpTransport : HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest, handle: CancelHandle): HttpResponse
}

/** 既定の実装。`HttpURLConnection`（追加依存なし）。ブロッキングなので IO ディスパッチャーから呼ぶ。 */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : CancellableHttpTransport {
    override fun execute(request: HttpRequest): HttpResponse = execute(request, CancelHandle())

    override fun execute(request: HttpRequest, handle: CancelHandle): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        handle.attach(conn)
        try {
            handle.throwIfCancelled()
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            handle.throwIfCancelled()
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            handle.throwIfCancelled()
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
