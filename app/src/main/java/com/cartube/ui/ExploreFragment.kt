package com.cartube.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.databinding.FragmentExploreBinding
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Trending videos in Sweden or the USA, by category, plus the way into Shorts. */
class ExploreFragment : Fragment(R.layout.fragment_explore) {

    private var binding: FragmentExploreBinding? = null
    private var loadJob: Job? = null
    private var country = "SE"
    private var kiosk = KIOSKS.first().first
    private val cache = HashMap<String, List<VideoItem>>()

    private val adapter = VideoAdapter(
        onChannelClick = { requireContext().openChannelOf(it) },
        onClick = { requireContext().openVideo(it) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentExploreBinding.bind(view)
        binding = b
        b.list.adapter = adapter
        b.list.useGrid()
        b.shortsCard.setOnClickListener { startActivity(Intent(requireContext(), ShortsActivity::class.java)) }
        b.countrySweden.isChecked = true
        b.countries.setOnCheckedStateChangeListener { _, ids ->
            country = if (ids.firstOrNull() == R.id.countryUsa) "US" else "SE"
            load()
        }
        for ((id, label) in KIOSKS) {
            val chip = com.google.android.material.chip.Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                text = getString(label)
                isCheckable = true
                isChecked = id == kiosk
                setOnClickListener {
                    kiosk = id
                    load()
                }
            }
            b.categories.addView(chip)
        }
        b.refresh.setOnRefreshListener { cache.clear(); load() }
        load()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun load() {
        val b = binding ?: return
        val key = "$country/$kiosk"
        cache[key]?.let {
            show(it)
            return
        }
        loadJob?.cancel()
        b.refresh.isRefreshing = true
        b.error.isVisible = false
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { YouTube.trending(kiosk, country) }
            val current = binding ?: return@launch
            current.refresh.isRefreshing = false
            result.onSuccess {
                cache[key] = it
                show(it)
            }.onFailure {
                adapter.submitList(emptyList())
                current.error.isVisible = true
                current.error.text = getString(R.string.trending_failed, requireContext().describeError(it))
            }
        }
    }

    private fun show(videos: List<VideoItem>) {
        val b = binding ?: return
        adapter.submitList(videos)
        b.list.scrollToPosition(0)
        b.error.isVisible = videos.isEmpty()
        if (videos.isEmpty()) b.error.setText(R.string.trending_empty)
    }

    private companion object {
        val KIOSKS = listOf(
            "Trending" to R.string.trending_all,
            "trending_music" to R.string.trending_music,
            "trending_gaming" to R.string.trending_gaming,
            "trending_movies_and_shows" to R.string.trending_movies,
            "trending_podcasts_episodes" to R.string.trending_podcasts,
            "live" to R.string.trending_live,
        )
    }
}
