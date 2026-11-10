package com.feedpulse.android.picker

import android.os.Build
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** 再送の設定。待ち時間は `baseDelayMs * 2^回数`。429 の `Retry-After` があればそちらを優先（[maxDelayMs] で頭打ち）。 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 120_000,
)

/**
 * Feedpulse のヘッドレスクライアント。通常は [Feedpulse.configure] で作り、[Feedpulse.client] から使う。
 *
 * 全ての関数は suspend（呼び出し元のスコープで動く。通信は IO ディスパッチャー）。
 * 失敗は [FeedpulseException]。状態は [config] / [lastError] を StateFlow で公開する。
 */
class FeedpulseClient internal constructor(
    val publicKey: String,
    val baseUrl: String,
    private val appId: String,
    private val attestor: AppAttestor,
    private val transport: HttpTransport,
    private val store: ConfigStore,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val deviceContext: () -> Map<String, String> = ::defaultDeviceContext,
    private val newIdempotencyKey: () -> String = ::randomIdempotencyKey,
) {
    private val base = baseUrl.trimEnd('/')
    private val cacheKey = "$base|$publicKey"

    private class Session(val token: String, val expiresAtSeconds: Long)

    private val sessionMutex = Mutex()
    @Volatile private var session: Session? = null
    @Volatile private var identityToken: String? = null

    private val _config = MutableStateFlow<FeedpulseConfig?>(store.load(cacheKey)?.let(ConfigCodec::decodeCache))
    private val _lastError = MutableStateFlow<FeedpulseException?>(null)

    // refreshConfig の世代管理。要求の開始順に番号を振り、より新しい要求の結果が反映済みなら古い応答は捨てる。
    private val configLock = Any()
    private var configRequestSeq = 0L
    private var configAppliedSeq = 0L

    /** 直近の config。保存値から復元したものや通信失敗後の前回値は `isStale = true`。未取得なら null。 */
    val config: StateFlow<FeedpulseConfig?> get() = _config

    /** 直近の通信エラー。成功すると null に戻る。 */
    val lastError: StateFlow<FeedpulseException?> get() = _lastError

    /**
     * 顧客バックエンドが発行した身元確認トークン（JWT）を設定する。SDK は検証せず `context.identity` にそのまま載せる。
     * null か空文字で解除。
     */
    fun identify(token: String?) {
        identityToken = token?.takeIf { it.isNotBlank() }
    }

    /** `GET /v1/config` を取りに行く。通信に失敗したら前回値（`isStale = true`）を返し、前回値も無ければ例外。 */
    suspend fun refreshConfig(): FeedpulseConfig {
        val seq = synchronized(configLock) { ++configRequestSeq }
        try {
            val res = authed("GET", "/v1/config?pk=" + java.net.URLEncoder.encode(publicKey, "UTF-8"), null)
            val json = parseObject(res.body)
            val fresh = FeedpulseConfig(ConfigCodec.parseForms(json), nowMillis())
            return synchronized(configLock) {
                if (seq < configAppliedSeq) {
                    // より新しい要求の結果が反映済み。古い応答で上書きしない。
                    _config.value ?: fresh
                } else {
                    configAppliedSeq = seq
                    store.save(cacheKey, ConfigCodec.encodeForms(fresh.forms, fresh.fetchedAtMillis))
                    _config.value = fresh
                    _lastError.value = null
                    fresh
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = e as? FeedpulseException
                ?: FeedpulseException(FeedpulseErrorCode.UNEXPECTED_RESPONSE, "config を解釈できない: ${e.message}", cause = e)
            val cached = synchronized(configLock) {
                if (seq < configAppliedSeq) {
                    // 古い要求の失敗は、反映済みの新しい config と lastError を変えない。
                    _config.value?.let { return@synchronized it }
                }
                _lastError.value = err
                _config.value?.copy(isStale = true)?.also { _config.value = it }
            }
            if (cached != null) return cached
            throw err
        }
    }

    /**
     * フィードバックを送る。
     *
     * 1. 項目定義（未取得なら [refreshConfig]）で [fields] を検証し、不正なら送らず `INVALID_FIELDS` を投げる。
     * 2. 冪等キーを付けて `POST /v1/submit`。通信失敗・429・5xx は [RetryPolicy] に従い同じキーで再送する。
     *
     * @param formId `forms[].id`。
     * @param fields ブロック ID → 値（[FormValidator] の型を参照）。
     * @param target [Feedpulse.pickTarget] の結果。フォームが `allowTarget` のときだけ `context.auto.target` に載せる。
     * @param custom `context.custom`（値は String / Number / Boolean）。
     * @param idempotencyKey 省略すると SDK が生成。アプリ側で再送を管理するときは同じ値を渡す（`^[A-Za-z0-9_-]{16,64}$`）。
     */
    suspend fun submit(
        formId: String,
        fields: Map<String, Any?>,
        target: FeedpulseTarget? = null,
        custom: Map<String, Any>? = null,
        idempotencyKey: String? = null,
    ): SubmitReceipt {
        val key = idempotencyKey ?: newIdempotencyKey()
        if (!IDEMPOTENCY_KEY.matches(key)) {
            throw FeedpulseException(FeedpulseErrorCode.INVALID_IDEMPOTENCY_KEY, "idempotencyKey は ^[A-Za-z0-9_-]{16,64}\$")
        }
        val sendFields = fields.filterValues { it != null && !(it is String && it.isBlank()) }
        custom?.values?.forEach {
            if (it !is String && it !is Boolean && !(it is Number && it.toDouble().isFinite())) {
                throw FeedpulseException(FeedpulseErrorCode.NOT_CONFIGURED, "custom の値は String / 有限の Number / Boolean のみ")
            }
        }
        var current = _config.value
        var form = current?.formById(formId)
        if (form == null) {
            current = refreshConfig()
            form = current.formById(formId)
        }
        if (form == null) throw FeedpulseException(FeedpulseErrorCode.UNKNOWN_FORM, "formId=$formId は config に無い")
        var errors = FormValidator.validate(form, sendFields)
        if (errors.isNotEmpty() && current?.isStale == true) {
            // 古い保存値で弾かない。最新の定義を取り直して検証し直す（取れなければ古い定義のまま）。
            form = refreshConfig().formById(formId) ?: form
            errors = FormValidator.validate(form, sendFields)
        }
        if (errors.isNotEmpty()) {
            throw FeedpulseException(FeedpulseErrorCode.INVALID_FIELDS, "入力が不正: $errors", fieldErrors = errors)
        }

        val auto = LinkedHashMap<String, Any>(deviceContext())
        if (target != null && form.definition.allowTarget) auto["target"] = target.toMap()
        val context = LinkedHashMap<String, Any>()
        context["auto"] = auto
        if (!custom.isNullOrEmpty()) context["custom"] = custom
        identityToken?.let { context["identity"] = it }

        val body = Json.encode(
            mapOf(
                "formId" to formId,
                "idempotencyKey" to key,
                "fields" to sendFields,
                "context" to context,
            ),
        )

        var attempt = 0
        while (true) {
            try {
                val res = authed("POST", "/v1/submit", body)
                val id = parseObject(res.body).optString("id")
                if (id.isEmpty()) throw FeedpulseException(FeedpulseErrorCode.UNEXPECTED_RESPONSE, "submit の応答に id が無い", res.status)
                _lastError.value = null
                return SubmitReceipt(id)
            } catch (e: FeedpulseException) {
                if (!e.isRetryable || attempt >= retryPolicy.maxRetries) {
                    _lastError.value = e
                    throw e
                }
                val wait = e.retryAfterSeconds?.let { it.coerceAtMost(retryPolicy.maxDelayMs / 1000) * 1000 } ?: (retryPolicy.baseDelayMs shl attempt)
                sleep(wait.coerceAtMost(retryPolicy.maxDelayMs))
                attempt++
            }
        }
    }

    // ---- セッション ----

    /**
     * 有効なセッションを返す（取得は single-flight: Mutex で直列化し、待機側は先に取れた結果を共有する）。
     * [forceNew] のとき、[rejectedToken]（サーバーが `invalid_session` とした token）と違う有効な
     * セッションが既にあれば、他の呼び出しが取り直し済みなのでそれを使う。
     */
    internal suspend fun ensureSession(forceNew: Boolean, rejectedToken: String? = null): String = sessionMutex.withLock {
        val cur = session
        if (cur != null && nowMillis() / 1000 < cur.expiresAtSeconds - SESSION_REFRESH_MARGIN_SECONDS) {
            if (!forceNew || (rejectedToken != null && cur.token != rejectedToken)) return@withLock cur.token
        }
        session = null
        try {
            openSession()
        } catch (e: FeedpulseException) {
            // challenge の期限切れ（端末が遅い等）は challenge を取り直して 1 回だけやり直す。
            if (e.code != FeedpulseErrorCode.CHALLENGE_EXPIRED && e.code != FeedpulseErrorCode.INVALID_CHALLENGE) throw e
            openSession()
        }
    }

    private suspend fun openSession(): String {
        val ch = post("/v1/native/challenge", Json.encode(mapOf("pk" to publicKey)), null)
        val challenge = parseObject(ch.body).optString("challenge")
        if (challenge.isEmpty()) throw FeedpulseException(FeedpulseErrorCode.UNEXPECTED_RESPONSE, "challenge の応答が不正", ch.status)
        val token = attestor.attest(challenge)
        val body = Json.encode(
            mapOf(
                "pk" to publicKey,
                "platform" to "android",
                "appId" to appId,
                "challenge" to challenge,
                "attestation" to mapOf("type" to "play_integrity", "token" to token),
            ),
        )
        val res = post("/v1/native/session", body, null)
        val json = parseObject(res.body)
        val jwt = json.optString("session")
        if (jwt.isEmpty() || !json.has("expiresAt")) {
            throw FeedpulseException(FeedpulseErrorCode.UNEXPECTED_RESPONSE, "session の応答が不正", res.status)
        }
        session = Session(jwt, json.getLong("expiresAt"))
        return jwt
    }

    /** [token] がまだ現在のセッションのときだけ破棄する（他の呼び出しが取り直した新しいセッションを消さない）。 */
    private suspend fun invalidateSession(token: String) = sessionMutex.withLock {
        if (session?.token == token) session = null
    }

    /** セッション付きの呼び出し。`invalid_session` なら取り直して 1 回だけやり直す。 */
    private suspend fun authed(method: String, pathAndQuery: String, body: String?): HttpResponse {
        var refreshed = false
        var forceNew = false
        var rejected: String? = null
        while (true) {
            val token = ensureSession(forceNew, rejected)
            try {
                return call(method, pathAndQuery, body, token)
            } catch (e: FeedpulseException) {
                when (e.code) {
                    FeedpulseErrorCode.INVALID_SESSION -> {
                        invalidateSession(token)
                        if (refreshed) throw e
                        refreshed = true
                        forceNew = true
                        rejected = token
                    }
                    FeedpulseErrorCode.APP_NOT_REGISTERED, FeedpulseErrorCode.ATTESTATION_FAILED -> {
                        invalidateSession(token)
                        throw e
                    }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun post(path: String, body: String, token: String?) = call("POST", path, body, token)

    private suspend fun call(method: String, pathAndQuery: String, body: String?, token: String?): HttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        if (token != null) {
            headers["X-FeedPulse-Platform"] = "android"
            headers["Authorization"] = "Bearer $token"
        }
        val res = try {
            withContext(Dispatchers.IO) { executeCancellable(HttpRequest(method, base + pathAndQuery, headers, body)) }
        } catch (e: IOException) {
            // キャンセルで接続を切られたときの IOException は、通信エラーでなくキャンセルとして扱う。
            currentCoroutineContext().ensureActive()
            throw FeedpulseException(FeedpulseErrorCode.NETWORK, e.message ?: "通信に失敗", cause = e)
        }
        if (res.status in 200..299) return res
        throw toException(res)
    }

    /** ブロッキング通信を実行する。スコープがキャンセルされたら通信中の接続を切って即座に抜ける。 */
    private suspend fun executeCancellable(request: HttpRequest): HttpResponse {
        val t = transport as? CancellableHttpTransport ?: return transport.execute(request)
        val handle = CancelHandle()
        return coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    handle.cancel()
                }
            }
            try {
                t.execute(request, handle)
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun toException(res: HttpResponse): FeedpulseException {
        val json = try { JSONObject(res.body) } catch (_: JSONException) { null }
        val code = FeedpulseErrorCode.fromWire(json?.optString("error"))
            ?: if (res.status >= 500) FeedpulseErrorCode.INTERNAL else FeedpulseErrorCode.UNEXPECTED_RESPONSE
        val fields = LinkedHashMap<String, FieldErrorCode>()
        json?.optJSONObject("fields")?.let { f ->
            for (k in f.keys()) FieldErrorCode.fromWire(f.optString(k))?.let { fields[k] = it }
        }
        return FeedpulseException(
            code = code,
            httpStatus = res.status,
            fieldErrors = fields,
            identityCode = json?.optString("code")?.takeIf { it.isNotEmpty() },
            retryAfterSeconds = res.headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw FeedpulseException(FeedpulseErrorCode.UNEXPECTED_RESPONSE, "JSON を解釈できない", cause = e)
    }

    companion object {
        internal val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{16,64}$")
        /** 期限のこの秒数前になったらセッションを取り直す。 */
        internal const val SESSION_REFRESH_MARGIN_SECONDS = 60L

        internal fun defaultDeviceContext(): Map<String, String> = mapOf(
            "os" to "Android ${Build.VERSION.RELEASE}",
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to TimeZone.getDefault().id,
        )

        internal fun randomIdempotencyKey(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
