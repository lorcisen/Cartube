package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/** User preferences, edited on the settings screen (res/xml/preferences.xml). */
object Settings {
    const val KEY_QUALITY_WIFI = "quality_wifi"
    const val KEY_QUALITY_MOBILE = "quality_mobile"
    const val KEY_SPEED = "default_speed"
    const val KEY_AUTOPLAY = "autoplay"
    const val KEY_SPONSORBLOCK = "sponsorblock"
    const val KEY_SPONSORBLOCK_CATEGORIES = "sponsorblock_categories"
    const val KEY_DISLIKES = "show_dislikes"
    const val KEY_GESTURES = "gestures"
    const val KEY_THEME = "theme"
    const val KEY_AMOLED = "amoled"
    const val KEY_DYNAMIC_COLOR = "dynamic_color"

    val DEFAULT_SPONSOR_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    /** Quality picked for one video in the player; wins over the defaults for that video. */
    @Volatile private var override: Pair<String, Int>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        applyTheme()
    }

    /** Highest video height to stream, depending on Wi-Fi or mobile data. */
    fun maxHeightFor(url: String): Int {
        override?.let { (overrideUrl, height) -> if (overrideUrl == url) return height }
        val metered = appContext.getSystemService(ConnectivityManager::class.java)
            ?.isActiveNetworkMetered ?: false
        val key = if (metered) KEY_QUALITY_MOBILE else KEY_QUALITY_WIFI
        return prefs.getString(key, if (metered) "480" else "1080")?.toIntOrNull() ?: 1080
    }

    fun overrideQuality(url: String, height: Int) {
        override = url to height
    }

    val defaultSpeed: Float get() = prefs.getString(KEY_SPEED, "1.0")?.toFloatOrNull() ?: 1f
    val autoplay: Boolean get() = prefs.getBoolean(KEY_AUTOPLAY, true)
    val sponsorBlock: Boolean get() = prefs.getBoolean(KEY_SPONSORBLOCK, true)
    val sponsorCategories: Set<String>
        get() = prefs.getStringSet(KEY_SPONSORBLOCK_CATEGORIES, DEFAULT_SPONSOR_CATEGORIES)
            ?: DEFAULT_SPONSOR_CATEGORIES
    val showDislikes: Boolean get() = prefs.getBoolean(KEY_DISLIKES, true)
    val gestures: Boolean get() = prefs.getBoolean(KEY_GESTURES, true)
    val amoled: Boolean get() = prefs.getBoolean(KEY_AMOLED, false)
    val dynamicColor: Boolean get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString(KEY_THEME, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
