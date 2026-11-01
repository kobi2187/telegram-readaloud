package io.github.kobi2187.readaloud.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.ReadAloudApp
import io.github.kobi2187.readaloud.tts.OfflineHebrewVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Downloads the offline Hebrew voice (~98 MB), resuming after interruptions. */
class VoiceDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val voice = (applicationContext as ReadAloudApp).graph.offlineHebrew
        // Visible progress, and protection from being stopped; fine to continue without it.
        runCatching { setForeground(foregroundInfo(voice.downloadedBytes(), voice.totalBytes)) }
        var lastReport = 0L
        return try {
            withContext(Dispatchers.IO) {
                voice.download(stopped = { isStopped }) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500 || done == total) {
                        lastReport = now
                        setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                        runCatching { setForegroundAsync(foregroundInfo(done, total)) }
                    }
                }
            }
            Result.success()
        } catch (e: OfflineHebrewVoice.ChecksumException) {
            Log.w(TAG, "Voice download corrupt", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w(TAG, "Voice download interrupted: ${e.message}")
            if (isStopped) Result.failure() else Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val voice = (applicationContext as ReadAloudApp).graph.offlineHebrew
        return foregroundInfo(voice.downloadedBytes(), voice.totalBytes)
    }

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification: Notification = NotificationCompat.Builder(applicationContext, ReadAloudApp.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.offline_voice_notification))
            .setProgress(100, percent, total <= 0)
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
        private const val TAG = "VoiceDownload"
        private const val NOTIFICATION_ID = 2002
        const val NAME = "offline-hebrew-voice"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        fun start(context: Context, allowMetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
