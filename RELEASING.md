# リリース手順

1. 各モジュールの `build.gradle.kts` の `version` を更新し、`CHANGELOG.md` に新バージョンの節を追加する。
2. `./gradlew build publishToMavenLocal` が通ることを確認し、PR で `main` に取り込む。
3. `main` で `git tag vX.Y.Z` を打って push する。
4. タグ `vX.Y.Z` の push で `.github/workflows/release.yml` が GitHub Packages（`https://maven.pkg.github.com/lv-kit/feedpulse-android`）へ公開する。タグ名とモジュールの `version` を一致させる。

## 利用側の設定

GitHub Packages は読み取りにも認証が要る。`read:packages` 権限のトークンで、利用側の `repositories` に追加する。

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/lv-kit/feedpulse-android")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
        password = providers.gradleProperty("gpr.key").orNull
    }
}
```
