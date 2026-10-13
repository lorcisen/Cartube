package com.cartube.ui

import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton

/** A drop-down with [options]; the button shows the current choice. */
fun <T> MaterialButton.bindChoice(
    options: List<T>,
    current: () -> T,
    label: (T) -> String,
    prefix: String? = null,
    onPick: (T) -> Unit,
) {
    fun render() {
        text = prefix?.let { "$it: ${label(current())}" } ?: label(current())
    }
    render()
    setOnClickListener { anchor: View ->
        val menu = PopupMenu(anchor.context, anchor)
        options.forEachIndexed { i, option ->
            menu.menu.add(0, i, i, label(option)).apply {
                isCheckable = true
                isChecked = option == current()
            }
        }
        menu.menu.setGroupCheckable(0, true, true)
        menu.setOnMenuItemClickListener { item ->
            onPick(options[item.itemId])
            render()
            true
        }
        menu.show()
    }
}

/** Single-choice dialog for screens where the sort lives in the toolbar menu. */
fun android.content.Context.pickSort(
    options: List<com.cartube.data.VideoSort>,
    current: com.cartube.data.VideoSort,
    label: (com.cartube.data.VideoSort) -> String = { getString(it.label) },
    onPick: (com.cartube.data.VideoSort) -> Unit,
) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(com.cartube.R.string.sort)
        .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current)) { dialog, which ->
            dialog.dismiss()
            onPick(options[which])
        }
        .show()
}
