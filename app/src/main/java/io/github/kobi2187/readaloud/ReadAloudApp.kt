package io.github.kobi2187.readaloud

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.kobi2187.readaloud.sync.SyncWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ReadAloudApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, getString(R.string.sync_notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        graph.scope.launch {
            graph.settings.flow
                .map { it.syncIntervalMinutes to it.allowMetered }
                .distinctUntilChanged()
                .collect { (interval, metered) -> SyncWorker.schedulePeriodic(this@ReadAloudApp, interval, metered) }
        }
    }

    companion object {
        const val CHANNEL_SYNC = "sync"
    }
}
