package com.cartube.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import coil.executeBlocking
import coil.imageLoader
import coil.request.ImageRequest
import com.cartube.R
import com.cartube.data.Feed
import com.cartube.ui.formatAge
import com.cartube.youtube.VideoItem

class LatestWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var items: List<VideoItem> = emptyList()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit

        override fun onDataSetChanged() {
            items = Feed.saved().take(12)
        }

        override fun getCount() = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val item = items[position]
            val views = RemoteViews(context.packageName, R.layout.widget_item)
            views.setTextViewText(R.id.title, item.title)
            views.setTextViewText(
                R.id.meta,
                listOfNotNull(item.uploader, item.uploadedAt.takeIf { it > 0 }?.let { formatAge(it) }).joinToString(" · "),
            )
            thumbnail(item.thumbnail)?.let { views.setImageViewBitmap(R.id.thumbnail, it) }
            views.setOnClickFillInIntent(R.id.row, item.putInto(Intent()))
            return views
        }

        /** Runs on a binder thread, so loading synchronously is fine here. */
        private fun thumbnail(url: String?): Bitmap? {
            url ?: return null
            val request = ImageRequest.Builder(context).data(url).size(320, 180).allowHardware(false).build()
            return (context.imageLoader.executeBlocking(request).drawable as? BitmapDrawable)?.bitmap
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items[position].url.hashCode().toLong()
        override fun hasStableIds() = true
    }
}
