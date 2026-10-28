package io.github.kobi2187.readaloud

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/** The real app, with an in-test WorkManager (Robolectric does not run App Startup). */
class TestApp : ReadAloudApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }
}
