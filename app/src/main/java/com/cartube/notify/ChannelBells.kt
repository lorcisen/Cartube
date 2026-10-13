package com.cartube.notify

import android.content.Context
import android.content.SharedPreferences

/** Per-channel notification bell. All subscribed channels notify unless muted here. */
object ChannelBells {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("channel_bells", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun muted(): Set<String> = prefs.getStringSet("muted", emptySet())!!.toSet()

    fun isOn(url: String) = url !in muted()

    /** Returns true if the bell is on after the call. */
    @Synchronized
    fun toggle(url: String): Boolean {
        val muted = muted().toMutableSet()
        val on = if (url in muted) { muted -= url; true } else { muted += url; false }
        prefs.edit().putStringSet("muted", muted).apply()
        return on
    }
}
