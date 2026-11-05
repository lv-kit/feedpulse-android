# feedpulse-android

Feedpulse Android SDK。ヘッドレス（UI・スクリーンショット無し）。該当箇所ピッカー、項目定義の取得、入力検証、送信（アプリ証明つき）、身元確認トークンの受け渡しを提供する。

## 導入

> **利用には Feedpulse への登録が必要。** SDK を使う前に <https://feedpulse.stargazy.xyz/> でアカウントを登録し、プロジェクトを作成して公開鍵（`pk_`）を発行する。

要件: minSdk 23 以上 / compileSdk 36 / JDK 17。成果物は GitHub Packages（`https://maven.pkg.github.com/lv-kit/feedpulse-android`）で配布している。Maven Central には未公開。

### 1. リポジトリと認証を追加する

GitHub Packages は読み取りにも認証が要る。`read:packages` 権限の Personal Access Token を作り、`~/.gradle/gradle.properties` に書く（リポジトリにはコミットしない）。

```properties
gpr.user=<GitHub のユーザー名>
gpr.key=<read:packages 権限のトークン>
```

`settings.gradle.kts`（または `build.gradle.kts` の `allprojects`）に追加する。

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/lv-kit/feedpulse-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

CI では `gpr.user` / `gpr.key` の代わりに環境変数や CI のシークレットから渡す。

### 2. 依存を追加する

```kotlin
dependencies {
    implementation("com.feedpulse:feedpulse-picker:0.2.1")   // 本体（View 対応）
    implementation("com.feedpulse:feedpulse-compose:0.2.1")  // Jetpack Compose を使う場合のみ追加
}
```

`feedpulse-compose` は追加するだけで有効になる（初期化コードは不要）。R8 / ProGuard のルールは consumer ルールとして同梱している。Gradle を同期すれば `import com.feedpulse.android.picker.Feedpulse` で使える。

### 依存関係（`feedpulse-picker` が持ち込むもの）

- `com.google.android.play:integrity`（Play Integrity API。アプリ証明）
- `org.jetbrains.kotlinx:kotlinx-coroutines-core`（`StateFlow` / `suspend` を公開 API に使うため `api`）
- HTTP は `HttpURLConnection`（追加ライブラリなし）。`INTERNET` 権限を manifest マージで追加する。

## 使い方

```kotlin
// 1. Application.onCreate など。公開鍵は Feedpulse で発行した pk_...
Feedpulse.configure(applicationContext, publicKey = "pk_...")

// 2. 項目定義を取得（失敗時は前回の保存値。isStale = true）
val config = Feedpulse.refreshConfig()
val form = config.form("bug")!!

// 3. ログイン済みユーザーの身元を渡す（任意）。顧客バックエンドが sk_ で署名した JWT をそのまま渡す
Feedpulse.identify(jwtFromYourBackend) // null で解除

// 4. 送信
try {
    val receipt = Feedpulse.submit(
        formId = form.id,
        fields = mapOf("title" to "ログインできない", "severity" to "high"),
        target = pickedTarget,                    // pickTarget の結果（任意）
        custom = mapOf("plan" to "pro"),          // context.custom（任意）
    )
} catch (e: FeedpulseException) {
    when (e.code) {
        FeedpulseErrorCode.INVALID_FIELDS -> showErrors(e.fieldErrors) // ブロック ID → required / too_long / ...
        FeedpulseErrorCode.RATE_LIMITED -> Unit // 自動再送を使い切った後。e.retryAfterSeconds
        else -> Unit
    }
}
```

Jetpack Compose からは `Feedpulse.client.config`（`StateFlow<FeedpulseConfig?>`）と `Feedpulse.client.lastError`（`StateFlow<FeedpulseException?>`）を `collectAsState()` で購読できる。

### 公開 API

- `Feedpulse.configure(context, publicKey, baseUrl = DEFAULT_BASE_URL, attestor = PlayIntegrityAttestor(context))` → `FeedpulseClient`
- `Feedpulse.client` / `identify(token)` / `suspend refreshConfig()` / `suspend submit(...)`（`client` への委譲）
- `FeedpulseClient`: `config` / `lastError`（StateFlow）、`identify`、`refreshConfig`、`submit`
- `FormValidator.validate(form, fields)`: 送信前の入力検証（`submit` も内部で呼ぶ）
- `FeedpulseException(code: FeedpulseErrorCode, httpStatus, fieldErrors, identityCode, retryAfterSeconds)`。`code` は契約のエラーコード（`invalid_session`、`rate_limited`、`identity_invalid` など）と、SDK 側の `NETWORK` / `ATTESTATION_UNAVAILABLE` / `NOT_CONFIGURED` / `UNEXPECTED_RESPONSE`
- `AppAttestor`: アプリ証明の差し替え口（テスト用など）

