package com.cartube.player

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Serves [fallback] instead of failing when the upstream request fails. Used for subtitles,
 * so a caption file YouTube refuses to hand out never stops the video itself.
 */
class FailSafeDataSource(
    private val upstream: DataSource,
    private val fallback: ByteArray,
) : DataSource {

    class Factory(private val upstream: DataSource.Factory, private val fallback: ByteArray) : DataSource.Factory {
        override fun createDataSource() = FailSafeDataSource(upstream.createDataSource(), fallback)
    }

    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = try {
        current = upstream
        upstream.open(dataSpec)
    } catch (e: IOException) {
        runCatching { upstream.close() }
        val replacement = ByteArrayDataSource(fallback)
        current = replacement
        replacement.open(dataSpec.buildUpon().setPosition(0).setLength(androidx.media3.common.C.LENGTH_UNSET.toLong()).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        current?.close()
        current = null
    }
}
