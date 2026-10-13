package com.cartube.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.SearchHistory
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search with the on-screen keyboard or the remote's microphone. */
class TvSearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.tvContent, TvSearchFragment()).commit()
        }
    }

    class TvSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
        private val results = ArrayObjectAdapter(ListRowPresenter())
        private var job: Job? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setSearchResultProvider(this)
            setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
                (item as? VideoItem)?.let { TvActivity.play(requireContext(), it) }
            })
        }

        override fun getResultsAdapter(): ObjectAdapter = results

        override fun onQueryTextChange(newQuery: String): Boolean {
            search(newQuery, delayMs = 600)
            return true
        }

        override fun onQueryTextSubmit(query: String): Boolean {
            SearchHistory.add(query)
            search(query, delayMs = 0)
            return true
        }

        private fun search(query: String, delayMs: Long) {
            job?.cancel()
            if (query.isBlank()) {
                results.clear()
                return
            }
            job = lifecycleScope.launch {
                delay(delayMs)
                val videos = runCatching { YouTube.search(query) }.getOrDefault(emptyList())
                results.clear()
                if (videos.isNotEmpty()) {
                    val row = ArrayObjectAdapter(VideoCardPresenter()).apply { addAll(0, videos) }
                    results.add(ListRow(HeaderItem(getString(R.string.tv_results, query)), row))
                }
            }
        }
    }
}
