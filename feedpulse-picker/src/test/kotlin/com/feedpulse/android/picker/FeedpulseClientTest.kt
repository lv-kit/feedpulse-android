package com.feedpulse.android.picker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** 契約 fixture（web の packages/shared/src/native/fixtures/contract.json の写し）を読み、モックサーバーとして使う。 */
@RunWith(RobolectricTestRunner::class)
class FeedpulseClientTest {
    private lateinit var server: MockWebServer
    private val sleeps = ArrayList<Long>()
    private var now = 1_700_000_000_000L
    private val fixtures = JSONObject(javaClass.getResource("/contract.json")!!.readText()).getJSONArray("fixtures").let { a ->
        (0 until a.length()).associate { a.getJSONObject(it).getString("name") to a.getJSONObject(it) }
    }

    private fun fx(name: String) = fixtures.getValue(name)
    private fun respBody(name: String) = fx(name).getJSONObject("response").getJSONObject("body")

    private val formId = "6f1d2c3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
    private val session = "session-jwt-1"

    /** path ごとの応答キュー（尽きたら最後を繰り返す）。 */
    private val routes = HashMap<String, MutableList<MockResponse>>()
    private val seen = ArrayList<RecordedRequest>()

    private fun mock(fixtureName: String, headersOverride: Map<String, String> = emptyMap()): MockResponse {
        val r = fx(fixtureName).getJSONObject("response")
        val m = MockResponse().setResponseCode(r.getInt("status")).setBody(r.getJSONObject("body").toString())
        r.optJSONObject("headers")?.let { h -> h.keys().forEach { m.setHeader(it, h.getString(it)) } }
        headersOverride.forEach { (k, v) -> m.setHeader(k, v) }
        return m
    }

    private fun route(path: String, vararg responses: MockResponse) {
        routes[path] = responses.toMutableList()
    }

