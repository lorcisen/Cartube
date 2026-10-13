package com.cartube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cartube.R
import com.cartube.databinding.ItemSuggestionBinding

/** Search history (clock icon) and live suggestions (magnifier) under the search box. */
class SuggestionAdapter(
    private val onPick: (String) -> Unit,
    private val onFill: (String) -> Unit,
    private val onRemove: (String) -> Unit,
) : RecyclerView.Adapter<SuggestionAdapter.Holder>() {

    data class Row(val text: String, val fromHistory: Boolean)

    private var rows: List<Row> = emptyList()

    class Holder(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(newRows: List<Row>) {
        rows = newRows
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.b.text.text = row.text
        holder.b.icon.setImageResource(if (row.fromHistory) R.drawable.ic_history else R.drawable.ic_search)
        holder.b.root.setOnClickListener { onPick(row.text) }
        holder.b.fill.setOnClickListener { onFill(row.text) }
        holder.b.root.setOnLongClickListener {
            if (row.fromHistory) onRemove(row.text)
            row.fromHistory
        }
    }
}
