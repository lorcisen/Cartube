package com.cartube

import android.app.Application
import com.cartube.data.Library
import com.cartube.net.Http
import com.cartube.net.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class CarTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)

        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(Http.client),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
    }
}
