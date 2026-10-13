package com.cartube.net

/**
 * YouTube answers too many requests from one connection with HTTP 429 (a captcha the app
 * can't show). When that happens, optional background work pauses for a while so the
 * limit can lift; playing videos and explicit searches still go through.
 */
object RateLimit {
    private const val COOLDOWN_MS = 15 * 60 * 1000L

    @Volatile
    private var until = 0L

    fun hit() {
        until = System.currentTimeMillis() + COOLDOWN_MS
    }

    /** True while optional requests (category lookups, prefetching, Shorts mixing) should wait. */
    val active: Boolean get() = System.currentTimeMillis() < until
}
