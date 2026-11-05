package com.feedpulse.android.picker

/**
 * 項目定義に基づくクライアント側の入力検証。サーバーと同じ語彙（[FieldErrorCode]）で返す。
 * 最終的な判定はサーバーが行う。ここは往復を省くための事前チェック。
 *
 * 値の型: short_text / long_text / email / select / radio = String、number / rating = Number、
 * checkbox = Collection<String>。image / file は 0.2.0 では扱わない（クライアント側では検証しない）。
 */
object FormValidator {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validate(form: FeedpulseForm, fields: Map<String, Any?>): Map<String, FieldErrorCode> {
        val errors = LinkedHashMap<String, FieldErrorCode>()
        val known = form.definition.blocks.map { it.id }.toSet()
        for (key in fields.keys) if (key !in known) errors[key] = FieldErrorCode.TYPE
        for (b in form.definition.blocks) {
            if (b.type == "image" || b.type == "file") continue
            val v = fields[b.id]
            if (isEmpty(v)) {
                if (b.required) errors[b.id] = FieldErrorCode.REQUIRED
                continue
            }
            check(b, v!!)?.let { errors[b.id] = it }
        }
        return errors
    }

    private fun isEmpty(v: Any?): Boolean = when (v) {
        null -> true
        is String -> v.isBlank()
        is Collection<*> -> v.isEmpty()
        else -> false
    }

    private fun check(b: FeedpulseBlock, v: Any): FieldErrorCode? = when (b.type) {
        "short_text", "long_text" -> when {
            v !is String -> FieldErrorCode.TYPE
            b.maxLength != null && v.length > b.maxLength -> FieldErrorCode.TOO_LONG
            else -> null
        }
        "email" -> when {
            v !is String -> FieldErrorCode.TYPE
            b.maxLength != null && v.length > b.maxLength -> FieldErrorCode.TOO_LONG
            !EMAIL.matches(v) -> FieldErrorCode.INVALID_EMAIL
            else -> null
        }
        "number", "rating" -> when {
            v !is Number || v.toDouble().isNaN() || v.toDouble().isInfinite() -> FieldErrorCode.TYPE
            b.min != null && v.toDouble() < b.min -> FieldErrorCode.OUT_OF_RANGE
            b.max != null && v.toDouble() > b.max -> FieldErrorCode.OUT_OF_RANGE
            else -> null
        }
        "select", "radio" -> when {
            v !is String -> FieldErrorCode.TYPE
            b.options.none { it.value == v } -> FieldErrorCode.INVALID_OPTION
            else -> null
        }
        "checkbox" -> when {
            v !is Collection<*> || v.any { it !is String } -> FieldErrorCode.TYPE
            v.any { x -> b.options.none { it.value == x } } -> FieldErrorCode.INVALID_OPTION
            else -> null
        }
        else -> null
    }
}
