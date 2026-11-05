package com.feedpulse.android.picker

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * アプリ証明の取得を抽象化する。既定は [PlayIntegrityAttestor]。
 * 単体テストや独自の証明方式では差し替える。
 */
fun interface AppAttestor {
    /** サーバー発行の [challenge] を nonce にして、証明トークン（Play Integrity のトークン）を返す。失敗は [FeedpulseException]。 */
    suspend fun attest(challenge: String): String
}

/**
 * Play Integrity API（classic request）でトークンを取る。nonce にサーバーの challenge をそのまま使う
 * （サーバーは復号した判定の nonce == challenge を照合するため、standard request は使わない）。
 *
 * @param cloudProjectNumber 任意。指定すると Play Integrity の GCP プロジェクトに紐づける。
 */
class PlayIntegrityAttestor(
    context: Context,
    private val cloudProjectNumber: Long? = null,
) : AppAttestor {
    private val appContext = context.applicationContext

    override suspend fun attest(challenge: String): String = suspendCancellableCoroutine { cont ->
        try {
            val builder = IntegrityTokenRequest.builder().setNonce(challenge)
            if (cloudProjectNumber != null) builder.setCloudProjectNumber(cloudProjectNumber)
            IntegrityManagerFactory.create(appContext)
                .requestIntegrityToken(builder.build())
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.token()) }
                .addOnFailureListener {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            FeedpulseException(
                                FeedpulseErrorCode.ATTESTATION_UNAVAILABLE,
                                "Play Integrity のトークンを取得できない: ${it.message}",
                                cause = it,
                            ),
                        )
                    }
                }
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resumeWithException(
                    FeedpulseException(FeedpulseErrorCode.ATTESTATION_UNAVAILABLE, e.message, cause = e),
                )
            }
        }
    }
}
