package com.feedpulse.android.picker

/**
 * web #106 の `target` 検証（sanitizeAutoContext）の写し。issue の受け入れ条件:
 * platform は許可値、各文字列に長さ上限、rect は有限の数値のみ。サーバー側の上限が確定したらここを合わせる。
 */
object TargetSchema {
    private val platforms = setOf("web", "ios", "android", "flutter", "react-native")
    private val keys = setOf("platform", "screen", "id", "path", "rect", "label")

    fun violations(m: Map<String, Any>): List<String> {
        val v = ArrayList<String>()
        for (k in m.keys) if (k !in keys) v += "unknown key $k"
        if (m["platform"] !in platforms) v += "platform"
        fun str(k: String, max: Int, required: Boolean = false) {
            val x = m[k]
            if (x == null) { if (required) v += "$k missing"; return }
            if (x !is String) { v += "$k type"; return }
            if (x.length > max) v += "$k too long"
            if (x.any { it.isISOControl() }) v += "$k control char"
        }
        str("screen", TargetLimits.SCREEN, required = true)
        str("id", TargetLimits.ID); str("path", TargetLimits.PATH); str("label", TargetLimits.LABEL_MAX)
        if (m.containsKey("rect")) {
            val r = m["rect"] as? Map<*, *>
            if (r == null) v += "rect type" else for (k in listOf("x", "y", "w", "h")) {
                val n = r[k]
                if (n !is Number || !n.toDouble().isFinite()) v += "rect.$k"
            }
        }
        return v
    }
}
