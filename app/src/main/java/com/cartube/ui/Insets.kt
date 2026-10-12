package com.cartube.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Pads a root view so content stays clear of the status, navigation and cutout areas. */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
