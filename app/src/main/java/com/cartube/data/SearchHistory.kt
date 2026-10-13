package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object SearchHistory {
    private const val MAX = 30
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<String> {
        val array = JSONArray(prefs.getString("items", "[]"))
        return (0 until array.length()).map { array.getString(it) }
    }

    @Synchronized
    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val items = (listOf(q) + all().filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs.edit().putString("items", JSONArray(items).toString()).apply()
    }

    @Synchronized
    fun remove(query: String) {
        prefs.edit().putString("items", JSONArray(all() - query).toString()).apply()
    }

    @Synchronized
    fun clear() = prefs.edit().putString("items", "[]").apply()
}
