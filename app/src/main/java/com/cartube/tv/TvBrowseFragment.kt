package com.cartube.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.data.Positions
import com.cartube.data.Recommendations
import com.cartube.data.Sync
import com.cartube.ui.SettingsActivity
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.launch

/**
 * Rows of videos to browse with the remote: continue watching, recommendations,
 * subscriptions, watch later, trending, favourites and history.
 */
class TvBrowseFragment : BrowseSupportFragment() {

    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private val rowIds = mutableListOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        badgeDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_logo)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand)
        adapter = rows

        setOnSearchClickedListener { startActivity(Intent(requireContext(), TvSearchActivity::class.java)) }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is VideoItem -> TvActivity.play(requireContext(), item)
                is ActionCardPresenter.Action -> onAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    override fun onResume() {
        super.onResume()
        // Progress and history change after watching; refresh the cheap local rows.
        setRow(ROW_CONTINUE, R.string.continue_watching, Positions.unfinished(Library.history()).take(20))
        setRow(ROW_WATCH_LATER, R.string.watch_later, Library.later(Library.WATCH_LATER))
        setRow(ROW_HISTORY, R.string.tab_history, Library.history().take(40))
        setActions()
    }

    private fun load() {
        setRow(ROW_FAVORITES, R.string.tab_favorites, Library.favorites())
        setActions()
        viewLifecycleOwner.lifecycleScope.launch { com.cartube.tvlink.TvLinkServer.address.collect { setActions() } }
        Sync.syncInBackground()
        viewLifecycleOwner.lifecycleScope.launch {
            setRow(ROW_FEED, R.string.nav_home, Feed.cached)
            runCatching { Feed.load() }.onSuccess { setRow(ROW_FEED, R.string.nav_home, it.videos) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { Recommendations.load() }.onSuccess { setRow(ROW_FOR_YOU, R.string.nav_for_you, it.all.take(40)) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { YouTube.trending("Trending", "SE") }
                .onSuccess { setRow(ROW_TRENDING, R.string.tv_trending_sweden, it) }
        }
    }

    /** Adds or replaces a row, keeping rows in a fixed order; empty rows are hidden. */
    private fun setRow(id: Long, title: Int, videos: List<VideoItem>) {
        val index = rowIds.indexOf(id)
        if (videos.isEmpty()) {
            if (index >= 0) {
                rows.removeItems(index, 1)
                rowIds.removeAt(index)
            }
            return
        }
        val items = ArrayObjectAdapter(VideoCardPresenter()).apply { addAll(0, videos.filterNot(Library::isHidden)) }
        val row = ListRow(HeaderItem(id, getString(title)), items)
        if (index >= 0) {
            rows.replace(index, row)
        } else {
            val position = rowIds.indexOfFirst { it > id }.let { if (it < 0) rowIds.size else it }
            rows.add(position, row)
            rowIds.add(position, id)
        }
    }

    private fun setActions() {
        val actions = ArrayObjectAdapter(ActionCardPresenter()).apply {
            add(ActionCardPresenter.Action("search", getString(R.string.nav_search), R.drawable.ic_search))
            add(ActionCardPresenter.Action("settings", getString(R.string.settings), R.drawable.ic_settings))
            val address = com.cartube.tvlink.TvLinkServer.address.value
            add(
                ActionCardPresenter.Action(
                    "link",
                    if (address != null) getString(R.string.tv_link_ready, address) else getString(R.string.tv_link_not_ready),
                    R.drawable.ic_tv,
                ),
            )
        }
        val row = ListRow(HeaderItem(ROW_ACTIONS, getString(R.string.tv_more)), actions)
        val index = rowIds.indexOf(ROW_ACTIONS)
        if (index >= 0) rows.replace(index, row) else {
            rows.add(row)
            rowIds.add(ROW_ACTIONS)
        }
    }

    private fun onAction(action: ActionCardPresenter.Action) {
        when (action.id) {
            "search" -> startActivity(Intent(requireContext(), TvSearchActivity::class.java))
            "settings" -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
            "link" -> android.widget.Toast.makeText(requireContext(), R.string.tv_link_help, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val ROW_CONTINUE = 1L
        const val ROW_FOR_YOU = 2L
        const val ROW_FEED = 3L
        const val ROW_WATCH_LATER = 4L
        const val ROW_TRENDING = 5L
        const val ROW_FAVORITES = 6L
        const val ROW_HISTORY = 7L
        const val ROW_ACTIONS = 99L
    }
}
