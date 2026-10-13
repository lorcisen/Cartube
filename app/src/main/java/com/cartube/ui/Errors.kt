package com.cartube.ui

import android.content.Context
import com.cartube.R
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** A message people can act on, instead of the raw exception text. */
fun Context.describeError(error: Throwable?): String = when (error) {
    is ReCaptchaException -> getString(R.string.error_rate_limited)
    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> getString(R.string.error_offline)
    null -> "?"
    else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}
