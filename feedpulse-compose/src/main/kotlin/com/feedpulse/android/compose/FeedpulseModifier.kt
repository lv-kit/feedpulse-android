@file:JvmName("FeedpulseModifierKt")

package com.feedpulse.android.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/** Feedpulse ピッカー用の明示 ID を載せる semantics キー。 */
val FeedpulseId = SemanticsPropertyKey<String>(
    name = "FeedpulseId",
    mergePolicy = { parent, _ -> parent }, // 外側（親）の ID を優先
)

var SemanticsPropertyReceiver.feedpulseId: String by FeedpulseId

/**
 * この Composable に明示 ID を付ける。ピッカーで選ばれたとき `target.id` になり、
 * 自動取得（semantics ツリー）より常に優先される。R8 の影響を受けないので本番ではこちらを使う。
 *
 * `clearAndSetSemantics` など、子の semantics を消す Modifier より **後ろ** に置くと消えるので、
 * それらより前（外側）に置くこと。
 */
fun Modifier.feedpulseId(id: String): Modifier = semantics(mergeDescendants = false) { feedpulseId = id }
