@file:OptIn(InternalComposeUiApi::class)

package com.feedpulse.android.compose

import android.view.View
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.feedpulse.android.picker.FeedpulseRect
import com.feedpulse.android.picker.HitElement
import com.feedpulse.android.picker.ViewHitResolver

/**
 * Compose の semantics ツリーでヒットテストする。`semanticsOwner` は Compose の半内部 API
 * （`ViewRootForTest`）経由なので、将来のバージョンで取得できなくなる可能性がある。
 * その場合は null（= View としてだけ扱う）を返し、明示 ID の無い Compose は AndroidComposeView 全体になる。
 * 明示 ID（[feedpulseId]）は semantics の公開 API だけで読むため、この resolver が動く限り安定する。
 */
object ComposeViewResolver : ViewHitResolver {

    override fun resolve(view: View, screenX: Int, screenY: Int): List<HitElement>? {
        val root = (view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val wx = (screenX - loc[0]).toFloat()
        val wy = (screenY - loc[1]).toFloat()
        // 画面座標でなく View ローカル座標のノード矩形に揃える。
        val chain = ArrayList<SemanticsNode>() // 外側 → 内側
        var node: SemanticsNode = root
        if (!node.boundsInRoot.containsPoint(wx, wy)) return emptyList()
        chain.add(node)
        while (true) {
            // children は描画順（後ろほど手前）。手前から探す。
            val hit = node.children.lastOrNull {
                it.boundsInRoot.containsPoint(wx, wy)
            } ?: break
            chain.add(hit)
            node = hit
        }
        return chain.asReversed().map { it.toElement(loc[0], loc[1]) }
    }

    private fun Rect.containsPoint(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    private fun SemanticsNode.toElement(offX: Int, offY: Int): HitElement {
        val b = boundsInRoot
        return HitElement(
            id = config.getOrNull(FeedpulseId),
            rect = FeedpulseRect(
                (b.left + offX).toDouble(), (b.top + offY).toDouble(),
                b.width.toDouble(), b.height.toDouble(),
            ),
            label = labelOf(this),
            segment = segmentOf(this),
        )
    }

    /** 入力欄（EditableText / パスワード / SetText を持つ）は contentDescription だけ。値は読まない。 */
    private fun labelOf(n: SemanticsNode): CharSequence? {
        val c = n.config
        val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        val isInput = c.contains(SemanticsProperties.EditableText) ||
            c.contains(SemanticsProperties.Password) ||
            c.contains(SemanticsActions.SetText)
        if (isInput) return desc
        return desc ?: c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    }

    private fun segmentOf(n: SemanticsNode): String {
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        val role = n.config.getOrNull(SemanticsProperties.Role)?.toString()
        val base = role?.let { "Compose:$it" } ?: "Compose"
        return if (tag != null) "$base#$tag" else base
    }
}
