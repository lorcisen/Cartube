package com.cartube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.cartube.net.Http
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream

/**
 * Loads googlevideo.com media the way YouTube's own clients do. Plain GET requests with a
 * Range header get throttled or rejected, so `videoplayback` URLs are fetched with a POST,
 * a request counter (`rn`) and, for DASH segments, the byte range as a URL parameter.
 */
class YoutubeDataSource private constructor(
    private val client: OkHttpClient,
    private val rangeAsParameter: Boolean,
) : BaseDataSource(true) {

    class Factory(
        private val rangeAsParameter: Boolean,
        private val client: OkHttpClient = Http.client,
    ) : DataSource.Factory {
        override fun createDataSource() = YoutubeDataSource(client, rangeAsParameter)
    }

    private var call: Call? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var requestNumber = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val isPlayback = dataSpec.uri.path?.startsWith("/videoplayback") == true
        val position = dataSpec.position
        val length = dataSpec.length
        val hasRange = position != 0L || length != C.LENGTH_UNSET.toLong()
        val rangeEnd = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""

        var url = dataSpec.uri.toString()
        if (isPlayback && !url.contains("&rn=")) url += "&rn=${requestNumber++}"

        val builder = Request.Builder()
        dataSpec.httpRequestHeaders.forEach { (name, value) -> builder.header(name, value) }

        val rangeInUrl = isPlayback && rangeAsParameter
        if (hasRange) {
            if (rangeInUrl) url += "&range=$position-$rangeEnd"
            else builder.header("Range", "bytes=$position-$rangeEnd")
        }

        builder.url(url)
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                    YoutubeParsingHelper.getVisionOsUserAgent(null)
                } else {
                    Http.USER_AGENT
                },
            )
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com")
        }
        if (isPlayback) builder.post(PLAYBACK_BODY.toRequestBody()) else builder.get()

        val newCall = client.newCall(builder.build())
        call = newCall
        val resp = try {
            newCall.execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        response = resp

        if (!resp.isSuccessful) {
            val headers = resp.headers.toMultimap()
            val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
            closeQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                resp.code, resp.message, null, headers, dataSpec, body,
            )
        }

        val responseBody = resp.body ?: throw IOException("Empty response body")
        // A server that ignores the Range header answers 200 with the whole file.
        val skip = if (!rangeInUrl && resp.code == 200 && position > 0) position else 0L
        val contentLength = responseBody.contentLength()
        bytesRemaining = when {
            length != C.LENGTH_UNSET.toLong() -> length
            contentLength >= 0 -> contentLength - skip
            else -> C.LENGTH_UNSET.toLong()
        }

        val stream = responseBody.byteStream()
        input = stream
        skipFully(stream, skip)

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = input?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let(Uri::parse)

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        closeQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        call?.cancel()
        runCatching { input?.close() }
        runCatching { response?.close() }
        call = null
        input = null
        response = null
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n == -1) throw IOException("Stream ended while skipping")
            left -= n
        }
    }

    private companion object {
        val PLAYBACK_BODY = byteArrayOf(0x78, 0)
    }
}
