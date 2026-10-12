package com.cartube.youtube

import android.content.Intent
import org.json.JSONObject

data class Channel(
    val url: String,
    val name: String,
    val avatar: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("name", name)
        .put("avatar", avatar)

    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_JSON, toJson().toString())

    companion object {
        private const val EXTRA_JSON = "com.cartube.CHANNEL"

        fun fromJson(json: JSONObject) = Channel(
            url = json.getString("url"),
            name = json.optString("name"),
            avatar = if (json.isNull("avatar")) null else json.optString("avatar").takeIf { it.isNotBlank() },
        )

        fun fromIntent(intent: Intent): Channel? =
            intent.getStringExtra(EXTRA_JSON)?.let { fromJson(JSONObject(it)) }
    }
}
