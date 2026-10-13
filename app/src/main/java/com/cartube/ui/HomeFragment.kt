package com.cartube.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.databinding.FragmentHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Latest uploads from subscribed channels. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null
    private var loadJob: Job? = null
    private val adapter = VideoAdapter(
        onChannelClick = { requireContext().openChannelOf(it) },
        onClick = { requireContext().openVideo(it) },
    )
    private val subscriptionsChanged: () -> Unit = {
        Feed.invalidate()
        if (view != null) load(force = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        binding = b
        b.list.adapter = adapter
        b.list.useGrid()
        b.refresh.setOnRefreshListener { load(force = true) }
        b.importButton.setOnClickListener { (activity as? MainActivity)?.startImport() }
        b.findChannelsButton.setOnClickListener { (activity as? MainActivity)?.showChannelSearch() }
        Library.addSubscriptionListener(subscriptionsChanged)
        adapter.submitList(Feed.cached)
        load(force = false)
    }

    override fun onDestroyView() {
        Library.removeSubscriptionListener(subscriptionsChanged)
        binding = null
        super.onDestroyView()
    }

    private fun describe(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "?"

    private fun load(force: Boolean) {
        val b = binding ?: return
        val hasSubscriptions = Library.subscriptions().isNotEmpty()
        b.emptyState.isVisible = !hasSubscriptions
        b.refresh.isVisible = hasSubscriptions
        b.error.isVisible = false
        if (!hasSubscriptions) {
            adapter.submitList(emptyList())
            return
        }
        loadJob?.cancel()
        b.refresh.isRefreshing = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { Feed.load(force) }
            val current = binding ?: return@launch
            current.refresh.isRefreshing = false
            result.onSuccess { feed ->
                adapter.submitList(feed.videos)
                val message = when {
                    feed.videos.isEmpty() && feed.failed > 0 ->
                        getString(R.string.feed_failed, describe(feed.firstError))
                    feed.videos.isEmpty() -> getString(R.string.feed_empty)
                    feed.failed > 0 -> getString(R.string.feed_partly_failed, feed.failed)
                    else -> null
                }
                current.error.isVisible = message != null
                current.error.text = message
            }.onFailure {
                current.error.isVisible = true
                current.error.text = getString(R.string.feed_failed, describe(it))
            }
        }
    }
}
