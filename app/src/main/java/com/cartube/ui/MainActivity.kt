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
import com.cartube.databinding.ActivityMainBinding
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickTakeoutFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSubscriptions(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.toolbar.inflateMenu(R.menu.main)
        runCatching {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.toolbar.menu, R.id.cast)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.content, HomeFragment(), TAG_HOME)
                .add(R.id.content, SearchFragment(), TAG_SEARCH)
                .add(R.id.content, LibraryFragment(), TAG_LIBRARY)
                .commitNow()
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.nav_search -> TAG_SEARCH
                    R.id.nav_library -> TAG_LIBRARY
                    else -> TAG_HOME
                },
            )
            true
        }
        show(
            when (binding.bottomNav.selectedItemId) {
                R.id.nav_search -> TAG_SEARCH
                R.id.nav_library -> TAG_LIBRARY
                else -> TAG_HOME
            },
        )

        askForNotificationPermission()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun show(tag: String) {
        val transaction = supportFragmentManager.beginTransaction()
        for (tagged in listOf(TAG_HOME, TAG_SEARCH, TAG_LIBRARY)) {
            val fragment: Fragment = supportFragmentManager.findFragmentByTag(tagged) ?: continue
            if (tagged == tag) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNow()
    }

    fun showChannelSearch() {
        binding.bottomNav.selectedItemId = R.id.nav_search
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
                binding.bottomNav.selectedItemId = R.id.nav_home
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
        const val TAG_HOME = "home"
        const val TAG_SEARCH = "search"
        const val TAG_LIBRARY = "library"
        const val TAKEOUT_URL = "https://takeout.google.com/takeout/custom/youtube"
    }
}
