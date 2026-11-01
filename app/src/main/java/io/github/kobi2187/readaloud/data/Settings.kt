package io.github.kobi2187.readaloud.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.GeminiTts
import io.github.kobi2187.readaloud.tts.Voices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Which online voice to download with posts, if any. Offline voices (the downloadable Hebrew
 * neural voice, then the phone's own) fill in whenever the online one is unavailable.
 */
enum class CloudVoice { GEMINI, AZURE, NONE }

/** What a quick double press of a volume key does while reading. */
enum class VolumeKeyMode { OFF, CHANNELS, MESSAGES }

data class AppSettings(
    val cloudVoice: CloudVoice = CloudVoice.GEMINI,
    val geminiKey: String = "",
    val geminiVoice: String = GeminiTts.DEFAULT_VOICE,
    val azureKey: String = "",
    val azureRegion: String = AzureTts.DEFAULT_REGION,
    val hebrewVoice: String = Voices.DEFAULT_HEBREW,
    val englishVoice: String = Voices.DEFAULT_ENGLISH,
    val speed: Float = 1.0f,
    val volumeKeys: VolumeKeyMode = VolumeKeyMode.CHANNELS,
    val autoAdvance: Boolean = true,
    val announceChannel: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    val allowMetered: Boolean = true,
    val prefetchCount: Int = 100,
    val deletePlayedAudio: Boolean = true,
    val defaultStartDays: Int = 1,
    val currentChannel: String? = null,
) {
    /** Identifies the online voice; audio downloaded with another key is downloaded again. */
    val voiceKey: String get() = when (cloudVoice) {
        CloudVoice.GEMINI -> "gemini|$geminiVoice"
        CloudVoice.AZURE -> Voices.voiceKey(hebrewVoice, englishVoice)
        CloudVoice.NONE -> "none"
    }
    val azure: AzureTts.Account get() = AzureTts.Account(azureKey, azureRegion)

    /** The credentials of the selected online service, so a rejected key is not retried. */
    val cloudKey: String get() = when (cloudVoice) {
        CloudVoice.GEMINI -> geminiKey
        CloudVoice.AZURE -> "$azureRegion/$azureKey"
        CloudVoice.NONE -> ""
    }

    /** An online voice is configured (it still needs a network connection to download). */
    val cloudEnabled: Boolean get() = when (cloudVoice) {
        CloudVoice.GEMINI -> geminiKey.isNotBlank()
        CloudVoice.AZURE -> azure.usable
        CloudVoice.NONE -> false
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val legacyTtsMode = stringPreferencesKey("tts_mode")
        val cloudVoice = stringPreferencesKey("cloud_voice")
        val geminiKey = stringPreferencesKey("gemini_key")
        val geminiVoice = stringPreferencesKey("gemini_voice")
        val azureKey = stringPreferencesKey("azure_key")
        val azureRegion = stringPreferencesKey("azure_region")
        val hebrewVoice = stringPreferencesKey("hebrew_voice")
        val englishVoice = stringPreferencesKey("english_voice")
        val speed = floatPreferencesKey("speed")
        val volumeKeys = stringPreferencesKey("volume_keys")
        val autoAdvance = booleanPreferencesKey("auto_advance")
        val announceChannel = booleanPreferencesKey("announce_channel")
        val syncInterval = intPreferencesKey("sync_interval")
        val allowMetered = booleanPreferencesKey("allow_metered")
        val prefetch = intPreferencesKey("prefetch")
        val deletePlayed = booleanPreferencesKey("delete_played")
        val defaultStartDays = intPreferencesKey("default_start_days")
        val currentChannel = stringPreferencesKey("current_channel")
    }

    val flow: Flow<AppSettings> = store.data.map { it.toSettings() }

    /** Always-current snapshot for code that cannot suspend (player callbacks, loader threads). */
    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            cloudVoice = this[Keys.cloudVoice]?.let { runCatching { CloudVoice.valueOf(it) }.getOrNull() }
                ?: legacyCloudVoice(this) ?: d.cloudVoice,
            geminiKey = this[Keys.geminiKey] ?: d.geminiKey,
            geminiVoice = this[Keys.geminiVoice] ?: d.geminiVoice,
            azureKey = this[Keys.azureKey] ?: d.azureKey,
            azureRegion = this[Keys.azureRegion] ?: d.azureRegion,
            hebrewVoice = this[Keys.hebrewVoice] ?: d.hebrewVoice,
            englishVoice = this[Keys.englishVoice] ?: d.englishVoice,
            speed = this[Keys.speed] ?: d.speed,
            volumeKeys = this[Keys.volumeKeys]?.let { runCatching { VolumeKeyMode.valueOf(it) }.getOrNull() } ?: d.volumeKeys,
            autoAdvance = this[Keys.autoAdvance] ?: d.autoAdvance,
            announceChannel = this[Keys.announceChannel] ?: d.announceChannel,
            syncIntervalMinutes = this[Keys.syncInterval] ?: d.syncIntervalMinutes,
            allowMetered = this[Keys.allowMetered] ?: d.allowMetered,
            prefetchCount = this[Keys.prefetch] ?: d.prefetchCount,
            deletePlayedAudio = this[Keys.deletePlayed] ?: d.deletePlayedAudio,
            defaultStartDays = this[Keys.defaultStartDays] ?: d.defaultStartDays,
            currentChannel = this[Keys.currentChannel],
        )
    }

    /** Version 1.0 had a CLOUD (= Azure) / DEVICE switch. */
    private fun legacyCloudVoice(p: Preferences): CloudVoice? = when (p[Keys.legacyTtsMode]) {
        "CLOUD" -> if (!p[Keys.azureKey].isNullOrBlank()) CloudVoice.AZURE else null
        "DEVICE" -> CloudVoice.NONE
        else -> null
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.cloudVoice] = s.cloudVoice.name
            prefs[Keys.geminiKey] = s.geminiKey.trim()
            prefs[Keys.geminiVoice] = s.geminiVoice
            prefs[Keys.azureKey] = s.azureKey.trim()
            prefs[Keys.azureRegion] = s.azureRegion.trim().lowercase()
            prefs[Keys.hebrewVoice] = s.hebrewVoice
            prefs[Keys.englishVoice] = s.englishVoice
            prefs[Keys.speed] = s.speed
            prefs[Keys.volumeKeys] = s.volumeKeys.name
            prefs[Keys.autoAdvance] = s.autoAdvance
            prefs[Keys.announceChannel] = s.announceChannel
            prefs[Keys.syncInterval] = s.syncIntervalMinutes
            prefs[Keys.allowMetered] = s.allowMetered
            prefs[Keys.prefetch] = s.prefetchCount
            prefs[Keys.deletePlayed] = s.deletePlayedAudio
            prefs[Keys.defaultStartDays] = s.defaultStartDays
            if (s.currentChannel != null) prefs[Keys.currentChannel] = s.currentChannel else prefs.remove(Keys.currentChannel)
        }
    }
}
