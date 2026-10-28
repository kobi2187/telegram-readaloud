package io.github.kobi2187.readaloud.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.ReadAloudApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as ReadAloudApp).graph
        // WorkManager stops a worker after 10 minutes; leave room to finish cleanly.
        graph.sync.sync(deadlineMillis = System.currentTimeMillis() + 8 * 60_000L)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, ReadAloudApp.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val PERIODIC = "periodic-sync"
        private const val NOW = "sync-now"

        private fun constraints(allowMetered: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()

        fun schedulePeriodic(context: Context, intervalMinutes: Int, allowMetered: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (intervalMinutes <= 0) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(constraints(allowMetered))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Syncs as soon as there is a network; waits for one if offline. */
        fun syncNow(context: Context, allowMetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(allowMetered))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
