package com.feedpulse.android.picker

import org.json.JSONArray
import org.json.JSONObject

data class FeedpulseOption(val value: String, val label: String)

/** フォームの項目（ブロック）定義。`type` は short_text / long_text / email / number / select / radio / checkbox / rating / image / file。 */
data class FeedpulseBlock(
    val id: String,
    val type: String,
    val label: String,
    val required: Boolean,
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<FeedpulseOption> = emptyList(),
    val maxFiles: Int? = null,
    val maxTotalBytes: Long? = null,
)

data class FeedpulseFormDefinition(
    val allowTarget: Boolean,
    val blocks: List<FeedpulseBlock>,
)

data class FeedpulseForm(
    val id: String,
    val slug: String,
    val name: String,
    /** `inquiry` / `bug` / `improvement`。 */
    val type: String,
    /** 公開済みの版。 */
    val version: Int,
    val definition: FeedpulseFormDefinition,
)

/** `GET /v1/config` の内容。 */
data class FeedpulseConfig(
    val forms: List<FeedpulseForm>,
    /** 取得時刻（epoch ms）。 */
    val fetchedAtMillis: Long,
    /** 通信に失敗して前回の保存値を返しているとき true。 */
    val isStale: Boolean = false,
) {
    fun form(slug: String): FeedpulseForm? = forms.firstOrNull { it.slug == slug }
    fun formById(id: String): FeedpulseForm? = forms.firstOrNull { it.id == id }
}

/** `POST /v1/submit` の受理結果（202）。 */
data class SubmitReceipt(val id: String)

internal object ConfigCodec {
    fun parseForms(json: JSONObject): List<FeedpulseForm> {
        val arr = json.getJSONArray("forms")
        return (0 until arr.length()).map { parseForm(arr.getJSONObject(it)) }
    }

    private fun parseForm(o: JSONObject): FeedpulseForm {
        val def = o.getJSONObject("definition")
        val blocks = def.getJSONArray("blocks")
        return FeedpulseForm(
            id = o.getString("id"),
            slug = o.getString("slug"),
            name = o.getString("name"),
            type = o.getString("type"),
            version = o.getInt("version"),
            definition = FeedpulseFormDefinition(
                allowTarget = def.optBoolean("allowTarget", false),
                blocks = (0 until blocks.length()).map { parseBlock(blocks.getJSONObject(it)) },
            ),
        )
    }

    private fun parseBlock(o: JSONObject): FeedpulseBlock {
        val opts = o.optJSONArray("options")
        return FeedpulseBlock(
            id = o.getString("id"),
            type = o.getString("type"),
            label = o.getString("label"),
            required = o.getBoolean("required"),
            maxLength = if (o.has("maxLength")) o.getInt("maxLength") else null,
            min = if (o.has("min")) o.getDouble("min") else null,
            max = if (o.has("max")) o.getDouble("max") else null,
            options = if (opts == null) emptyList() else (0 until opts.length()).map {
                val x = opts.getJSONObject(it)
                FeedpulseOption(x.getString("value"), x.getString("label"))
            },
            maxFiles = if (o.has("maxFiles")) o.getInt("maxFiles") else null,
            maxTotalBytes = if (o.has("maxTotalBytes")) o.getLong("maxTotalBytes") else null,
        )
    }

    fun encodeForms(forms: List<FeedpulseForm>, fetchedAtMillis: Long): String {
        val arr = forms.map { f ->
            mapOf(
                "id" to f.id, "slug" to f.slug, "name" to f.name, "type" to f.type, "version" to f.version,
                "definition" to buildMap {
                    put("allowTarget", f.definition.allowTarget)
                    put(
                        "blocks",
                        f.definition.blocks.map { b ->
                            buildMap<String, Any> {
                                put("id", b.id); put("type", b.type); put("label", b.label); put("required", b.required)
                                b.maxLength?.let { put("maxLength", it) }
                                b.min?.let { put("min", it) }
                                b.max?.let { put("max", it) }
                                if (b.options.isNotEmpty()) put("options", b.options.map { mapOf("value" to it.value, "label" to it.label) })
                                b.maxFiles?.let { put("maxFiles", it) }
                                b.maxTotalBytes?.let { put("maxTotalBytes", it) }
                            }
                        },
                    )
                },
            )
        }
        return Json.encode(mapOf("fetchedAt" to fetchedAtMillis, "forms" to arr))
    }

    /** 保存値の復元。壊れていたら null（キャッシュ無し扱い）。 */
    fun decodeCache(text: String): FeedpulseConfig? = try {
        val o = JSONObject(text)
        FeedpulseConfig(parseForms(o), o.getLong("fetchedAt"), isStale = true)
    } catch (_: Exception) {
        null
    }
}

internal fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
