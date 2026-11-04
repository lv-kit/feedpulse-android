package com.feedpulse.android.picker

import android.view.View

/**
 * ヒットテストで見つかった 1 要素（View または Compose ノード）。
 *
 * @property id 明示 ID（`feedpulseId`）。無ければ null。
 * @property rect 画面座標（px）。
 * @property label 表示テキスト / contentDescription（切り詰め前でも可。SDK 側で切る）。入力値は入れない。
 * @property segment path 用の短い名前。
 */
class HitElement(
    val id: String?,
    val rect: FeedpulseRect,
    val label: CharSequence?,
    val segment: String,
)

/**
 * 特定の View（例: Compose の `AndroidComposeView`）の内部を、標準の View 走査の代わりに解決する拡張点。
 * `feedpulse-compose` が実装する。
 */
interface ViewHitResolver {
    /**
     * [view] 内の画面座標 ([screenX], [screenY]) にある要素を、最も内側から外側の順で返す。
     * [view] を扱わない場合は null。扱うが何も無ければ空リスト。
     */
    fun resolve(view: View, screenX: Int, screenY: Int): List<HitElement>?
}
