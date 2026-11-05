package com.feedpulse.android.picker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.WeakHashMap

/** [Feedpulse.pickTarget] のオプション。 */
data class PickerOptions(
    /** label に残す先頭文字数（0〜200）。0 なら label を返さない。 */
    val labelMaxChars: Int = 40,
    /** target.screen。null なら Activity のクラス名（R8 で変わるので本番では指定を推奨）。 */
    val screenName: String? = null,
)

/** ピッカーの結果。コールバックは必ずメインスレッドで 1 回だけ呼ばれる。 */
sealed class PickResult {
    data class Selected(val target: FeedpulseTarget) : PickResult()
    /** 戻るキー・[PickerHandle.cancel]・別の pickTarget・Activity 破棄で中断された。 */
    object Cancelled : PickResult()
    /** 機密保護のため開始しなかった（`FLAG_SECURE` の画面など）。 */
    data class Unavailable(val reason: Reason) : PickResult()

    enum class Reason { SECURE_WINDOW, ACTIVITY_FINISHING }
}

interface PickerHandle {
    val isActive: Boolean
    /** ピッカーを閉じる。コールバックには [PickResult.Cancelled] が届く。 */
    fun cancel()
}

/**
 * 画面上の該当箇所を選んで [FeedpulseTarget] を返す。UI・スクリーンショットは扱わない（ヘッドレス）。
 */
object Feedpulse {
    /** 既定の API ベース URL（本番の ingest）。 */
    const val DEFAULT_BASE_URL = "https://ingest.feedpulse.stargazy.xyz"

    @Volatile private var configured: FeedpulseClient? = null

    /**
     * SDK を初期化する。アプリ起動時（`Application.onCreate` など）に 1 回呼ぶ。再度呼ぶと置き換わる。
     * 通信は最初の [refreshConfig] / [submit] で始まる。
     *
     * @param publicKey 公開鍵（`pk_...`）。
     * @param baseUrl API のベース URL。
     * @param attestor アプリ証明の取得方法。既定は Play Integrity。
     */
    @JvmOverloads
    fun configure(
        context: Context,
        publicKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        attestor: AppAttestor = PlayIntegrityAttestor(context),
    ): FeedpulseClient {
        require(publicKey.isNotBlank()) { "publicKey が空" }
        val host = java.net.URI(baseUrl).host
        require(baseUrl.startsWith("https://") || host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") {
            "baseUrl は https（開発用の localhost / 127.0.0.1 / 10.0.2.2 のみ http 可）"
        }
        return FeedpulseClient(
            publicKey = publicKey,
            baseUrl = baseUrl,
            appId = context.applicationContext.packageName,
            attestor = attestor,
            transport = UrlConnectionTransport(),
            store = PrefsConfigStore(context),
        ).also { configured = it }
    }

    /** [configure] で作ったクライアント。未設定なら `NOT_CONFIGURED` を投げる。 */
    val client: FeedpulseClient
        get() = configured ?: throw FeedpulseException(FeedpulseErrorCode.NOT_CONFIGURED, "Feedpulse.configure を先に呼ぶ")

    /** [FeedpulseClient.identify] */
    fun identify(token: String?) = client.identify(token)

    /** [FeedpulseClient.refreshConfig] */
    suspend fun refreshConfig(): FeedpulseConfig = client.refreshConfig()

    /** [FeedpulseClient.submit] */
    suspend fun submit(
        formId: String,
        fields: Map<String, Any?>,
        target: FeedpulseTarget? = null,
        custom: Map<String, Any>? = null,
        idempotencyKey: String? = null,
    ): SubmitReceipt = client.submit(formId, fields, target, custom, idempotencyKey)

    private val active = WeakHashMap<Activity, Session>()
    private val extraResolvers = ArrayList<ViewHitResolver>()
    private var composeLoaded = false

    /** 自前の View 解決（独自描画ビュー等）を足す。`feedpulse-compose` は自動で登録される。 */
    @Synchronized
    fun registerViewResolver(resolver: ViewHitResolver) {
        extraResolvers.add(resolver)
    }

    @Synchronized
    private fun resolvers(): List<ViewHitResolver> {
        if (!composeLoaded) {
            composeLoaded = true
            // feedpulse-compose が依存に含まれている場合だけ読み込む（Compose 非依存のアプリでも落ちない）。
            runCatching {
                val k = Class.forName("com.feedpulse.android.compose.ComposeViewResolver")
                extraResolvers.add(k.getField("INSTANCE").get(null) as ViewHitResolver)
            }
        }
        return extraResolvers.toList()
    }

    /**
     * 全面の透明オーバーレイを [activity] に重ね、次のタップで選ばれた要素を [callback] に返す。
     * メインスレッドから呼ぶこと。`FLAG_SECURE` のウィンドウでは開始せず [PickResult.Unavailable] を返す。
     * Dialog / PopupWindow は別ウィンドウなので対象外。
     */
    @JvmOverloads
    fun pickTarget(
        activity: Activity,
        options: PickerOptions = PickerOptions(),
        callback: (PickResult) -> Unit,
    ): PickerHandle {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Feedpulse.pickTarget はメインスレッドから呼ぶ" }
        active[activity]?.finish(PickResult.Cancelled)

        if (activity.isFinishing || activity.isDestroyed) {
            callback(PickResult.Unavailable(PickResult.Reason.ACTIVITY_FINISHING))
            return Session.NONE
        }
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            callback(PickResult.Unavailable(PickResult.Reason.SECURE_WINDOW))
            return Session.NONE
        }
        val session = Session(activity, options, resolvers(), callback)
        active[activity] = session
        session.start()
        return session
    }

    private class Session(
        private val activity: Activity,
        private val options: PickerOptions,
        private val resolvers: List<ViewHitResolver>,
        private val callback: (PickResult) -> Unit,
    ) : PickerHandle {
        private var overlay: Overlay? = null
        private var done = false
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) { if (a === activity) finish(PickResult.Cancelled) }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        }

        override val isActive: Boolean get() = !done

        fun start() {
            val decor = activity.window.decorView as ViewGroup
            val o = Overlay(activity, ::onTap, { finish(PickResult.Cancelled) })
            overlay = o
            decor.addView(o, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            o.requestFocus()
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
        }

        private fun onTap(x: Int, y: Int) {
            val decor = activity.window.decorView
            val target = ViewTargetResolver.resolve(
                root = decor, screenX = x, screenY = y, exclude = overlay, resolvers = resolvers,
                screen = options.screenName ?: activity.javaClass.name,
                labelMaxChars = options.labelMaxChars,
            ) ?: return
            finish(PickResult.Selected(target))
        }

        override fun cancel() = finish(PickResult.Cancelled)

        fun finish(result: PickResult) {
            if (done) return
            done = true
            overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            overlay = null
            runCatching { activity.application.unregisterActivityLifecycleCallbacks(lifecycle) }
            if (active[activity] === this) active.remove(activity)
            callback(result)
        }

        companion object {
            val NONE = object : PickerHandle {
                override val isActive = false
                override fun cancel() {}
            }
        }
    }

    /** タップを奪う透明ビュー。描画しない。 */
    private class Overlay(
        context: Context,
        private val onTap: (Int, Int) -> Unit,
        private val onBack: () -> Unit,
    ) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(e.rawX - downX) <= slop && kotlin.math.abs(e.rawY - downY) <= slop) {
                        onTap(e.rawX.toInt(), e.rawY.toInt())
                    }
                }
            }
            return true // 下のビューにタップを渡さない
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
