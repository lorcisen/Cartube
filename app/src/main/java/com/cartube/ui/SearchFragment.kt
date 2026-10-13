package com.cartube.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.databinding.FragmentSearchBinding
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {

    private var binding: FragmentSearchBinding? = null
    private var searchJob: Job? = null
    private var videos: List<VideoItem> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var playlists: List<YouTube.PlaylistSummary> = emptyList()
    private var lastQuery = ""

    private val videoAdapter = VideoAdapter(
        onChannelClick = { requireContext().openChannelOf(it) },
        onClick = { requireContext().openVideo(it) },
    )
    private val channelAdapter = ChannelAdapter { requireContext().openChannel(it) }
    private val playlistAdapter = PlaylistAdapter { PlaylistActivity.remote(requireContext(), it.key) }

    private val showingChannels get() = binding?.chipChannels?.isChecked == true
    private val showingPlaylists get() = binding?.chipPlaylists?.isChecked == true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSearchBinding.bind(view)
        binding = b
        b.searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search(b.searchInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        b.filter.setOnCheckedStateChangeListener { _, _ ->
            showResults()
            if (lastQuery.isNotBlank()) search(lastQuery)
        }
        showResults()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** Switches to channel search and focuses the search field. */
    fun focusChannelSearch() {
        val b = binding ?: return
        b.chipChannels.isChecked = true
        b.searchInput.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showResults() {
        val b = binding ?: return
        if (showingPlaylists) {
            b.list.adapter = playlistAdapter
            playlistAdapter.submitList(
                playlists.map {
                    PlaylistAdapter.Row(
                        key = it.url,
                        name = it.name,
                        meta = listOfNotNull(
                            it.uploader.takeIf { u -> u.isNotBlank() },
                            it.count.takeIf { c -> c >= 0 }?.let { c -> getString(R.string.videos_count, c.toInt()) },
                        ).joinToString(" · "),
                        thumbnail = it.thumbnail,
                    )
                },
            )
        } else if (showingChannels) {
            b.list.adapter = channelAdapter
            channelAdapter.submitList(channels)
        } else {
            b.list.adapter = videoAdapter
            videoAdapter.submitList(videos)
        }
        val empty = when {
            showingPlaylists -> playlists.isEmpty()
            showingChannels -> channels.isEmpty()
            else -> videos.isEmpty()
        }
        b.empty.isVisible = empty && searchJob?.isActive != true
        b.empty.setText(if (lastQuery.isBlank()) R.string.empty_search else R.string.no_results)
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val b = binding ?: return
        YouTube.findLink(query)?.let {
            requireContext().openVideo(VideoItem.fromLink(it))
            return
        }
        lastQuery = query
        hideKeyboard()
        searchJob?.cancel()
        b.progress.isVisible = true
        b.empty.isVisible = false
        val forChannels = showingChannels
        val forPlaylists = showingPlaylists
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                when {
                    forPlaylists -> playlists = YouTube.searchPlaylists(query)
                    forChannels -> channels = YouTube.searchChannels(query)
                    else -> videos = YouTube.search(query)
                }
            }
            val current = binding ?: return@launch
            current.progress.isVisible = false
            result.onSuccess {
                showResults()
                current.list.scrollToPosition(0)
            }.onFailure {
                current.empty.isVisible = true
                current.empty.text = getString(R.string.search_failed, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun hideKeyboard() {
        val b = binding ?: return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        b.searchInput.clearFocus()
    }
}
