package com.cartube.tvlink

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.cartube.youtube.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The TV side of "picture on the TV, sound in the phone". While CarTube is open on the TV,
 * it is announced on the local network; a phone connects, sends what it is playing and
 * where it is, and [TvLinkActivity] shows that video in step with the phone, without sound.
 *
 * Messages are one JSON object per line. Phone → TV: hello, ping, state, bye.
 * TV → phone: pong, cmd (play/pause or seek from the TV remote), closed.
 */
object TvLinkServer {

    /** What the phone plays: [positionMs] at [atTvMs] on this TV's elapsedRealtime clock. */
    class State(
        val video: VideoItem?,
        val positionMs: Long,
        val atTvMs: Long,
        val speed: Float,
        val playing: Boolean,
    ) {
        fun positionAt(nowMs: Long): Long =
            if (playing) positionMs + ((nowMs - atTvMs) * speed).toLong() else positionMs
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    /** Name of the connected phone, or null. */
    private val _phone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = _phone

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Executors.newSingleThreadExecutor()
    private var started = false
    @Volatile private var current: Socket? = null
    @Volatile private var out: BufferedWriter? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val server = try {
                ServerSocket(TvLink.PORT)
            } catch (e: IOException) {
                ServerSocket(0)
            }
            register(app, server.localPort)
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    continue
                }
                // One phone at a time; the newest one takes over.
                runCatching { current?.close() }
                current = socket
                launch { serve(app, socket) }
            }
        }
    }

    /** Sends a command to the phone, e.g. play/pause from the TV remote. */
    fun send(message: JSONObject) {
        val writer = out ?: return
        writes.execute {
            runCatching {
                writer.write(message.toString())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    /** The TV player was closed: tell the phone so it shows the picture itself again. */
    fun close() {
        send(JSONObject().put("t", "closed"))
        _state.value = null
    }

    private fun serve(app: Context, socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val reader = socket.getInputStream().bufferedReader()
            out = socket.getOutputStream().bufferedWriter()
            while (true) {
                val line = reader.readLine() ?: break
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (message.optString("t")) {
                    "hello" -> _phone.value = message.optString("name").ifBlank { "Mobil" }
                    "ping" -> send(
                        JSONObject().put("t", "pong")
                            .put("a", message.optLong("a"))
                            .put("b", SystemClock.elapsedRealtime()),
                    )
                    "state" -> {
                        val state = State(
                            video = message.optJSONObject("video")?.let { VideoItem.fromJson(it) },
                            positionMs = message.optLong("pos"),
                            atTvMs = message.optLong("at"),
                            speed = message.optDouble("speed", 1.0).toFloat(),
                            playing = message.optBoolean("playing"),
                        )
                        _state.value = state
                        if (state.video != null && !TvLinkActivity.visible) show(app)
                    }
                    "bye" -> break
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Phone disconnected", e)
        } finally {
            runCatching { socket.close() }
            if (current === socket) {
                current = null
                out = null
                _phone.value = null
                _state.value = null
            }
        }
    }

    private fun show(app: Context) {
        // Works while CarTube is in front on the TV; Android doesn't let apps in the
        // background open screens.
        runCatching {
            app.startActivity(Intent(app, TvLinkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun register(app: Context, port: Int) {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return
        val name = android.provider.Settings.Global.getString(app.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = TvLink.SERVICE_TYPE
            setPort(port)
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Could not announce the TV: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            })
        }.onFailure { Log.w(TAG, "Could not announce the TV", it) }
    }

    private const val TAG = "TvLinkServer"
}

object TvLink {
    const val SERVICE_TYPE = "_cartube._tcp"
    const val PORT = 47811
}
