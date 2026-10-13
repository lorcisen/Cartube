package com.cartube.youtube

/** The 11-character video id from any common YouTube URL form. */
fun videoIdOf(url: String): String? =
    Regex("""(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
