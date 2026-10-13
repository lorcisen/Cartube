package com.cartube.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cartube.data.Sync
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
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

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Sync.finishSignIn(requireActivity(), result.data) }
            .onFailure { signInFailed(it) }
    }

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Sync.state.collect { showSync(it) }
            }
        }
    }

    private fun showSync(state: Sync.State) {
        val b = binding ?: return
        when (state) {
            Sync.State.SignedOut -> {
                b.syncStatus.setText(R.string.sync_signed_out)
                b.syncButton.isEnabled = true
                b.syncButton.setText(R.string.sync_sign_in)
                b.syncButton.setOnClickListener { signIn() }
                b.syncCard.setOnClickListener(null)
            }
            Sync.State.Working -> {
                b.syncStatus.setText(R.string.sync_working)
                b.syncButton.isEnabled = false
            }
            is Sync.State.SignedIn -> {
                val last = if (state.lastSync > 0) formatAge(state.lastSync) else getString(R.string.sync_never)
                b.syncStatus.text = state.error?.let { getString(R.string.sync_error, it) }
                    ?: getString(R.string.sync_status, state.email ?: "Google", last)
                b.syncButton.isEnabled = true
                b.syncButton.setText(R.string.sync_now)
                b.syncButton.setOnClickListener {
                    if (state.error != null) signIn() else viewLifecycleOwner.lifecycleScope.launch { Sync.syncNow() }
                }
                b.syncCard.setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.sync_sign_out_confirm)
                        .setPositiveButton(R.string.sync_sign_out) { _, _ -> Sync.signOut() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun signIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { Sync.signIn(requireActivity()) }
                .onSuccess { pending ->
                    pending?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                }
                .onFailure { signInFailed(it) }
        }
    }

    private fun signInFailed(error: Throwable) {
        Toast.makeText(
            requireContext(),
            getString(R.string.sync_failed_sign_in, error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
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
