package com.cartube.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.ActivityMainBinding
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = VideoAdapter { openPlayer(it) }
    private var searchResults: List<VideoItem> = emptyList()
    private var searchJob: Job? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.list.adapter = adapter
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search(binding.searchInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = showTab()
        })

        askForNotificationPermission()
        if (savedInstanceState == null) handleShare(intent)
        showTab()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        showTab()
    }

    private fun showTab() {
        val tab = binding.tabs.selectedTabPosition
        binding.searchBox.isVisible = tab == TAB_SEARCH
        val items = when (tab) {
            TAB_FAVORITES -> Library.favorites()
            TAB_HISTORY -> Library.history()
            else -> searchResults
        }
        adapter.submitList(items)
        if (tab != TAB_SEARCH || searchJob?.isActive != true) {
            binding.progress.isVisible = false
            binding.empty.isVisible = items.isEmpty()
            binding.empty.setText(
                when (tab) {
                    TAB_FAVORITES -> R.string.empty_favorites
                    TAB_HISTORY -> R.string.empty_history
                    else -> R.string.empty_search
                },
            )
        }
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val link = YouTube.findLink(query)
        if (link != null) {
            openPlayer(VideoItem(link, link, "", null, 0))
            return
        }
        hideKeyboard()
        searchJob?.cancel()
        binding.progress.isVisible = true
        binding.empty.isVisible = false
        adapter.submitList(emptyList())
        searchJob = lifecycleScope.launch {
            val result = runCatching { YouTube.search(query) }
            binding.progress.isVisible = false
            result.onSuccess {
                searchResults = it
                showTab()
                binding.list.scrollToPosition(0)
            }.onFailure {
                binding.empty.isVisible = true
                binding.empty.text = getString(R.string.search_failed, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        YouTube.findLink(text)?.let { openPlayer(VideoItem(it, it, "", null, 0)) }
    }

    private fun openPlayer(item: VideoItem) {
        startActivity(item.putInto(Intent(this, PlayerActivity::class.java)))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
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
        const val TAB_SEARCH = 0
        const val TAB_FAVORITES = 1
        const val TAB_HISTORY = 2
    }
}
