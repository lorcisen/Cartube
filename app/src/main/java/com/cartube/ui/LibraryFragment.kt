package com.cartube.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.FragmentLibraryBinding

/** Favourites, history and subscribed channels. */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var binding: FragmentLibraryBinding? = null
    private val videoAdapter = VideoAdapter(
        compact = true,
        onClick = { requireContext().openVideo(it) },
    )
    private val channelAdapter = ChannelAdapter { requireContext().openChannel(it) }
    private val subscriptionsChanged: () -> Unit = { refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentLibraryBinding.bind(view)
        binding = b
        b.filter.setOnCheckedStateChangeListener { _, _ -> refresh() }
        b.importButton.setOnClickListener { (activity as? MainActivity)?.startImport() }
        b.clearHistoryButton.setOnClickListener {
            Library.clearHistory()
            refresh()
        }
        Library.addSubscriptionListener(subscriptionsChanged)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        Library.removeSubscriptionListener(subscriptionsChanged)
        binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        val b = binding ?: return
        val channels = b.chipSubscriptions.isChecked
        val history = b.chipHistory.isChecked
        b.importButton.isVisible = channels
        b.clearHistoryButton.isVisible = history && Library.history().isNotEmpty()

        val empty = if (channels) {
            b.list.adapter = channelAdapter
            val items = Library.subscriptions()
            channelAdapter.submitList(items)
            items.isEmpty()
        } else {
            b.list.adapter = videoAdapter
            val items = if (history) Library.history() else Library.favorites()
            videoAdapter.submitList(items)
            items.isEmpty()
        }
        b.empty.isVisible = empty
        b.empty.setText(
            when {
                channels -> R.string.empty_subscriptions
                history -> R.string.empty_history
                else -> R.string.empty_favorites
            },
        )
    }
}
