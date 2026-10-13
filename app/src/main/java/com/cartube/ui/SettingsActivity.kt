package com.cartube.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.cartube.BuildConfig
import com.cartube.R
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.Preference>("version")?.summary = BuildConfig.VERSION_NAME
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
