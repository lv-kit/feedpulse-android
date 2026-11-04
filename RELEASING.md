# リリース手順

1. 各モジュールの `build.gradle.kts` の `version` を更新し、`CHANGELOG.md` に新バージョンの節を追加する。
2. `./gradlew build publishToMavenLocal` が通ることを確認し、PR で `main` に取り込む。
3. `main` で `git tag vX.Y.Z` を打って push する。
4. 公開先のリポジトリ（Maven Central など）へ `publish` する。公開先の認証情報とリポジトリ設定は未整備で、メンテナが決めて追加する。
