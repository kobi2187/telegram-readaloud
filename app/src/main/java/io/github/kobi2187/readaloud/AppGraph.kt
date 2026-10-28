package io.github.kobi2187.readaloud

import android.content.Context
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.ChannelRepository
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.sync.SyncEngine
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.SystemTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies; one instance per process. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val db: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsRepository(appContext, scope)
    val network = NetworkMonitor(appContext)
    val telegram = TelegramClient(http)
    val azureTts = AzureTts(http)
    val systemTts = SystemTts(appContext)
    val renderer = AudioRenderer(appContext, db.messages(), settings, azureTts, systemTts, network)
    val sync = SyncEngine(db, telegram, renderer, settings, network, File(appContext.filesDir, "avatars"))
    val channels = ChannelRepository(db, telegram, renderer)
}
