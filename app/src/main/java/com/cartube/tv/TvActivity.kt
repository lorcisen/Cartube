package com.cartube.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.cartube.R

/** The TV start screen (Google TV launches this; phones never see it). */
class TvActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        // Lets a phone show its video here while it plays the sound itself.
        com.cartube.tvlink.TvLinkServer.start(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.tvContent, TvBrowseFragment()).commit()
        }
    }

    companion object {
        fun play(context: android.content.Context, video: com.cartube.youtube.VideoItem) {
            context.startActivity(video.putInto(Intent(context, TvPlayerActivity::class.java)))
        }
    }
}
