package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate

/** Time spent watching, per day and per channel (last 60 days). */
object WatchStats {
    private const val KEEP_DAYS = 60L
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("watch_stats", Context.MODE_PRIVATE)
    }

    private fun today() = LocalDate.now().toString()

    @Synchronized
    fun add(ms: Long, channel: String?) {
        if (ms <= 0) return
        val days = JSONObject(prefs.getString("days", "{}")!!)
        val key = today()
        days.put(key, days.optLong(key) + ms)
        // Drop old days.
        val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }

        val channels = JSONObject(prefs.getString("channels", "{}")!!)
        if (!channel.isNullOrBlank()) channels.put(channel, channels.optLong(channel) + ms)
        prefs.edit().putString("days", days.toString()).putString("channels", channels.toString()).apply()
    }

    @Synchronized
    fun day(date: LocalDate): Long = JSONObject(prefs.getString("days", "{}")!!).optLong(date.toString())

    fun todayMs(): Long = day(LocalDate.now())

    /** The last [count] days, oldest first. */
    fun lastDays(count: Int): List<Pair<LocalDate, Long>> =
        (count - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }.map { it to day(it) }

    @Synchronized
    fun topChannels(count: Int): List<Pair<String, Long>> {
        val json = JSONObject(prefs.getString("channels", "{}")!!)
        return json.keys().asSequence().map { it to json.optLong(it) }.sortedByDescending { it.second }.take(count).toList()
    }

    /** True once per day, the first time today's total passes [limitMinutes]. */
    @Synchronized
    fun shouldRemind(limitMinutes: Int): Boolean {
        if (limitMinutes <= 0 || todayMs() < limitMinutes * 60_000L) return false
        if (prefs.getString("reminded", null) == today()) return false
        prefs.edit().putString("reminded", today()).apply()
        return true
    }
}