    private fun happyRoutes() {
        route("/v1/native/challenge", mock("challenge-ok"))
        route("/v1/native/session", mock("session-ok-play-integrity"))
        route("/v1/config", mock("config-ok"))
        route("/v1/submit", mock("submit-ok"))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FeedpulseClientTest) {
                seen.add(request)
                val path = request.requestUrl!!.encodedPath
                val q = routes[path] ?: return MockResponse().setResponseCode(404).setBody("{\"error\":\"not_found\"}")
                if (q.size > 1) q.removeAt(0) else q[0]
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(
        attestor: AppAttestor = AppAttestor { "integrity-token" },
        store: ConfigStore = InMemoryConfigStore(),
        deviceContext: () -> Map<String, String> = { mapOf("os" to "Android 15", "locale" to "ja-JP") },
        keys: List<String> = listOf("k3FvQ2mT9xLw8RzB"),
        transport: HttpTransport = UrlConnectionTransport(),
        appId: String = "com.example.app",
    ): FeedpulseClient {
        val it = keys.iterator()
        var last = keys.last()
        return FeedpulseClient(
            publicKey = "pk_example",
            baseUrl = server.url("/").toString().trimEnd('/'),
            appId = appId,
            attestor = attestor,
            transport = transport,
            store = store,
            nowMillis = { now },
            sleep = { sleeps.add(it) },
            deviceContext = deviceContext,
            newIdempotencyKey = { if (it.hasNext()) it.next().also { k -> last = k } else last },
        )
    }

    private fun requests(path: String) = seen.filter { it.requestUrl!!.encodedPath == path }

    private fun fail(block: suspend () -> Unit): FeedpulseException {
        try {
            runBlocking { block() }
        } catch (e: FeedpulseException) {
            return e
        }
        fail("FeedpulseException が投げられなかった")
        throw AssertionError()
    }

    private fun json(s: String) = JSONObject(s)

    // ---------- 契約 fixture: session ----------

    @Test
    fun sessionFixtures_android() {
        for (name in listOf("session-ok-play-integrity", "session-attestation-failed", "session-challenge-expired", "session-app-not-registered", "session-rate-limited")) {
            seen.clear(); sleeps.clear()
            val f = fx(name)
            val reqBody = f.getJSONObject("request").getJSONObject("body")
            happyRoutes()
            route("/v1/native/session", mock(name))
            val c = client(attestor = AppAttestor { reqBody.getJSONObject("attestation").getString("token") }, appId = reqBody.getString("appId"))
            val expected = f.getJSONObject("response")
            if (expected.getInt("status") == 200) {
                runBlocking { c.ensureSession(false) }
            } else {
                val e = fail { c.ensureSession(false) }
                assertEquals(name, expected.getJSONObject("body").getString("error"), e.code.wire)
                assertEquals(name, expected.getInt("status"), e.httpStatus)
                f.getJSONObject("response").optJSONObject("headers")?.optString("retry-after")?.takeIf { it.isNotEmpty() }
                    ?.let { assertEquals(name, it.toLong(), e.retryAfterSeconds) }
                // アプリ証明の失敗などは再試行しない（session は 1 回だけ）。
                assertEquals(name, if (name == "session-challenge-expired") 2 else 1, requests("/v1/native/session").size)
            }
            // 要求本文が契約どおり（challenge は challenge-ok の値）。
            val sent = json(requests("/v1/native/session").last().body.readUtf8())
            val want = JSONObject(reqBody.toString()).put("challenge", respBody("challenge-ok").getString("challenge"))
            assertEquals(name, want.toString(), sent.toString().let { JSONObject(it) }.toString().let { reorder(JSONObject(it), want).toString() })
            assertEquals(name, "pk_example", requests("/v1/native/challenge").last().body.readUtf8().let { json(it).getString("pk") })
            assertNull(name, requests("/v1/native/session").last().getHeader("Origin"))
        }
    }

    /** JSONObject はキー順を保証しないので、比較用に [like] の形へ値だけ写す。 */
    private fun reorder(o: JSONObject, like: JSONObject): JSONObject = JSONObject().also { r ->
        for (k in like.keys()) {
            val v = o.opt(k)
            r.put(k, if (v is JSONObject && like.opt(k) is JSONObject) reorder(v, like.getJSONObject(k)) else v)
        }
    }

    // ---------- 契約 fixture: config ----------

    @Test
    fun configFixtures() {
        // 成功
        happyRoutes()
        val c = client()
        val cfg = runBlocking { c.refreshConfig() }
        val want = respBody("config-ok").getJSONArray("forms").getJSONObject(0)
        assertEquals(1, cfg.forms.size)
        val form = cfg.forms[0]
        assertEquals(want.getString("id"), form.id)
        assertEquals(want.getInt("version"), form.version)
        assertTrue(form.definition.allowTarget)
        assertEquals(listOf("title", "body", "severity"), form.definition.blocks.map { it.id })
        assertEquals(50, form.definition.blocks[0].maxLength)
        assertEquals(listOf("low", "high"), form.definition.blocks[2].options.map { it.value })
        assertFalse(cfg.isStale)
        assertEquals(cfg, c.config.value)
        val req = requests("/v1/config").single()
        assertEquals("android", req.getHeader("X-FeedPulse-Platform"))
        assertEquals("Bearer " + respBody("session-ok-play-integrity").getString("session"), req.getHeader("Authorization"))
        assertNull(req.getHeader("Origin"))
        assertEquals("pk_example", req.requestUrl!!.queryParameter("pk"))

        // 失敗（前回値も無し）
        for (name in listOf("config-session-expired", "config-rate-limited")) {
            seen.clear()
            happyRoutes()
            route("/v1/config", mock(name))
            val e = fail { client().refreshConfig() }
            assertEquals(name, respBody(name).getString("error"), e.code.wire)
            assertEquals(name, fx(name).getJSONObject("response").getInt("status"), e.httpStatus)
        }
    }

    @Test
    fun configSessionExpired_refreshesSessionOnceThenGivesUp() {
        happyRoutes()
        route("/v1/config", mock("config-session-expired"))
        val e = fail { client().refreshConfig() }
        assertEquals(FeedpulseErrorCode.INVALID_SESSION, e.code)
        assertEquals(2, requests("/v1/native/session").size)
        assertEquals(2, requests("/v1/config").size)
    }

    @Test
    fun configFailure_fallsBackToPreviousValue() {
        happyRoutes()
        val store = InMemoryConfigStore()
        val c = client(store = store)
        val first = runBlocking { c.refreshConfig() }
        route("/v1/config", mock("config-rate-limited"))
        val second = runBlocking { c.refreshConfig() }
        assertEquals(first.forms, second.forms)
        assertTrue(second.isStale)
        assertEquals(FeedpulseErrorCode.RATE_LIMITED, c.lastError.value?.code)

        // プロセス再起動後（保存値から復元）。通信できなくても前回値を返す。
        val c2 = client(store = store, transport = { throw IOException("offline") })
        assertEquals(first.forms, c2.config.value?.forms)
        val third = runBlocking { c2.refreshConfig() }
        assertEquals(first.forms, third.forms)
        assertTrue(third.isStale)
        assertEquals(FeedpulseErrorCode.NETWORK, c2.lastError.value?.code)
    }

    // ---------- 契約 fixture: submit ----------

    private fun substitute(v: Any?, identity: String): Any? = when (v) {
        "{{formId}}" -> formId
        "{{identity}}" -> identity
        is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, substitute(v.get(k), identity)) } }
        else -> v
    }

    @Test
    fun submitFixtures_success() {
        for (name in listOf("submit-ok", "submit-ok-with-identity")) {
            seen.clear()
            happyRoutes()
            val c = client()
            val body = substitute(fx(name).getJSONObject("request").getJSONObject("body"), "id.jwt.token") as JSONObject
            val ctx = body.optJSONObject("context")
            ctx?.optString("identity")?.takeIf { it.isNotEmpty() }?.let { c.identify(it) }
            val fields = body.getJSONObject("fields").let { f -> f.keys().asSequence().associateWith { f.get(it) } }
            val custom = ctx?.optJSONObject("custom")?.let { f -> f.keys().asSequence().associateWith { f.get(it) as Any } }
            val auto = ctx?.optJSONObject("auto")
            val c2 = client(deviceContext = { auto?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } } ?: emptyMap() })
            if (ctx?.optString("identity")?.isNotEmpty() == true) c2.identify(ctx.getString("identity"))
            val receipt = runBlocking { c2.submit(formId, fields, custom = custom, idempotencyKey = body.getString("idempotencyKey")) }
            assertEquals(name, respBody(name).getString("id"), receipt.id)

            val req = requests("/v1/submit").single()
            assertEquals(name, "android", req.getHeader("X-FeedPulse-Platform"))
            assertEquals(name, "Bearer " + respBody("session-ok-play-integrity").getString("session"), req.getHeader("Authorization"))
            assertNull(name, req.getHeader("Origin"))
            assertTrue(name, req.getHeader("Content-Type")!!.startsWith("application/json"))
            val sent = json(req.body.readUtf8())
            // context.auto は fixture にある場合のみ比較し、無ければ空 auto が付く。
            if (ctx?.has("auto") != true) sent.getJSONObject("context").remove("auto")
            assertEquals(name, canonical(body), canonical(sent))
        }
    }

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        else -> v.toString()
    }

    @Test
    fun nonJsonRateLimitIsRetriedWithRetryAfter() {
        happyRoutes()
        route("/v1/submit", MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("<html>busy</html>"))
        val e = fail { client().submit(formId, mapOf("title" to "x", "severity" to "high")) }
        assertEquals(FeedpulseErrorCode.UNEXPECTED_RESPONSE, e.code)
        assertEquals(2L, e.retryAfterSeconds)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), sleeps)
        assertEquals(4, requests("/v1/submit").size)
    }

    @Test
    fun submitFixtures_errors() {
        val cases = listOf(
            "submit-invalid-fields", "submit-identity-invalid", "submit-session-expired",
            "submit-rate-limited", "submit-app-not-registered",
        )
        for (name in cases) {
            seen.clear(); sleeps.clear()
            happyRoutes()
            route("/v1/submit", mock(name))
            // 項目定義上は妥当な入力を送り、サーバーの応答だけを fixture にする。
            val e = fail { client().submit(formId, mapOf("title" to "x", "severity" to "high")) }
            val want = respBody(name)
            assertEquals(name, want.getString("error"), e.code.wire)
            assertEquals(name, fx(name).getJSONObject("response").getInt("status"), e.httpStatus)
            if (want.has("code")) assertEquals(name, want.getString("code"), e.identityCode)
            want.optJSONObject("fields")?.let { f ->
                assertEquals(name, f.keys().asSequence().associateWith { f.getString(it) }, e.fieldErrors.mapValues { it.value.wire })
            }
            when (name) {
                // 429 は Retry-After(30s) を守って 3 回再送してから諦める（同じ冪等キー）。
                "submit-rate-limited" -> {
                    assertEquals(listOf(30_000L, 30_000L, 30_000L), sleeps)
                    assertEquals(30L, e.retryAfterSeconds)
                    val keys = requests("/v1/submit").map { json(it.body.readUtf8()).getString("idempotencyKey") }.toSet()
                    assertEquals(4, requests("/v1/submit").size)
                    assertEquals(1, keys.size)
                }
                // 期限切れのセッションは取り直して 1 回だけやり直す。
                "submit-session-expired" -> {
                    assertEquals(2, requests("/v1/native/session").size)
                    assertEquals(2, requests("/v1/submit").size)
                }
                // 検証・認証系は再送しない。
                else -> {
                    assertEquals(name, 1, requests("/v1/submit").size)
                    assertTrue(name, sleeps.isEmpty())
                }
            }
        }
    }

    @Test
    fun submitInvalidFieldsFixture_isCaughtOnClientWithSameCodes() {
        val body = fx("submit-invalid-fields").getJSONObject("request").getJSONObject("body")
        val f = body.getJSONObject("fields")
        happyRoutes()
        val e = fail { client().submit(formId, f.keys().asSequence().associateWith { f.get(it) }) }
        assertEquals(FeedpulseErrorCode.INVALID_FIELDS, e.code)
        val want = respBody("submit-invalid-fields").getJSONObject("fields")
        assertEquals(want.keys().asSequence().associateWith { want.getString(it) }, e.fieldErrors.mapValues { it.value.wire })
        assertTrue(requests("/v1/submit").isEmpty())
    }

    // ---------- セッション ----------

    @Test
    fun session_isCachedAndRefreshedBeforeExpiry() {
        happyRoutes()
        val c = client()
        runBlocking { c.refreshConfig(); c.refreshConfig() }
        assertEquals(1, requests("/v1/native/session").size)
        // expiresAt=1700000900。60 秒前（…840）を過ぎたら取り直す。
        now = 1_700_000_839_000L
        runBlocking { c.refreshConfig() }
        assertEquals(1, requests("/v1/native/session").size)
        now = 1_700_000_841_000L
        runBlocking { c.refreshConfig() }
        assertEquals(2, requests("/v1/native/session").size)
        assertEquals(2, requests("/v1/native/challenge").size)
    }

    @Test
    fun attestorFailure_isReportedAndNotRetriedAsNetwork() {
        happyRoutes()
        val c = client(attestor = AppAttestor { throw FeedpulseException(FeedpulseErrorCode.ATTESTATION_UNAVAILABLE, "no play") })
        val e = fail { c.submit(formId, mapOf("title" to "x", "severity" to "high")) }
        assertEquals(FeedpulseErrorCode.ATTESTATION_UNAVAILABLE, e.code)
        assertTrue(requests("/v1/submit").isEmpty())
        assertTrue(sleeps.isEmpty())
    }

    // ---------- submit の挙動 ----------

    @Test
    fun submit_retriesServerErrorsWithBackoffAndSameKey() {
        happyRoutes()
        route("/v1/submit", MockResponse().setResponseCode(503).setBody("{\"error\":\"internal\"}"), mock("submit-ok"))
        val receipt = runBlocking { client(keys = listOf("generatedKey_0123456789")).submit(formId, mapOf("title" to "x", "severity" to "low")) }
        assertEquals("0b9f1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d", receipt.id)
        assertEquals(listOf(1_000L), sleeps)
        val keys = requests("/v1/submit").map { json(it.body.readUtf8()).getString("idempotencyKey") }
        assertEquals(listOf("generatedKey_0123456789", "generatedKey_0123456789"), keys)
    }

    @Test
    fun submit_retriesNetworkFailure() {
        happyRoutes()
        var n = 0
        val real = UrlConnectionTransport()
        val c = client(transport = { r -> if (r.url.endsWith("/v1/submit") && n++ < 2) throw IOException("reset") else real.execute(r) })
        runBlocking { c.submit(formId, mapOf("title" to "x", "severity" to "low")) }
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun submit_networkFailureGivesNetworkError() {
        val c = client(transport = { throw IOException("offline") })
        val e = fail { c.submit(formId, mapOf("title" to "x")) }
        assertEquals(FeedpulseErrorCode.NETWORK, e.code)
        assertTrue(e.isRetryable)
    }

    @Test
    fun submit_defaultKeyMatchesContractPattern() {
        repeat(20) { assertTrue(FeedpulseClient.IDEMPOTENCY_KEY.matches(FeedpulseClient.randomIdempotencyKey())) }
        happyRoutes()
        val e = fail { client().submit(formId, mapOf("title" to "x", "severity" to "low"), idempotencyKey = "short") }
        assertEquals(FeedpulseErrorCode.INVALID_IDEMPOTENCY_KEY, e.code)
    }

    @Test
    fun submit_targetIsPutUnderContextAutoTarget_onlyWhenAllowed() {
        happyRoutes()
        val target = FeedpulseTarget(screen = "Checkout", id = "checkout.pay")
        runBlocking { client().submit(formId, mapOf("title" to "x", "severity" to "low"), target = target) }
        val ctx = json(requests("/v1/submit").single().body.readUtf8()).getJSONObject("context")
        assertEquals("checkout.pay", ctx.getJSONObject("auto").getJSONObject("target").getString("id"))
        assertEquals("android", ctx.getJSONObject("auto").getJSONObject("target").getString("platform"))
        assertFalse(ctx.has("identity"))
        assertFalse(ctx.has("custom"))

        // allowTarget=false のフォームでは載せない。
        seen.clear()
        val cfg = JSONObject(respBody("config-ok").toString())
        cfg.getJSONArray("forms").getJSONObject(0).getJSONObject("definition").put("allowTarget", false)
        route("/v1/config", MockResponse().setBody(cfg.toString()))
        runBlocking { client().submit(formId, mapOf("title" to "x", "severity" to "low"), target = target) }
        val ctx2 = json(requests("/v1/submit").single().body.readUtf8()).getJSONObject("context")
        assertFalse(ctx2.getJSONObject("auto").has("target"))
    }

    @Test
    fun submit_identityCanBeClearedAndUnknownFormIsReported() {
        happyRoutes()
        val c = client()
        c.identify("a.b.c")
        c.identify(null)
        runBlocking { c.submit(formId, mapOf("title" to "x", "severity" to "low")) }
        assertFalse(json(requests("/v1/submit").single().body.readUtf8()).getJSONObject("context").has("identity"))
        val e = fail { c.submit("00000000-0000-4000-8000-000000000000", emptyMap()) }
        assertEquals(FeedpulseErrorCode.UNKNOWN_FORM, e.code)
    }

    @Test
    fun error_unknownBodyMapsToUnexpectedOrInternal() {
        happyRoutes()
        route("/v1/submit", MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val e = fail { client().submit(formId, mapOf("title" to "x", "severity" to "low")) }
        assertEquals(FeedpulseErrorCode.INTERNAL, e.code)
        assertEquals(502, e.httpStatus)
        assertEquals(4, requests("/v1/submit").size)
        assertNotNull(e.message)
    }

    @Test
    fun challengeExpired_retriesSessionOnceWithNewChallenge() {
        happyRoutes()
        route("/v1/native/session", mock("session-challenge-expired"), mock("session-ok-play-integrity"))
        runBlocking { client().refreshConfig() }
        assertEquals(2, requests("/v1/native/challenge").size)
        assertEquals(2, requests("/v1/native/session").size)
    }

    @Test
    fun staleCachedDefinition_isRefreshedBeforeRejectingInput() {
        happyRoutes()
        val store = InMemoryConfigStore()
        // 保存値は severity の選択肢が古い（"high" が無い）。
        val old = JSONObject(respBody("config-ok").toString())
        old.getJSONArray("forms").getJSONObject(0).getJSONObject("definition").getJSONArray("blocks")
            .getJSONObject(2).put("options", JSONArray().put(JSONObject().put("value", "low").put("label", "低")))
        route("/v1/config", MockResponse().setBody(old.toString()))
        runBlocking { client(store = store).refreshConfig() }
        route("/v1/config", mock("config-ok"))
        val c = client(store = store)
        assertTrue(c.config.value!!.isStale)
        runBlocking { c.submit(formId, mapOf("title" to "x", "severity" to "high")) }
        assertEquals(1, requests("/v1/submit").size)
    }

    @Test
    fun blankOptionalFieldsAreNotSent() {
        happyRoutes()
        runBlocking { client().submit(formId, mapOf("title" to "x", "severity" to "low", "body" to "  ")) }
        assertFalse(json(requests("/v1/submit").single().body.readUtf8()).getJSONObject("fields").has("body"))
    }

    @Test
    fun configure_rejectsPlainHttp() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            Feedpulse.configure(ctx, "pk_x", "http://example.com", AppAttestor { "t" })
            fail("http は拒否される")
        } catch (_: IllegalArgumentException) {
        }
        Feedpulse.configure(ctx, "pk_x", "http://localhost:8787", AppAttestor { "t" })
    }

    // ---------- バリデーション ----------

    private fun block(type: String, required: Boolean = false, maxLength: Int? = null, min: Double? = null, max: Double? = null, opts: List<String> = emptyList()) =
        FeedpulseBlock("b", type, "L", required, maxLength, min, max, opts.map { FeedpulseOption(it, it) })

    private fun check(b: FeedpulseBlock, v: Any?): FieldErrorCode? = FormValidator.validate(
        FeedpulseForm("i", "s", "n", "bug", 1, FeedpulseFormDefinition(false, listOf(b))),
        if (v == null) emptyMap() else mapOf("b" to v),
    )["b"]

    @Test
    fun validator() {
        assertEquals(FieldErrorCode.REQUIRED, check(block("short_text", required = true), null))
        assertEquals(FieldErrorCode.REQUIRED, check(block("short_text", required = true), "  "))
        assertNull(check(block("short_text"), null))
        assertEquals(FieldErrorCode.TOO_LONG, check(block("short_text", maxLength = 3), "abcd"))
        assertNull(check(block("short_text", maxLength = 3), "abc"))
        assertEquals(FieldErrorCode.TYPE, check(block("long_text"), 5))
        assertEquals(FieldErrorCode.INVALID_EMAIL, check(block("email"), "no-at"))
        assertNull(check(block("email"), "a@b.co"))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(block("number", min = 1.0, max = 5.0), 6))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(block("rating", min = 1.0, max = 5.0), 0))
        assertNull(check(block("rating", min = 1.0, max = 5.0), 5))
        assertEquals(FieldErrorCode.TYPE, check(block("number"), "5"))
        assertEquals(FieldErrorCode.INVALID_OPTION, check(block("select", opts = listOf("a")), "b"))
        assertNull(check(block("radio", opts = listOf("a")), "a"))
        assertEquals(FieldErrorCode.INVALID_OPTION, check(block("checkbox", opts = listOf("a", "b")), listOf("a", "z")))
        assertNull(check(block("checkbox", opts = listOf("a", "b")), listOf("a", "b")))
        assertEquals(FieldErrorCode.REQUIRED, check(block("checkbox", required = true, opts = listOf("a")), emptyList<String>()))
        // 未知のブロック ID は type エラー。
        val form = FeedpulseForm("i", "s", "n", "bug", 1, FeedpulseFormDefinition(false, emptyList()))
        assertEquals(FieldErrorCode.TYPE, FormValidator.validate(form, mapOf("zzz" to "x"))["zzz"])
    }

    // ---------- single-flight / キャンセル ----------

    @Test
    fun concurrentInvalidSession_refetchesSessionOnlyOnce() {
        val challenges = AtomicInteger()
        val sessions = AtomicInteger()
        val rejected = CountDownLatch(2)
        val transport = HttpTransport { req ->
            val path = req.url.substringAfter("//").substringAfter('/').let { "/" + it.substringBefore('?') }
            when (path) {
                "/v1/native/challenge" -> {
                    challenges.incrementAndGet()
                    HttpResponse(200, emptyMap(), respBody("challenge-ok").toString())
                }
                "/v1/native/session" -> {
                    val n = sessions.incrementAndGet()
                    HttpResponse(200, emptyMap(), JSONObject().put("session", "s$n").put("expiresAt", now / 1000 + 900).toString())
                }
                "/v1/config" ->
                    if (req.headers["Authorization"] == "Bearer s1") {
                        // 2 本とも s1 で出てから、揃って invalid_session を返す。
                        rejected.countDown()
                        rejected.await(5, TimeUnit.SECONDS)
                        HttpResponse(401, emptyMap(), respBody("config-session-expired").toString())
                    } else {
                        HttpResponse(200, emptyMap(), respBody("config-ok").toString())
                    }
                else -> HttpResponse(404, emptyMap(), "{}")
            }
        }
        val c = client(transport = transport)
        runBlocking {
            val results = (1..2).map { async(Dispatchers.Default) { c.refreshConfig() } }.awaitAll()
            results.forEach { assertFalse(it.isStale) }
        }
        assertEquals(0, rejected.count)
        assertEquals("challenge は初回 + invalid_session 後の 1 回だけ", 2, challenges.get())
        assertEquals("session も初回 + invalid_session 後の 1 回だけ", 2, sessions.get())
    }

    @Test
    fun refreshConfig_staleResponseDoesNotOverwriteNewerConfig() {
        val configCalls = AtomicInteger()
        val oldReleased = CountDownLatch(1)
        val oldStarted = CountDownLatch(1)
        fun body(version: Int) = JSONObject(respBody("config-ok").toString()).also {
            it.getJSONArray("forms").getJSONObject(0).put("version", version)
        }.toString()
        val transport = HttpTransport { req ->
            val path = req.url.substringAfter("//").substringAfter('/').let { "/" + it.substringBefore('?') }
            when (path) {
                "/v1/native/challenge" -> HttpResponse(200, emptyMap(), respBody("challenge-ok").toString())
                "/v1/native/session" -> HttpResponse(200, emptyMap(), JSONObject().put("session", "s1").put("expiresAt", now / 1000 + 900).toString())
                "/v1/config" ->
                    if (configCalls.incrementAndGet() == 1) {
                        // 1 本目（フォーム変更前の要求）: 2 本目が終わるまで応答を保留する。
                        oldStarted.countDown()
                        oldReleased.await(5, TimeUnit.SECONDS)
                        HttpResponse(200, emptyMap(), body(3))
                    } else {
                        HttpResponse(200, emptyMap(), body(4))
                    }
                else -> HttpResponse(404, emptyMap(), "{}")
            }
        }
        val store = InMemoryConfigStore()
        val c = client(transport = transport, store = store)
        runBlocking {
            val old = async(Dispatchers.Default) { c.refreshConfig() }
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            val newer = c.refreshConfig()
            assertEquals(4, newer.forms.single().version)
            oldReleased.countDown()
            val oldResult = old.await()
            assertEquals("古い応答は破棄され新しい定義が返る", 4, oldResult.forms.single().version)
            assertFalse(oldResult.isStale)
        }
        assertEquals(4, c.config.value!!.forms.single().version)
        assertEquals("保存値も新しい定義のまま", 4, ConfigCodec.decodeCache(store.load("${server.url("/").toString().trimEnd('/')}|pk_example")!!)!!.forms.single().version)
    }

    @Test
    fun cancellingScope_disconnectsInFlightRequest() {
        happyRoutes()
        route("/v1/native/challenge", MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = client() // 既定の UrlConnectionTransport（読み取りタイムアウト 30 秒）
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                started.complete(Unit)
                c.ensureSession(false)
            }
            started.await()
            withTimeout(5_000) { while (synchronized(this@FeedpulseClientTest) { seen.isEmpty() }) delay(10) }
            val t0 = System.nanoTime()
            job.cancel()
            // 接続が切られるので、30 秒の読み取りタイムアウトを待たず直ちに終わる。
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000)
        }
    }

    @Test
    fun cancelledBeforeConnect_abortsWithoutSendingRequest() {
        happyRoutes()
        val handle = CancelHandle().apply { cancel() }
        val req = HttpRequest("GET", server.url("/v1/config").toString(), emptyMap())
        try {
            UrlConnectionTransport().execute(req, handle)
            fail("IOException が投げられなかった")
        } catch (_: IOException) {
        }
        assertTrue(seen.isEmpty())
    }
}
