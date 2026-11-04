package com.feedpulse.android.picker

import android.view.View

/**
 * この View に明示 ID を付ける。ピッカーで選ばれたとき `target.id` になり、自動取得（リソース名・クラス名）より優先される。
 * R8 / リソース縮小の影響を受けないので、本番ではこちらを使う。
 */
fun View.feedpulseId(id: String?): View = apply { setTag(R.id.feedpulse_id_tag, id) }

internal val View.explicitFeedpulseId: String?
    get() = (getTag(R.id.feedpulse_id_tag) as? String)?.let { Sanitize.text(it, TargetLimits.ID) }
