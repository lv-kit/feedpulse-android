# core が reflection で読み込む（Compose 非依存アプリを壊さないため）。
-keep class com.feedpulse.android.compose.ComposeViewResolver { public static final ** INSTANCE; }
-keep class com.feedpulse.android.compose.FeedpulseModifierKt { *; }
