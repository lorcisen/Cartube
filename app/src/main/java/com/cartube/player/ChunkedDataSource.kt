package com.cartube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Downloads a whole googlevideo.com file as a series of ranged requests. YouTube throttles
 * or cuts off single requests for large files, so downloads ask for a few MB at a time.
 * The file size comes from the `clen` parameter in the stream URL.
 */
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            // Unknown size: fall back to a single request.
            spec = null
            opened = true
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length else total
        chunkRemaining = 0
        opened = true
        return end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val base = spec ?: return upstream.read(buffer, offset, length)
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            upstream.close()
            val size = minOf(CHUNK_SIZE, end - position)
            upstream.open(base.subrange(position - base.position, size))
            chunkRemaining = size
        }
        val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            // The server sent less than asked; ask again from here.
            chunkRemaining = 0
            return 0
        }
        position += read
        chunkRemaining -= read
        return read
    }

    override fun getUri(): Uri? = spec?.uri ?: upstream.uri

    override fun close() {
        if (opened) upstream.close()
        opened = false
        spec = null
    }

    private companion object {
        const val CHUNK_SIZE = 4L * 1024 * 1024
    }
}
