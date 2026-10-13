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
import com.cartube.data.LengthFilter
import com.cartube.data.SearchHistory
import com.cartube.data.UploadFilter
import com.cartube.data.VideoSort
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {

    private var binding: FragmentSearchBinding? = null
    private var searchJob: Job? = null
    private var videos: List<VideoItem> = emptyList()
    private var videoPages = 0
    private var sort = VideoSort.ORIGINAL
    private var upload = UploadFilter.ANY
    private var length = LengthFilter.ANY

    /** Sorting or filtering works on a bigger pool than YouTube's first page. */
    private val wantsMore get() = sort != VideoSort.ORIGINAL || upload != UploadFilter.ANY || length != LengthFilter.ANY

    private fun shownVideos(): List<VideoItem> =
        sort.apply(videos.filter { upload.matches(it) && length.matches(it) })
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

    private var suggestJob: Job? = null
    private val suggestionAdapter = SuggestionAdapter(
        onPick = { query ->
            binding?.searchInput?.setText(query)
            search(query)
        },
        onFill = { query ->
            binding?.searchInput?.apply {
                setText("$query ")
                setSelection(text?.length ?: 0)
            }
        },
        onRemove = { query ->
            SearchHistory.remove(query)
            updateSuggestions(binding?.searchInput?.text?.toString().orEmpty())
        },
    )

    /** History when the box is empty, otherwise YouTube's suggestions (after a short pause). */
    private fun updateSuggestions(text: String) {
        val b = binding ?: return
        suggestJob?.cancel()
        val query = text.trim()
        if (query.isEmpty()) {
            suggestionAdapter.submit(SearchHistory.all().map { SuggestionAdapter.Row(it, true) })
            return
        }
        val fromHistory = SearchHistory.all().filter { it.contains(query, ignoreCase = true) }.take(3)
        suggestionAdapter.submit(fromHistory.map { SuggestionAdapter.Row(it, true) })
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(250)
            val live = YouTube.suggestions(query).filterNot { s -> fromHistory.any { it.equals(s, true) } }
            if (binding === b) {
                suggestionAdapter.submit(
                    fromHistory.map { SuggestionAdapter.Row(it, true) } + live.map { SuggestionAdapter.Row(it, false) },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSearchBinding.bind(view)
        binding = b
        b.suggestions.adapter = suggestionAdapter
        val sortLabel: (VideoSort) -> String = {
            getString(if (it == VideoSort.ORIGINAL) R.string.sort_relevance else it.label)
        }
        b.sortButton.bindChoice(
            listOf(VideoSort.ORIGINAL, VideoSort.NEWEST, VideoSort.MOST_VIEWED, VideoSort.LONGEST, VideoSort.SHORTEST),
            { sort }, sortLabel, getString(R.string.sort),
        ) { sort = it; filtersChanged() }
        b.uploadButton.bindChoice(UploadFilter.entries.toList(), { upload }, { getString(it.label) }, getString(R.string.uploaded)) {
            upload = it
            filtersChanged()
        }
        b.lengthButton.bindChoice(LengthFilter.entries.toList(), { length }, { getString(it.label) }, getString(R.string.length)) {
            length = it
            filtersChanged()
        }
        b.searchInput.setOnFocusChangeListener { _, focused ->
            b.suggestions.isVisible = focused
            if (focused) updateSuggestions(b.searchInput.text?.toString().orEmpty())
        }
        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (b.searchInput.hasFocus()) updateSuggestions(s?.toString().orEmpty())
            }
        })
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
            b.list.useGrid(minItemWidthDp = 380)
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
            b.list.useGrid(minItemWidthDp = 300)
            channelAdapter.submitList(channels)
        } else {
            b.list.adapter = videoAdapter
            b.list.useGrid()
            videoAdapter.submitList(shownVideos())
        }
        b.sortRow.isVisible = !showingPlaylists && !showingChannels
        val empty = when {
            showingPlaylists -> playlists.isEmpty()
            showingChannels -> channels.isEmpty()
            else -> shownVideos().isEmpty()
        }
        b.empty.isVisible = empty && searchJob?.isActive != true
        b.empty.setText(
            when {
                lastQuery.isBlank() -> R.string.empty_search
                !showingChannels && !showingPlaylists && videos.isNotEmpty() -> R.string.no_results_filter
                else -> R.string.no_results
            },
        )
    }

    private fun filtersChanged() {
        val b = binding ?: return
        // Fetch a bigger pool once, then sort and filter locally.
        if (wantsMore && videoPages < 3 && lastQuery.isNotBlank() && !showingChannels && !showingPlaylists) {
            search(lastQuery)
        } else {
            showResults()
            b.list.scrollToPosition(0)
        }
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val b = binding ?: return
        YouTube.findLink(query)?.let {
            requireContext().openVideo(VideoItem.fromLink(it))
            return
        }
        lastQuery = query
        SearchHistory.add(query)
        b.suggestions.isVisible = false
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
                    else -> {
                        val pages = if (wantsMore) 3 else 1
                        videos = YouTube.search(query, pages)
                        videoPages = pages
                    }
                }
            }
            val current = binding ?: return@launch
            current.progress.isVisible = false
            result.onSuccess {
                showResults()
                current.list.scrollToPosition(0)
            }.onFailure {
                current.empty.isVisible = true
                current.empty.text = getString(R.string.search_failed, requireContext().describeError(it))
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
