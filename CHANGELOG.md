# Changelog

## 0.2.0

- SDK 本体を追加: `Feedpulse.configure` / `refreshConfig` / `submit` / `identify`。
- アプリ証明（Play Integrity）でネイティブセッションを取得し、`GET /v1/config`・`POST /v1/submit` に使う。セッションは 900 秒キャッシュし、期限前に更新する。
- 項目定義のローカル保存（通信失敗時は前回値）、項目定義に基づく入力検証（`FormValidator`）。
- 冪等キー付き送信と再送（バックオフ、429 の `Retry-After` 尊重）。`context.auto.target` / `context.identity` に対応。
- エラーを契約のエラーコードに対応する `FeedpulseException` / `FeedpulseErrorCode` で公開。
- 依存に `com.google.android.play:integrity` と `kotlinx-coroutines-core` を追加。`INTERNET` 権限を追加。
- 既存のピッカー API は変更なし。

## 0.1.1

- React Native の開発用オーバーレイ（`DebuggingOverlay`）を選択対象から外す。

## 0.1.0

- 該当箇所ピッカー（View 自動取得 + Compose `Modifier.feedpulseId`）を追加。
