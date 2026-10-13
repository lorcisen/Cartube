package com.cartube.tv

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import com.cartube.R
import com.cartube.data.Positions
import com.cartube.ui.videoMeta
import com.cartube.youtube.VideoItem

/** A 16:9 card for a video in a TV row; it grows and lights up when focused. */
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            setInfoAreaBackgroundColor(context.getColor(R.color.tv_card))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? VideoItem ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = video.title
        val progress = Positions.progress(video.url)?.takeIf { it > 0.02f && it < 0.98f }
        card.contentText = listOfNotNull(
            card.context.videoMeta(video).ifBlank { null },
            progress?.let { "▶ ${(it * 100).toInt()} %" },
        ).joinToString("  ")
        card.mainImageView.load(video.thumbnail) { placeholder(R.color.placeholder) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }

    private companion object {
        const val CARD_WIDTH = 400
        const val CARD_HEIGHT = 225
    }
}

/** Simple text tiles for actions such as "Sök" and "Inställningar". */
class ActionCardPresenter : Presenter() {
    data class Action(val id: String, val title: String, val icon: Int)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(240, 135)
            setInfoAreaBackgroundColor(context.getColor(R.color.tv_card))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val action = item as? Action ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = action.title
        card.mainImageView.setImageResource(action.icon)
        card.mainImageView.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
