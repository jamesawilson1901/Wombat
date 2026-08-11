package com.magpie

import android.app.Application
import com.magpie.service.Notifications
import com.magpie.service.WatcherService

class MagpieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        // Started on app launch as well as on boot (§4). Safe no-op until the
        // setup wizard has granted what the service needs.
        WatcherService.startIfConfigured(this)
    }
}
