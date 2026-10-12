package com.cartube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.databinding.ItemChannelBinding
import com.cartube.youtube.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
) : ListAdapter<Channel, ChannelAdapter.Holder>(Diff) {

    class Holder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = getItem(position)
        holder.binding.name.text = channel.name
        holder.binding.avatar.load(channel.avatar) {
            transformations(CircleCropTransformation())
            placeholder(R.drawable.avatar_placeholder)
            error(R.drawable.avatar_placeholder)
        }
        holder.binding.root.setOnClickListener { onClick(channel) }
    }

    private object Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.url == b.url
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }
}
