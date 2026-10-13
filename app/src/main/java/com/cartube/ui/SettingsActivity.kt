package com.cartube.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.cartube.BuildConfig
import com.cartube.R
import com.cartube.data.Library
import com.cartube.data.SearchHistory
import com.cartube.data.Settings
import com.cartube.data.Updates
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import com.cartube.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private fun showBlocked() {
            val blocked = Library.blockedChannels()
            if (blocked.isEmpty()) {
                Toast.makeText(requireContext(), R.string.blocked_none, Toast.LENGTH_SHORT).show()
                return
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.blocked_channels)
                .setItems(blocked.map { it.name }.toTypedArray()) { _, which ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(blocked[which].name)
                        .setPositiveButton(R.string.unblock) { _, _ -> Library.unblockChannel(blocked[which].url) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .show()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.Preference>("version")?.summary = BuildConfig.VERSION_NAME
            findPreference<androidx.preference.Preference>("stats")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), StatsActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>(Settings.KEY_NOTIFY_NEW)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { com.cartube.notify.NewVideosWorker.schedule(requireContext()) }
                true
            }
            findPreference<androidx.preference.Preference>("blocked")?.setOnPreferenceClickListener {
                showBlocked()
                true
            }
            findPreference<androidx.preference.Preference>("clear_search_history")?.setOnPreferenceClickListener {
                SearchHistory.clear()
                Toast.makeText(requireContext(), R.string.search_history_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<androidx.preference.Preference>("check_update")?.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val update = Updates.check(requireContext(), force = true)
                    if (update == null) {
                        Toast.makeText(requireContext(), R.string.update_none, Toast.LENGTH_SHORT).show()
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.update_title)
                            .setMessage(getString(R.string.update_message, update.versionName))
                            .setPositiveButton(R.string.update_install) { _, _ ->
                                Updates.downloadAndInstall(requireContext(), update)
                                Toast.makeText(requireContext(), R.string.update_downloading, Toast.LENGTH_LONG).show()
                            }
                            .setNegativeButton(R.string.update_later, null)
                            .show()
                    }
                }
                true
            }

            findPreference<androidx.preference.Preference>(Settings.KEY_THEME)
                ?.setOnPreferenceChangeListener { _, _ ->
                    // Apply after the new value is saved.
                    view?.post { Settings.applyTheme() }
                    true
                }
            findPreference<androidx.preference.Preference>(Settings.KEY_AMOLED)
                ?.setOnPreferenceChangeListener { _, _ ->
                    view?.post { activity?.recreate() }
                    true
                }
        }
    }
}
