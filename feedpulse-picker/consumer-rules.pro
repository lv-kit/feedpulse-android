# 公開 API（RN などのブリッジが参照する）。
-keep class com.feedpulse.android.picker.Feedpulse { *; }
-keep class com.feedpulse.android.picker.FeedpulseTarget { *; }
-keep class com.feedpulse.android.picker.FeedpulseRect { *; }
-keep class com.feedpulse.android.picker.PickResult { *; }
-keep class com.feedpulse.android.picker.PickResult$* { *; }
-keep class com.feedpulse.android.picker.PickerOptions { *; }
-keep class com.feedpulse.android.picker.PickerHandle { *; }
-keep class com.feedpulse.android.picker.ViewHitResolver { *; }
-keep class com.feedpulse.android.picker.HitElement { *; }
# 注意: R8 はリソース名・クラス名を変更しない。ただし `getResourceEntryName` は
# リソース縮小（shrinkResources + 名前難読化）されると壊れる。本番では明示 ID
# （View.feedpulseId / Modifier.feedpulseId）を使うこと。
