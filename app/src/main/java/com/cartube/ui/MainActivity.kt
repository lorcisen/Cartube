package com.cartube.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Library
import com.cartube.data.Settings
import com.cartube.data.Sync
import com.cartube.data.Updates
import android.content.ComponentName
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import com.cartube.player.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.cartube.databinding.ActivityMainBinding
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Bottom bar on phones, navigation rail on tablets (same id in both layouts). */
    private val nav: NavigationBarView get() = findViewById(R.id.bottomNav)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickTakeoutFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSubscriptions(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        createdWithAmoled = Settings.amoled
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        // The root already keeps clear of the system bars and keyboard; without this the
        // navigation bar adds the same space again and grows to the keyboard's height.
        ViewCompat.setOnApplyWindowInsetsListener(nav) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
        // While typing, give the screen to the search box and its suggestions.
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { _, insets ->
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!isTablet) nav.isVisible = !typing
            miniPlayerHiddenForKeyboard = typing
            updateMiniPlayer()
            insets
        }

        binding.toolbar.inflateMenu(R.menu.main)
        runCatching {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.toolbar.menu, R.id.cast)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }
        setUpMiniPlayer()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.content, ForYouFragment(), TAG_FOR_YOU)
                .add(R.id.content, HomeFragment(), TAG_HOME)
                .add(R.id.content, SearchFragment(), TAG_SEARCH)
                .add(R.id.content, LibraryFragment(), TAG_LIBRARY)
                .commitNow()
        }
        nav.setOnItemSelectedListener { item ->
            show(tagFor(item.itemId))
            true
        }
        show(tagFor(nav.selectedItemId))

        askForNotificationPermission()
        if (savedInstanceState == null) handleShare(intent)
        lifecycleScope.launch { Updates.check(this@MainActivity)?.let { offerUpdate(it) } }
    }

    override fun onStart() {
        super.onStart()
        Sync.syncInBackground()
        connectMiniPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (createdWithAmoled != Settings.amoled) recreate()
    }

    override fun onStop() {
        disconnectMiniPlayer()
        super.onStop()
    }

    // region Mini player

    private var createdWithAmoled = false
    private var miniPlayerHiddenForKeyboard = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val progressTick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            val duration = c.duration
            binding.miniProgress.progress =
                if (duration > 0) (c.currentPosition * 1000 / duration).toInt() else 0
            binding.miniProgress.postDelayed(this, 1000)
        }
    }

    private val miniListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateMiniPlayer()
    }

    private fun setUpMiniPlayer() {
        binding.miniProgress.max = 1000
        binding.miniPlayer.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        binding.miniPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.miniClose.setOnClickListener {
            controller?.run {
                stop()
                clearMediaItems()
            }
        }
    }

    private fun connectMiniPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = ready
            ready.addListener(miniListener)
            updateMiniPlayer()
            binding.miniProgress.post(progressTick)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disconnectMiniPlayer() {
        binding.miniProgress.removeCallbacks(progressTick)
        controller?.removeListener(miniListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private fun updateMiniPlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.playbackState == Player.STATE_IDLE || miniPlayerHiddenForKeyboard) {
            binding.miniPlayer.isVisible = false
            return
        }
        binding.miniPlayer.isVisible = true
        val video = VideoItem.fromMediaItem(item)
        binding.miniTitle.text = video.title
        binding.miniUploader.text = video.uploader
        binding.miniThumbnail.load(video.thumbnail)
        binding.miniPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // endregion

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun tagFor(itemId: Int) = when (itemId) {
        R.id.nav_home -> TAG_HOME
        R.id.nav_search -> TAG_SEARCH
        R.id.nav_library -> TAG_LIBRARY
        else -> TAG_FOR_YOU
    }

    private fun show(tag: String) {
        val transaction = supportFragmentManager.beginTransaction()
        for (tagged in listOf(TAG_FOR_YOU, TAG_HOME, TAG_SEARCH, TAG_LIBRARY)) {
            val fragment: Fragment = supportFragmentManager.findFragmentByTag(tagged) ?: continue
            if (tagged == tag) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNow()
    }

    fun showSearch() {
        nav.selectedItemId = R.id.nav_search
    }

    fun showChannelSearch() {
        nav.selectedItemId = R.id.nav_search
        (supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment)?.focusChannelSearch()
    }

    /** Explains how to export subscriptions from YouTube, then lets the user pick the file. */
    fun startImport() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_title)
            .setMessage(R.string.import_instructions)
            .setNegativeButton(R.string.import_open_takeout) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TAKEOUT_URL)))
            }
            .setPositiveButton(R.string.import_pick_file) { _, _ ->
                pickTakeoutFile.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun importSubscriptions(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                val type = fileType(uri)
                contentResolver.openInputStream(uri)!!.use { YouTube.importSubscriptions(it, type) }
            }
            result.onSuccess { channels ->
                val added = Library.addSubscriptions(channels)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_done, added, channels.size),
                    Toast.LENGTH_LONG,
                ).show()
                nav.selectedItemId = R.id.nav_home
            }.onFailure {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.import_failed)
                    .setMessage(it.message ?: it.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** "zip", "csv" or "json", from the file name or, failing that, its MIME type. */
    private fun fileType(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            .orEmpty()
            .lowercase()
        return when {
            name.endsWith(".zip") -> "zip"
            name.endsWith(".csv") -> "csv"
            name.endsWith(".json") -> "json"
            else -> when (contentResolver.getType(uri)) {
                "application/zip", "application/x-zip-compressed" -> "zip"
                "application/json" -> "json"
                else -> "csv"
            }
        }
    }

    fun offerUpdate(update: Updates.Update) {
        val message = buildString {
            append(getString(R.string.update_message, update.versionName))
            if (update.notes.isNotBlank()) append("\n\n").append(update.notes)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_install) { _, _ ->
                Updates.downloadAndInstall(this, update)
                Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun handleShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        YouTube.findLink(text)?.let { openVideo(VideoItem.fromLink(it)) }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val TAG_FOR_YOU = "for_you"
        const val TAG_HOME = "home"
        const val TAG_SEARCH = "search"
        const val TAG_LIBRARY = "library"
        const val TAKEOUT_URL = "https://takeout.google.com/takeout/custom/youtube"
    }
}
