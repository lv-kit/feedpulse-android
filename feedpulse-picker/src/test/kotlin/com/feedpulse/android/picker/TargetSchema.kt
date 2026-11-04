package com.feedpulse.android.picker

/**
 * web #106 の sanitizeTarget（packages/shared/src/context.ts）の写し。
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
        str("screen", TargetLimits.SCREEN)
        if (m["id"] == null && m["path"] == null) v += "id or path required"
        str("id", TargetLimits.ID); str("path", TargetLimits.PATH); str("label", TargetLimits.LABEL_MAX)
        if (m.containsKey("rect")) {
            val r = m["rect"] as? Map<*, *>
            if (r == null) v += "rect type" else for (k in listOf("x", "y", "w", "h")) {
                val n = r[k]
                if (n !is Number || !n.toDouble().isFinite() || Math.abs(n.toDouble()) > 1_000_000) v += "rect.$k"
                else if ((k == "w" || k == "h") && n.toDouble() < 0) v += "rect.$k negative"
            }
        }
        return v
    }
}
