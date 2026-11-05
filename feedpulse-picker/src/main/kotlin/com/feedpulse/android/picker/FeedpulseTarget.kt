package com.feedpulse.android.picker

/** 要素の画面上の矩形（px、画面左上原点）。 */
data class FeedpulseRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun toMap(): Map<String, Any> = mapOf("x" to x, "y" to y, "w" to w, "h" to h)
}

/**
 * 投稿の `context.auto.target` に載せる「該当箇所」。web の共有スキーマ（#106）と同形:
 * `platform` / `screen` / `id` / `path` / `rect` / `label`。
 *
 * - [id]: 明示 ID（`feedpulseId`）。あれば最優先で、自動取得の [path] より信頼できる。
 * - [path]: 自動取得したビュー階層（補助。R8 / リソース縮小で壊れうる）。
 * - [label]: contentDescription またはテキストの先頭 N 文字。入力欄の値は入らない。
 */
data class FeedpulseTarget(
    val screen: String,
    val id: String? = null,
    val path: String? = null,
    val rect: FeedpulseRect? = null,
    val label: String? = null,
) {
    val platform: String get() = PLATFORM

    /** null のフィールドを省いた Map（JSON / RN の WritableMap に変換しやすい形）。 */
    fun toMap(): Map<String, Any> = buildMap {
        put("platform", platform)
        put("screen", screen)
        id?.let { put("id", it) }
        path?.let { put("path", it) }
        rect?.let { put("rect", it.toMap()) }
        label?.let { put("label", it) }
    }

    /** `context.auto.target` にそのまま入れられる JSON 文字列。 */
    fun toJson(): String = Json.encode(toMap())

    companion object {
        const val PLATFORM = "android"
    }
}

/** 最小の JSON エンコーダ（org.json は JVM 単体テストでスタブになるため自前）。 */
internal object Json {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
