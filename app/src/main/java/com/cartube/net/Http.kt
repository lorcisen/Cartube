package com.cartube.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /** Desktop browser user agent; YouTube serves the most formats to it. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
