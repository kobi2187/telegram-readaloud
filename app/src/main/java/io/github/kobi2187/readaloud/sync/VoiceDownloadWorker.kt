package io.github.kobi2187.readaloud.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import io.github.kobi2187.readaloud.tts.OfflineVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads one offline voice, resuming after interruptions and retrying until it is done. */
class VoiceDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val voices get() = (applicationContext as ReadAloudApp).graph.offlineVoices
    private val voiceId get() = inputData.getString(KEY_VOICE).orEmpty()

    override suspend fun doWork(): Result {
        val id = voiceId
        if (voices.pack(id) == null) return Result.failure()
        // Visible progress, and protection from being stopped; fine to continue without it.
        runCatching { setForeground(foregroundInfo(0, voices.remainingBytes(id))) }
        var lastReport = 0L
        return try {
            withContext(Dispatchers.IO) {
                voices.download(id, stopped = { isStopped }) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500 || done == total) {
                        lastReport = now
                        setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                        runCatching { setForegroundAsync(foregroundInfo(done, total)) }
                    }
                }
            }
            Result.success()
        } catch (e: OfflineVoices.NotEnoughSpaceException) {
            Result.failure(workDataOf(KEY_REASON to REASON_SPACE, KEY_NEEDED to e.neededBytes))
        } catch (e: OfflineVoices.UnsupportedVoiceException) {
            Result.failure(workDataOf(KEY_REASON to REASON_UNSUPPORTED))
        } catch (e: OfflineVoices.ChecksumException) {
            Log.w(TAG, "Voice download corrupt", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_REASON to REASON_NETWORK))
        } catch (e: IOException) {
            Log.w(TAG, "Voice download interrupted: ${e.message}")
            if (isStopped) Result.failure() else Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, voices.remainingBytes(voiceId))

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val name = voices.pack(voiceId)?.name.orEmpty()
        val notification: Notification = NotificationCompat.Builder(applicationContext, ReadAloudApp.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.offline_voice_notification, name))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val id = NOTIFICATION_ID + voiceId.hashCode().mod(1000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        private const val TAG = "VoiceDownload"
        private const val NOTIFICATION_ID = 3000
        const val TAG_ALL = "voice-download"
        const val TAG_WIFI_ONLY = "voice-download-wifi-only"
        private const val TAG_VOICE = "voice:"
        const val KEY_VOICE = "voice"
        const val KEY_REASON = "reason"
        const val KEY_NEEDED = "needed"
        const val REASON_SPACE = "space"
        const val REASON_UNSUPPORTED = "unsupported"
        const val REASON_NETWORK = "network"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        fun voiceOf(tags: Set<String>): String? = tags.firstOrNull { it.startsWith(TAG_VOICE) }?.removePrefix(TAG_VOICE)

        /** Starts (or restarts, e.g. to allow mobile data) the download of [voiceId]. */
        fun start(context: Context, voiceId: String, allowMetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
                .setInputData(workDataOf(KEY_VOICE to voiceId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .addTag(TAG_ALL)
                .addTag(TAG_VOICE + voiceId)
                .apply { if (!allowMetered) addTag(TAG_WIFI_ONLY) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(voiceId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, voiceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(voiceId))
        }

        private fun name(voiceId: String) = "voice-download-$voiceId"
    }
}
