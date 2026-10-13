package com.cartube.ui

import android.app.Activity
import android.content.res.Configuration
import com.cartube.R
import com.cartube.data.Settings

/** Pure black backgrounds in dark mode, for OLED screens. Call before setContentView. */
fun Activity.applyAmoledIfEnabled() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    if (night && Settings.amoled) theme.applyStyle(R.style.ThemeOverlay_CarTube_Amoled, true)
}
