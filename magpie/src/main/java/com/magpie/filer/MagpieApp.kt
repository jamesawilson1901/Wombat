package com.magpie.filer

import android.app.Application
import com.magpie.filer.watch.Notifications

class MagpieApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before anything is posted, including from the
        // boot receiver, which can run before the activity ever does.
        Notifications.createChannels(this)
    }
}
