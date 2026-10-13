package com.cartube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.databinding.ItemCommentBinding
import com.cartube.databinding.SheetCommentsBinding
import com.cartube.youtube.YouTube
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Comments for a video in a bottom sheet, loading more as you reach the end. */
object CommentsSheet {

    fun show(activity: AppCompatActivity, url: String) {
        val binding = SheetCommentsBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        binding.root.minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.6).toInt()

        val adapter = CommentAdapter()
        binding.list.layoutManager = LinearLayoutManager(activity)
        binding.list.adapter = adapter

        var next: Page? = null
        var loading = false

        fun load() {
            if (loading) return
            loading = true
            binding.progress.isVisible = true
            activity.lifecycleScope.launch {
                val result = runCatching { YouTube.comments(url, next) }
                loading = false
                binding.progress.isVisible = false
                result.onSuccess { page ->
                    if (page.disabled) {
                        binding.message.isVisible = true
                        binding.message.setText(R.string.comments_disabled)
                        return@onSuccess
                    }
                    if (page.total > 0) {
                        binding.title.text = activity.getString(R.string.comments_count, formatCount(page.total.toLong()))
                    }
                    adapter.add(page.comments)
                    next = page.next
                }.onFailure {
                    binding.message.isVisible = adapter.itemCount == 0
                    binding.message.text = activity.getString(R.string.comments_failed, it.message ?: it.javaClass.simpleName)
                }
            }
        }

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                if (next != null && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) load()
            }
        })
        load()
        sheet.show()
    }

    private class CommentAdapter : RecyclerView.Adapter<CommentAdapter.Holder>() {
        private val items = ArrayList<YouTube.Comment>()

        class Holder(val b: ItemCommentBinding) : RecyclerView.ViewHolder(b.root)

        fun add(more: List<YouTube.Comment>) {
            val start = items.size
            items += more
            notifyItemRangeInserted(start, more.size)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            val context = holder.itemView.context
            holder.b.author.text = listOf(c.author, c.age).filter { it.isNotBlank() }.joinToString(" · ")
            holder.b.text.text = HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).trim()
            holder.b.likes.text = buildString {
                if (c.likes > 0) append("👍 ").append(formatCount(c.likes.toLong()))
                if (c.hearted) append("  ❤")
                if (c.replies > 0) append("   ").append(context.getString(R.string.replies, c.replies))
            }
            holder.b.likes.isVisible = holder.b.likes.text.isNotEmpty()
            holder.b.pinned.isVisible = c.pinned
            holder.b.avatar.load(c.avatar) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_placeholder)
                error(R.drawable.avatar_placeholder)
            }
        }
    }
}
