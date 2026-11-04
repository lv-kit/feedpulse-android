package com.feedpulse.android.picker

/** サーバー側（#106）の上限に合わせた長さ制限。サーバーは超過した target 全体を捨てるので SDK 側で切る。 */
internal object TargetLimits {
    const val SCREEN = 256
    const val ID = 128
    const val PATH = 512
    const val LABEL_MAX = 120 // PickerOptions.labelMaxChars の上限
}

internal object Sanitize {
    /** 制御文字を空白にし、連続空白を畳み、trim して先頭 [max] 文字（サロゲートペアを割らない）に切る。空なら null。 */
    fun text(raw: CharSequence?, max: Int): String? {
        if (raw == null || max <= 0) return null
        val sb = StringBuilder()
        var lastSpace = true
        for (c in raw) {
            val blank = c.isISOControl() || c.isWhitespace()
            if (blank) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c); lastSpace = false
            }
        }
        var s = sb.toString().trimEnd()
        if (s.length > max) {
            var end = max
            if (Character.isHighSurrogate(s[end - 1])) end--
            s = s.substring(0, end)
        }
        return s.ifEmpty { null }
    }
}
