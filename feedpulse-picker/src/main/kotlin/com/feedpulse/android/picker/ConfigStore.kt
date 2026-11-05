package com.feedpulse.android.picker

import android.content.Context

/** 取得済み config の保存先。失敗時に前回値を使うために使う。 */
interface ConfigStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
}

internal class PrefsConfigStore(context: Context) : ConfigStore {
    private val prefs = context.applicationContext.getSharedPreferences("feedpulse_config", Context.MODE_PRIVATE)
    override fun load(key: String): String? = prefs.getString(key, null)
    override fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

class InMemoryConfigStore : ConfigStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun load(key: String): String? = map[key]
    @Synchronized override fun save(key: String, value: String) { map[key] = value }
}
