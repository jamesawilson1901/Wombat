package com.wombat.split

import android.app.Application
import com.wombat.split.jobs.JobRepository

class WombatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JobRepository.init(this)
    }
}
