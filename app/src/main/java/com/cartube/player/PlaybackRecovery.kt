package com.cartube.player

/** Videos that failed once and are retried in a safer way (fresh stream URLs, no subtitles). */
object PlaybackRecovery {
    private val retried = mutableSetOf<String>()

    @Synchronized
    fun isRetrying(url: String) = url in retried

    /** Returns false if this video was already retried, so it is only attempted once. */
    @Synchronized
    fun markRetry(url: String): Boolean = retried.add(url)
}
