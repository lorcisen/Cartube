package com.cartube

import android.app.Application
import com.cartube.data.Downloads
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.data.Positions
import com.cartube.data.SearchHistory
import com.cartube.data.Recommendations
import com.cartube.data.Settings
import com.google.android.material.color.DynamicColors
import com.cartube.data.Sync
import com.cartube.net.Http
import com.cartube.net.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class CarTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        if (Settings.dynamicColor) DynamicColors.applyToActivitiesIfAvailable(this)
        Library.init(this)
        Feed.init(this)
        Downloads.init(this)
        Positions.init(this)
        SearchHistory.init(this)
        Recommendations.init(this)
        Sync.init(this)

        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(Http.client),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
    }
}
