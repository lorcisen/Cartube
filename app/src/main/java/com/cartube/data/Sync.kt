package com.cartube.data

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.cartube.net.Http
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Keeps favourites, history and subscriptions the same on every device signed in with the
 * same Google account. The data lives in the app's hidden folder on the user's own Google
 * Drive (the `drive.appdata` scope), so there is no server and nobody else can read it.
 */
object Sync {

    sealed interface State {
        data object SignedOut : State
        data object Working : State
        data class SignedIn(val email: String?, val lastSync: Long, val error: String? = null) : State
    }

    private const val FILE_NAME = "cartube-library.json"
    private const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val TAG = "Sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var pendingUpload: Job? = null

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state

    private val enabled get() = prefs.getBoolean("enabled", false)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        publishIdle()
        // Upload a few seconds after the last change, so a burst of changes is one upload.
        Library.addChangeListener {
            if (!enabled) return@addChangeListener
            pendingUpload?.cancel()
            pendingUpload = scope.launch {
                delay(5_000)
                syncNow()
            }
        }
    }

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA), Scope("email")))
        .build()

    /**
     * Starts sign-in. Returns a PendingIntent to show Google's consent screen when the
     * user has not granted access yet; pass its result to [finishSignIn].
     */
    suspend fun signIn(activity: Activity): PendingIntent? {
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) return result.pendingIntent
        onAuthorized(result)
        return null
    }

    fun finishSignIn(activity: Activity, data: Intent?) {
        try {
            val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            onAuthorized(result)
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed", e)
            _state.value = State.SignedOut
            throw e
        }
    }

    private fun onAuthorized(result: AuthorizationResult) {
        val email = result.toGoogleSignInAccount()?.email
        prefs.edit().putBoolean("enabled", true).putString("email", email).apply()
        scope.launch { syncNow() }
    }

    fun signOut() {
        pendingUpload?.cancel()
        prefs.edit().clear().apply()
        _state.value = State.SignedOut
    }

    /** Downloads the shared copy, merges it with this device and uploads the result. */
    suspend fun syncNow() {
        if (!enabled) return
        mutex.withLock {
            _state.value = State.Working
            val error = try {
                val token = token() ?: throw IOException("Logga in igen för att fortsätta synka")
                withContext(Dispatchers.IO) {
                    val drive = Drive(token)
                    val fileId = drive.findFile()
                    fileId?.let { drive.download(it) }?.let { Library.merge(JSONObject(it)) }
                    drive.upload(fileId, Library.export().toString())
                }
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                null
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                e.message ?: e.javaClass.simpleName
            }
            publishIdle(error)
        }
    }

    /** Syncs in the background, for example when the app is opened or closed. */
    fun syncInBackground() {
        if (enabled) scope.launch { syncNow() }
    }

    private fun publishIdle(error: String? = null) {
        _state.value = if (enabled) {
            State.SignedIn(prefs.getString("email", null), prefs.getLong("last_sync", 0), error)
        } else {
            State.SignedOut
        }
    }

    /** A fresh access token; null if Google needs the user to confirm again. */
    private suspend fun token(): String? {
        val result = Identity.getAuthorizationClient(appContext).authorize(request).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /** The few Drive v3 REST calls we need. */
    private class Drive(private val token: String) {
        private val api = "https://www.googleapis.com/drive/v3/files"
        private val upload = "https://www.googleapis.com/upload/drive/v3/files"
        private val json = "application/json; charset=utf-8".toMediaType()

        fun findFile(): String? {
            val url = "$api?spaces=appDataFolder&fields=files(id)&q=" +
                java.net.URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
            val body = call(Request.Builder().url(url).get())
            return JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }
        }

        fun download(id: String): String = call(Request.Builder().url("$api/$id?alt=media").get())

        fun upload(existingId: String?, content: String) {
            if (existingId != null) {
                call(
                    Request.Builder()
                        .url("$upload/$existingId?uploadType=media")
                        .patch(content.toRequestBody(json)),
                )
            } else {
                val metadata = JSONObject()
                    .put("name", FILE_NAME)
                    .put("parents", org.json.JSONArray().put("appDataFolder"))
                val multipart = MultipartBody.Builder()
                    .setType("multipart/related".toMediaType())
                    .addPart(metadata.toString().toRequestBody(json))
                    .addPart(content.toRequestBody(json))
                    .build()
                call(Request.Builder().url("$upload?uploadType=multipart").post(multipart))
            }
        }

        private fun call(builder: Request.Builder): String {
            val request = builder.header("Authorization", "Bearer $token").build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Google Drive svarade ${response.code}: ${body.take(200)}")
                return body
            }
        }
    }
}
