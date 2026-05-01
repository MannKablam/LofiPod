package com.lofipod.app

import android.app.Application
import com.lofipod.app.data.PodcastRepository
import com.lofipod.app.data.db.AppDatabase

class LofiPodApp : Application() {
    lateinit var repo: PodcastRepository
        private set
    lateinit var db: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = PodcastRepository(this)
        db = AppDatabase.get(this)
    }

    companion object {
        @Volatile lateinit var instance: LofiPodApp
            private set
    }
}
