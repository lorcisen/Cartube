package com.cartube.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import com.cartube.BuildConfig
import com.cartube.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks the GitHub release the build workflow publishes ("latest") for a newer version,
 * and downloads and opens the APK so Android can install it over this one.
 */
object Updates {
    class Update(val versionName: String, val versionCode: Int, val apkUrl: String, val notes: String)

    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** A newer version, or null when up to date or the check failed. */
    suspend fun check(context: Context, force: Boolean = false): Update? {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return null
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val json = JSONObject(response.body?.string() ?: return@runCatching null)
                    val body = json.optString("body")
                    val code = Regex("""versionCode=(\d+)""").find(body)?.groupValues?.get(1)?.toInt()
                        ?: return@runCatching null
                    val name = Regex("""versionName=(\S+)""").find(body)?.groupValues?.get(1) ?: code.toString()
                    val assets = json.optJSONArray("assets") ?: return@runCatching null
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url")
                        ?: return@runCatching null
                    if (code <= BuildConfig.VERSION_CODE) return@runCatching null
                    val notes = body.lines().filterNot { it.startsWith("versionCode=") || it.startsWith("versionName=") }
                        .joinToString("\n").trim()
                    Update(name, code, apk, notes)
                }
            }.getOrNull()
        }
    }

    /** Downloads the APK with the system download manager, then opens the installer. */
    fun downloadAndInstall(context: Context, update: Update) {
        val app = context.applicationContext
        val manager = app.getSystemService(DownloadManager::class.java) ?: return
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("CarTube ${update.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "CarTube-${update.versionCode}.apk")
        val id = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = manager.getUriForDownloadedFile(id) ?: return
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(install)
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
