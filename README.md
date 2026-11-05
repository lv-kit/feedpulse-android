# feedpulse-android

Feedpulse Android SDK。ヘッドレス（UI・スクリーンショット無し）。

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
    implementation("com.feedpulse:feedpulse-picker:0.1.1")   // 本体（View 対応）
    implementation("com.feedpulse:feedpulse-compose:0.1.1")  // Jetpack Compose を使う場合のみ追加
}
```

`feedpulse-compose` は追加するだけで有効になる（初期化コードは不要）。R8 / ProGuard のルールは consumer ルールとして同梱している。Gradle を同期すれば `import com.feedpulse.android.picker.Feedpulse` で使える。

## 該当箇所ピッカー（#115）

画面上の要素を 1 タップで選び、投稿の `context.auto.target` に載せる値を返す。

```kotlin
val handle = Feedpulse.pickTarget(activity, PickerOptions(labelMaxChars = 40, screenName = "Checkout")) { result ->
    when (result) {
        is PickResult.Selected -> submit(contextAutoTarget = result.target.toMap()) // または toJson()
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
