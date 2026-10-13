package com.cartube.ui

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** True on tablets (smallest side at least 600 dp); phones keep their one-column layout. */
val Context.isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

/**
 * On tablets, lays the list out as a grid with as many columns as fit [minItemWidthDp].
 * Positions for which [fullWidth] is true (headers) span the whole row.
 */
fun RecyclerView.useGrid(
    minItemWidthDp: Int = 320,
    availableWidthDp: Int = resources.configuration.screenWidthDp,
    fullWidth: (Int) -> Boolean = { false },
) {
    val columns = if (context.isTablet) (availableWidthDp / minItemWidthDp).coerceIn(1, 4) else 1
    if (columns == 1) {
        if (layoutManager !is LinearLayoutManager || layoutManager is GridLayoutManager) {
            layoutManager = LinearLayoutManager(context)
        }
        return
    }
    layoutManager = GridLayoutManager(context, columns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (fullWidth(position)) columns else 1
        }
    }
}
