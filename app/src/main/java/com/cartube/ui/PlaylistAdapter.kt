package com.cartube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cartube.R
import com.cartube.databinding.ItemPlaylistBinding

/** Rows for playlists, both the user's own and YouTube search results. */
class PlaylistAdapter(
    private val onClick: (Row) -> Unit,
) : ListAdapter<PlaylistAdapter.Row, PlaylistAdapter.Holder>(Diff) {

    data class Row(val key: String, val name: String, val meta: String, val thumbnail: String?)

    class Holder(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.name.text = row.name
        holder.binding.meta.text = row.meta
        holder.binding.thumbnail.load(row.thumbnail) {
            placeholder(R.color.placeholder)
            error(R.color.placeholder)
        }
        holder.binding.root.setOnClickListener { onClick(row) }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }
}
