package com.cartube.ui

import com.cartube.R
import com.cartube.data.Library
import com.cartube.youtube.Channel
import com.google.android.material.button.MaterialButton

/** Shows "Prenumerera" or "Prenumererar" and toggles the subscription on tap. */
fun bindSubscribeButton(button: MaterialButton, channel: Channel) {
    fun render() {
        val subscribed = Library.isSubscribed(channel.url)
        button.setText(if (subscribed) R.string.subscribed else R.string.subscribe)
        button.isChecked = subscribed
    }
    button.isCheckable = true
    render()
    button.setOnClickListener {
        Library.toggleSubscription(channel)
        render()
    }
}
