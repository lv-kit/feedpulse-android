package com.feedpulse.android.picker

import android.content.res.Resources
import android.graphics.Rect
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView

/** タップ座標から [FeedpulseTarget] を組み立てる。UI にもウィンドウにも依存しない（テスト可能）。 */
internal object ViewTargetResolver {

    fun resolve(
        root: View,
        screenX: Int,
        screenY: Int,
        exclude: View?,
        resolvers: List<ViewHitResolver>,
        screen: String,
        labelMaxChars: Int,
    ): FeedpulseTarget? {
        // root → leaf の View 列、または特殊 resolver が返した内側要素列。
        val viewChain = ArrayList<View>()
        var inner: List<HitElement> = emptyList()
        var cur: View? = root
        while (cur != null) {
            viewChain.add(cur)
            val special = resolvers.firstNotNullOfOrNull { runCatching { it.resolve(cur!!, screenX, screenY) }.getOrNull() }
            if (special != null) { inner = special; break }
            cur = if (cur is WebView) null else hitChild(cur, screenX, screenY, exclude)
        }
        if (viewChain.isEmpty()) return null

        // 外側 → 内側の要素列。
        val elements = ArrayList<HitElement>()
        for (v in viewChain) elements.add(viewElement(v))
        elements.addAll(inner.asReversed())

        // 明示 ID を持つ最も内側の要素を優先。無ければ最も内側（leaf）。
        val idx = elements.indexOfLast { it.id != null }.takeIf { it >= 0 } ?: elements.lastIndex
        val chosen = elements[idx]

        val path = buildPath(elements.subList(0, idx + 1).map { it.segment })
        return FeedpulseTarget(
            screen = Sanitize.text(screen, TargetLimits.SCREEN) ?: "unknown",
            id = chosen.id,
            path = path,
            rect = chosen.rect,
            label = Sanitize.text(chosen.label, labelMaxChars.coerceIn(0, TargetLimits.LABEL_MAX)),
        )
    }

    /** 利用者の画面ではなく、開発ツールが全面に重ねる透明な View。選択対象にしない。 */
    private val IGNORED_VIEW_CLASSES = setOf("com.facebook.react.views.debuggingoverlay.DebuggingOverlay")

    private fun hitChild(parent: View, x: Int, y: Int, exclude: View?): View? {
        if (parent !is ViewGroup) return null
        // Z の高い View が前面。同じ Z なら追加順が後の View を優先する。
        for (c in (0 until parent.childCount).map { parent.getChildAt(it) }.asReversed().sortedByDescending { it.z }) {
            if (c === exclude || c.visibility != View.VISIBLE || c.alpha <= 0f) continue
            if (c.javaClass.name in IGNORED_VIEW_CLASSES) continue
            if (screenRect(c).contains(x, y)) return c
        }
        return null
    }

    private fun screenRect(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun viewElement(v: View): HitElement {
        val r = screenRect(v)
        return HitElement(
            id = v.explicitFeedpulseId,
            rect = FeedpulseRect(r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble()),
            label = labelOf(v),
            segment = segmentOf(v),
        )
    }

    /** 入力欄（EditText / パスワード）は contentDescription 以外を一切読まない。WebView は何も読まない。 */
    private fun labelOf(v: View): CharSequence? {
        if (v is WebView) return null
        val desc = v.contentDescription?.takeIf { it.isNotBlank() }
        if (isTextInput(v)) return desc
        return desc ?: (v as? TextView)?.text
    }

    private fun isTextInput(v: View): Boolean {
        if (v is EditText) return true
        if (v !is TextView) return false
        if (v.transformationMethod is PasswordTransformationMethod) return true
        val type = v.inputType
        if (type and InputType.TYPE_MASK_CLASS == 0) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** `scheme://user:password@host` の userinfo（認証情報）。authority 内（`/` `\` まで）の最後の `@` までを除く。 */
    private val USERINFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/\\\\]*@")

    private fun segmentOf(v: View): String {
        val cls = v.javaClass.simpleName.ifEmpty { v.javaClass.name.substringAfterLast('.') }
        val res = if (v.id != View.NO_ID) {
            try { v.resources.getResourceEntryName(v.id) } catch (_: Resources.NotFoundException) { null }
        } else null
        val base = if (res != null) "$cls#$res" else cls
        if (v is WebView) {
            // WebView 内の要素は取れない。WebView 全体として URL（クエリ・フラグメント・userinfo 無し）だけ残す。
            val url = runCatching { v.url }.getOrNull()
                ?.substringBefore('#')?.substringBefore('?')
                ?.replace(USERINFO, "$1")
            if (!url.isNullOrEmpty()) return "$base[$url]"
        }
        return base
    }

    /** 長すぎるときは根側を省略して、選択要素に近い側を残す。 */
    private fun buildPath(segments: List<String>): String? {
        if (segments.isEmpty()) return null
        var s = segments.joinToString(" > ") { Sanitize.text(it, 128) ?: "?" }
        if (s.length > TargetLimits.PATH) {
            s = "…" + s.takeLast(TargetLimits.PATH - 1)
        }
        return s
    }
}