### 挙動

- **セッション**: `POST /v1/native/challenge` → Play Integrity（classic request、nonce = challenge）→ `POST /v1/native/session`。セッションは 900 秒。期限の 60 秒前に取り直し、`invalid_session` を受けたら 1 回だけ取り直してやり直す。アプリ証明の失敗（`attestation_failed`）は再試行しない。`Origin` は付けず `X-FeedPulse-Platform: android` と Bearer を送る。
- **config**: `GET /v1/config`。成功したらローカル（SharedPreferences）に保存。通信失敗時は前回値を返す（前回値も無ければ例外）。
- **検証**: 項目定義（必須・文字数・選択肢・メール形式・数値の範囲）で送信前に検証し、不正なら送らず `INVALID_FIELDS`。`image` / `file` ブロックは 0.2.0 では未対応（検証も送信もしない）。
- **送信**: 冪等キー（SDK が生成、`idempotencyKey` 引数で指定も可）を付け、通信失敗・429・5xx は同じキーで最大 3 回再送（1 秒・2 秒・4 秒。429 の `Retry-After` を優先、上限 120 秒）。`context.auto` に `os` / `locale` / `timezone` を載せ、フォームが `allowTarget` のときだけ `target` を `context.auto.target` に載せる。`identify` したトークンは `context.identity`。
- API のベース URL の既定は本番（`https://ingest.feedpulse.stargazy.xyz`）。ローカル開発では `baseUrl` を指定する。

### 検証状況と制約

- 単体テストは契約 fixture（`feedpulse-picker/src/test/resources/contract.json`、web の `packages/shared/src/native/fixtures/contract.json` の写し）をモックサーバー（MockWebServer）で再生して検証している。
- **Play Integrity と実機での送信は未検証**（Play ストアのある実機と、Feedpulse 側のアプリ登録・Play Integrity の設定が要る）。テストでは `AppAttestor` を差し替えている。
- Play Integrity は classic request のみ対応（サーバーが復号した nonce と challenge を照合するため）。`dev_insecure` は送らない。
- Feedpulse 側で、プロジェクトに Android アプリ（package name と署名証明書の SHA-256）の登録が必要。

## 該当箇所ピッカー（#115）

画面上の要素を 1 タップで選び、投稿の `context.auto.target` に載せる値を返す。

```kotlin
val handle = Feedpulse.pickTarget(activity, PickerOptions(labelMaxChars = 40, screenName = "Checkout")) { result ->
    when (result) {
        is PickResult.Selected -> Feedpulse.submit(formId, fields, target = result.target)
        PickResult.Cancelled -> Unit
        is PickResult.Unavailable -> Unit // FLAG_SECURE の画面など
    }
}
handle.cancel() // 途中で閉じる
```

- 全面の透明オーバーレイを Activity の decor view に足し、次のタップを奪う。戻るキー / `cancel()` / Activity 破棄で `Cancelled`。
- メインスレッド専用。コールバックは 1 回だけ。Dialog / PopupWindow（別ウィンドウ）は対象外。
- `FLAG_SECURE` のウィンドウでは開始しない（`Unavailable(SECURE_WINDOW)`）。
- `EditText` / パスワード入力の値は取らない（`contentDescription` のみ）。`WebView` 内は取らず、WebView 全体の bounds と URL（クエリ・フラグメント除去）だけ `path` に入る。
- 明示 ID が自動取得より常に優先される（最も内側の明示 ID を持つ祖先または自身が選ばれる）。

### 明示 ID（本番ではこちらに頼る）

R8 / リソース縮小でクラス名・リソース名は壊れうる。

```kotlin
// View
button.feedpulseId("checkout.pay")
// Compose（feedpulse-compose）
Button(onClick = {}, modifier = Modifier.feedpulseId("checkout.pay")) { ... }
```

Compose の自動取得（text / testTag）は `ViewRootForTest`（半内部 API）経由の補助。取れなくても明示 ID は動く。
`feedpulse-compose` を依存に入れるだけで有効になる（reflection で自動登録。consumer ProGuard ルール同梱）。

### target

`{"platform":"android","screen":"…","id":"…","path":"…","rect":{"x","y","w","h"},"label":"…"}`。null のキーは出さない。rect は画面座標の px。
上限（`TargetLimits`）: screen 512 / id 200 / path 512 / label 200 文字（web #106 の TARGET_LIMITS と同じ）。rect は有限・|値|≤1,000,000・w/h≥0。id か path のどちらか必須。

## ビルド

JDK 17 + Android SDK。`JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test`
