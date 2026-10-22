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
    const val KEY_SUBTITLES = "subtitles"
    const val SUBTITLES_SWEDISH = "sv"
    const val SUBTITLES_ORIGINAL = "original"
    const val SUBTITLES_OFF = "off"
    const val KEY_SUBTITLE_SIZE = "subtitle_size"
    const val KEY_AUDIO_ONLY = "audio_only"
    const val KEY_NORMALIZE = "normalize_volume"
    const val KEY_CLEAR_SPEECH = "clear_speech"
    const val KEY_DOWNLOAD_QUALITY = "download_quality"
    const val KEY_NOTIFY_NEW = "notify_new_videos"
    const val KEY_DAILY_LIMIT = "daily_limit"
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
        // Videos now start without text; CC in the player turns it on. Applied once, so a
        // later choice in the settings sticks.
        if (!prefs.getBoolean("subtitles_off_by_default", false)) {
            prefs.edit().putString(KEY_SUBTITLES, SUBTITLES_OFF).putBoolean("subtitles_off_by_default", true).apply()
        }
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
    val subtitles: String get() = prefs.getString(KEY_SUBTITLES, SUBTITLES_OFF) ?: SUBTITLES_OFF
    /** Subtitle height as a fraction of the video height. */
    val subtitleSize: Float get() = prefs.getString(KEY_SUBTITLE_SIZE, "0.0533")?.toFloatOrNull() ?: 0.0533f
    /**
     * How much later (ms) the TV shows the picture than the phone plays the sound, for one
     * audio output (each pair of headphones has its own delay); negative = earlier.
     */
    fun tvLinkDelay(output: String): Int = prefs.getInt("tv_link_delay:$output", prefs.getInt("tv_link_delay", 0))

    fun setTvLinkDelay(output: String, ms: Int) = prefs.edit().putInt("tv_link_delay:$output", ms).apply()

    var audioOnly: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ONLY, value).apply()
    val normalizeVolume: Boolean get() = prefs.getBoolean(KEY_NORMALIZE, false)
    val clearSpeech: Boolean get() = prefs.getBoolean(KEY_CLEAR_SPEECH, false)

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    val notifyNewVideos: Boolean get() = prefs.getBoolean(KEY_NOTIFY_NEW, true)
    val dailyLimitMinutes: Int get() = prefs.getString(KEY_DAILY_LIMIT, "0")?.toIntOrNull() ?: 0

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
