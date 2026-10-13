package com.cartube.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Recommendations
import com.cartube.databinding.FragmentForYouBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Recommended videos with category chips, like the YouTube home page. */
class ForYouFragment : Fragment(R.layout.fragment_for_you) {

    private var binding: FragmentForYouBinding? = null
    private var loadJob: Job? = null
    private var selectedCategory: String? = null
    private val adapter = VideoAdapter(
        onChannelClick = { requireContext().openChannelOf(it) },
        onClick = { requireContext().openVideo(it) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentForYouBinding.bind(view)
        binding = b
        b.list.adapter = adapter
        b.refresh.setOnRefreshListener { load(force = true) }
        b.searchButton.setOnClickListener { (activity as? MainActivity)?.showSearch() }
        Recommendations.current?.let { show(it) }
        load(force = false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Picks up newly watched videos when coming back to the tab.
        if (!hidden) load(force = false)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun load(force: Boolean) {
        val b = binding ?: return
        if (loadJob?.isActive == true) return
        b.refresh.isRefreshing = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { Recommendations.load(force) }
            val current = binding ?: return@launch
            current.refresh.isRefreshing = false
            result.onSuccess { show(it) }
                .onFailure {
                    current.empty.isVisible = adapter.itemCount == 0
                    current.emptyText.text = getString(R.string.recommendations_failed, it.message ?: it.javaClass.simpleName)
                }
            // Categories of new videos are looked up afterwards, so the list shows at once.
            if (result.getOrNull()?.categoriesPending == true) {
                runCatching { Recommendations.refine() }.onSuccess { show(it) }
            }
        }
    }

    private fun show(result: Recommendations.Result) {
        val b = binding ?: return
        b.chips.removeAllViews()
        val keys = listOf<String?>(null) + result.categories.map { it.key }
        if (selectedCategory !in keys) selectedCategory = null
        for (key in keys) {
            val chip = Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                text = key?.let { k -> result.categories.first { it.key == k }.title } ?: getString(R.string.all)
                isCheckable = true
                isChecked = key == selectedCategory
                setOnClickListener {
                    selectedCategory = key
                    show(result)
                    b.list.scrollToPosition(0)
                }
            }
            b.chips.addView(chip)
        }
        b.chipScroll.isVisible = result.categories.isNotEmpty()

        val videos = selectedCategory?.let { k -> result.categories.first { it.key == k }.videos } ?: result.all
        adapter.submitList(videos)
        b.empty.isVisible = videos.isEmpty()
        b.emptyText.setText(R.string.recommendations_empty)
    }
}
