package com.cartube.tvlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.cartube.data.Settings
import com.cartube.youtube.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The phone side of "picture on the TV, sound in the phone". The phone keeps playing as
 * usual (sound only, while linked) and tells the TV twice a second what it plays and
 * where, in the TV's clock, so the TV can show the picture in step.
 */
object TvLinkClient {

    class Tv(val name: String, val host: InetAddress, val port: Int)

    /** Name of the TV the picture is shown on, or null. */
    private val _connectedTo = MutableStateFlow<String?>(null)
    val connectedTo: StateFlow<String?> = _connectedTo

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writes = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var loop: Job? = null
    private var player: Player? = null

    /** TV clock minus phone clock, from the quickest recent ping (least network delay). */
    private var offsetMs = 0L
    private val samples = ArrayDeque<Pair<Long, Long>>() // round trip, offset

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                )
            ) {
                sendState()
            }
        }
    }

    private var appContext: Context? = null

    /** The adjustment for the current headphones or speaker, see [Settings.tvLinkDelay]. */
    val delayMs: Int get() = appContext?.let { Settings.tvLinkDelay(output(it)) } ?: 0

    fun setDelay(context: Context, ms: Int) {
        Settings.setTvLinkDelay(output(context), ms)
        sendState()
    }

    /** Name of where the sound goes now: the Bluetooth or wired headphones, or the speaker. */
    fun output(context: Context): String {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return "speaker"
        val preferred = listOf(
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        val devices = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (type in preferred) {
            val device = devices.firstOrNull { it.type == type } ?: continue
            return device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "type$type"
        }
        return "speaker"
    }

    /** Called by the playback service with the player whose sound the TV follows. */
    fun attach(player: Player) {
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
    }

    fun detach(player: Player) {
        if (this.player !== player) return
        player.removeListener(listener)
        this.player = null
    }

    fun connect(context: Context, tv: Tv, onFailed: () -> Unit) {
        disconnect()
        appContext = context.applicationContext
        scope.launch {
            val connected = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().apply {
                        wifi(context)?.let { runCatching { it.bindSocket(this) } }
                        connect(InetSocketAddress(tv.host, tv.port), 5000)
                        tcpNoDelay = true
                    }
                }.getOrNull()
            }
            if (connected == null) return@launch onFailed()
            socket = connected
            writer = connected.getOutputStream().bufferedWriter()
            samples.clear()
            offsetMs = 0
            val name = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
            send(JSONObject().put("t", "hello").put("name", name))
            _connectedTo.value = tv.name
            launch(Dispatchers.IO) { read(connected) }
            loop = launch {
                // A few quick pings first so the clocks line up before the first state.
                repeat(5) {
                    ping()
                    delay(60)
                }
                var ticks = 0
                while (isActive) {
                    sendState()
                    if (ticks++ % 6 == 0) ping()
                    delay(500)
                }
            }
        }
    }

    fun disconnect() {
        loop?.cancel()
        loop = null
        val old = socket ?: return
        send(JSONObject().put("t", "bye"))
        writes.execute { runCatching { old.close() } }
        socket = null
        writer = null
        _connectedTo.value = null
    }

    /** Finds CarTube TVs on the local network until the returned function is called. */
    fun discover(context: Context, onFound: (Tv) -> Unit): () -> Unit {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return {}
        val resolving = ArrayDeque<NsdServiceInfo>()
        var busy = false

        fun resolveNext() {
            if (busy) return
            val next = resolving.removeFirstOrNull() ?: return
            busy = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        busy = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        busy = false
                        @Suppress("DEPRECATION")
                        val host = info.host
                        if (host != null) onFound(Tv(info.serviceName, host, info.port))
                        resolveNext()
                    }
                }
            })
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    resolving.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Could not search for TVs: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(TvLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { Log.w(TAG, "Could not search for TVs", it) }
        // Many routers drop the multicast that service discovery relies on, so also ask
        // every address on the local network directly.
        val scan = scope.launch {
            while (isActive) {
                scanSubnet(context, onFound)
                delay(3000)
            }
        }
        return {
            scan.cancel()
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private suspend fun scanSubnet(context: Context, onFound: (Tv) -> Unit) = coroutineScope {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@coroutineScope
        val network = wifi(context) ?: return@coroutineScope
        val own = connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
            ?: return@coroutineScope
        val bytes = own.address
        val self = bytes[3].toInt() and 0xff
        val limit = Semaphore(48)
        for (last in 1..254) {
            if (last == self) continue
            launch(Dispatchers.IO) {
                val address = InetAddress.getByAddress(byteArrayOf(bytes[0], bytes[1], bytes[2], last.toByte()))
                val tv = limit.withPermit { probe(network, address) } ?: return@launch
                withContext(Dispatchers.Main) { onFound(tv) }
            }
        }
    }

    /**
     * The Wi-Fi network, even when a VPN is the default route: the TV is on the Wi-Fi, and
     * talking to it through a VPN tunnel usually doesn't work.
     */
    @Suppress("DEPRECATION")
    private fun wifi(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivity.allNetworks.firstOrNull {
            val caps = connectivity.getNetworkCapabilities(it)
            caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** The first three parts of the phone's Wi-Fi address, e.g. "192.168.1", or null without Wi-Fi. */
    fun subnet(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = wifi(context) ?: return null
        val own = connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull() ?: return null
        return own.hostAddress?.substringBeforeLast('.')
    }

    /** Checks a typed-in address ("192.168.1.23" or "192.168.1.23:47811") and connects to it. */
    fun connectTo(context: Context, typed: String, onFailed: () -> Unit) {
        val host = typed.trim().substringBefore(':')
        val port = typed.substringAfter(':', "").trim().toIntOrNull() ?: TvLink.PORT
        scope.launch {
            val tv = withContext(Dispatchers.IO) {
                runCatching {
                    val address = InetAddress.getByName(host)
                    val network = wifi(context)
                    if (network != null) probe(network, address, port) else null
                }.getOrNull()
            }
            if (tv == null) onFailed() else connect(context, tv, onFailed)
        }
    }

    /** Asks one address whether it is a CarTube TV. */
    private fun probe(network: Network, address: InetAddress, port: Int = TvLink.PORT): Tv? = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), if (port == TvLink.PORT) 400 else 3000)
            socket.soTimeout = 1500
            val out = socket.getOutputStream().bufferedWriter()
            out.write(JSONObject().put("t", "who").toString())
            out.write("\n")
            out.flush()
            val reply = JSONObject(socket.getInputStream().bufferedReader().readLine() ?: return@use null)
            if (reply.optString("t") != "tv") return@use null
            Tv(reply.optString("name").ifBlank { address.hostAddress ?: "TV" }, address, port)
        }
    }.getOrNull()

    private fun read(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                main.post { handle(message) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "TV disconnected", e)
        }
        main.post { if (this.socket === socket) disconnect() }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("t")) {
            "pong" -> {
                val now = SystemClock.elapsedRealtime()
                val sent = message.optLong("a")
                val roundTrip = now - sent
                val offset = message.optLong("b") - (sent + roundTrip / 2)
                samples.addLast(roundTrip to offset)
                while (samples.size > 8) samples.removeFirst()
                offsetMs = samples.minBy { it.first }.second
            }
            "cmd" -> {
                val p = player ?: return
                when (message.optString("c")) {
                    "toggle" -> if (p.isPlaying) p.pause() else p.play()
                    "seek" -> p.seekTo((p.currentPosition + message.optLong("by")).coerceAtLeast(0))
                }
            }
            "closed" -> disconnect()
        }
    }

    private fun ping() = send(JSONObject().put("t", "ping").put("a", SystemClock.elapsedRealtime()))

    private fun sendState() {
        // Without a clock reading from the TV, positions would point anywhere.
        if (socket == null || samples.isEmpty()) return
        val p = player
        val item = p?.currentMediaItem
        val message = JSONObject().put("t", "state")
        if (p != null && item != null) {
            message
                .put("video", VideoItem.fromMediaItem(item).toJson())
                .put("pos", p.currentPosition - delayMs)
                .put("at", SystemClock.elapsedRealtime() + offsetMs)
                .put("speed", p.playbackParameters.speed.toDouble())
                .put("playing", p.isPlaying)
        }
        send(message)
    }

    private fun send(message: JSONObject) {
        val out = writer ?: return
        writes.execute {
            try {
                out.write(message.toString())
                out.write("\n")
                out.flush()
            } catch (e: IOException) {
                Log.i(TAG, "Could not reach the TV", e)
            }
        }
    }

    private const val TAG = "TvLinkClient"
}
