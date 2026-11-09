package io.github.kobi2187.readaloud

import android.content.Context
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.ChannelRepository
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.sync.SpokenText
import io.github.kobi2187.readaloud.sync.SyncEngine
import io.github.kobi2187.readaloud.sync.Translations
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.GeminiTts
import io.github.kobi2187.readaloud.tts.OfflineVoices
import io.github.kobi2187.readaloud.tts.Pronunciation
import io.github.kobi2187.readaloud.tts.SystemTts
import io.github.kobi2187.readaloud.web.Translator
import io.github.kobi2187.readaloud.web.WebClient
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
    // Gemini answers only once the whole clip is generated, which can take a while for long posts.
    val geminiTts = GeminiTts(http.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build())
    val systemTts = SystemTts(appContext)
    val offlineVoices = OfflineVoices(appContext, http, wanted = {
        settings.state.value.let { Pronunciation(phonikud = it.usePhonikud, stress = it.ipaStress) }
    })
    val translations = Translations(db.messages(), Translator(http), settings)
    private val spokenText = SpokenText(db.messages(), db.channels(), translations)
    val renderer = AudioRenderer(
        appContext, db.messages(), settings, geminiTts, azureTts, systemTts, network, offlineVoices, spoken = spokenText::of,
    )
    val web = WebClient(http)
    val sync = SyncEngine(db, telegram, renderer, settings, network, File(appContext.filesDir, "avatars"), web, translations)
    val channels = ChannelRepository(db, telegram, renderer, web)
}
