package com.feedpulse.android.picker

/** 契約（web の error-response スキーマ）のエラーコードと、SDK 内で起きるエラー。 */
enum class FeedpulseErrorCode(val wire: String?) {
    INVALID_JSON("invalid_json"),
    INVALID_PK("invalid_pk"),
    INVALID_PLATFORM("invalid_platform"),
    INVALID_APP("invalid_app"),
    INVALID_CHALLENGE("invalid_challenge"),
    CHALLENGE_EXPIRED("challenge_expired"),
    ATTESTATION_FAILED("attestation_failed"),
    APP_NOT_REGISTERED("app_not_registered"),
    INVALID_SESSION("invalid_session"),
    RATE_LIMITED("rate_limited"),
    UNKNOWN_PROJECT("unknown_project"),
    UNKNOWN_FORM("unknown_form"),
    INVALID_FORM("invalid_form"),
    INVALID_IDEMPOTENCY_KEY("invalid_idempotency_key"),
    INVALID_FIELDS("invalid_fields"),
    IDENTITY_INVALID("identity_invalid"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    QUOTA_EXCEEDED("quota_exceeded"),
    PROJECT_READ_ONLY("project_read_only"),
    NOT_FOUND("not_found"),
    INTERNAL("internal"),

    // ---- SDK 側で起きるもの（契約のコードではない） ----
    /** [Feedpulse.configure] 前の呼び出し、または不正な引数。 */
    NOT_CONFIGURED(null),
    /** 通信できなかった（接続失敗・タイムアウト）。 */
    NETWORK(null),
    /** Play Integrity のトークンを取得できなかった（Play ストア無し・端末非対応など）。 */
    ATTESTATION_UNAVAILABLE(null),
    /** サーバーが契約外の応答を返した。 */
    UNEXPECTED_RESPONSE(null);

    companion object {
        fun fromWire(value: String?): FeedpulseErrorCode? = entries.firstOrNull { it.wire != null && it.wire == value }
    }
}

/** `invalid_fields` の検証エラーコード（サーバーと同じ語彙）。 */
enum class FieldErrorCode(val wire: String) {
    REQUIRED("required"),
    TYPE("type"),
    TOO_LONG("too_long"),
    INVALID_OPTION("invalid_option"),
    INVALID_EMAIL("invalid_email"),
    OUT_OF_RANGE("out_of_range"),
    TOO_MANY_FILES("too_many_files"),
    INVALID_UPLOAD("invalid_upload");

    companion object {
        fun fromWire(value: String?): FieldErrorCode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * SDK が投げる唯一の例外型。
 *
 * - [code]: 契約のエラーコード、または SDK 側のコード。
 * - [httpStatus]: サーバー応答のとき。
 * - [fieldErrors]: [FeedpulseErrorCode.INVALID_FIELDS] のとき、ブロック ID → 検証エラー。クライアント側検証でも同じ形で返す。
 * - [identityCode]: [FeedpulseErrorCode.IDENTITY_INVALID] のとき、サーバーが返した理由（`expired` など）。
 * - [retryAfterSeconds]: `Retry-After` ヘッダー（429 など）。
 */
class FeedpulseException(
    val code: FeedpulseErrorCode,
    message: String? = null,
    val httpStatus: Int? = null,
    val fieldErrors: Map<String, FieldErrorCode> = emptyMap(),
    val identityCode: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message ?: code.wire ?: code.name.lowercase(), cause) {
    /** 同じ送信を再試行してよいエラーか（通信失敗・429・5xx）。 */
    val isRetryable: Boolean
        get() = code == FeedpulseErrorCode.NETWORK ||
            code == FeedpulseErrorCode.RATE_LIMITED ||
            code == FeedpulseErrorCode.INTERNAL ||
            (httpStatus == 429 && code != FeedpulseErrorCode.QUOTA_EXCEEDED) ||
            (httpStatus != null && httpStatus >= 500)
}
