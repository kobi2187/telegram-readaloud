package io.github.kobi2187.readaloud.data

import kotlinx.coroutines.runBlocking

/**
 * Settings live in a process-wide DataStore, so a test class sees what an earlier one saved (a
 * Gemini key would make sync call the real service). Puts [cfg] in place and waits until it shows.
 */
fun SettingsRepository.resetForTest(cfg: AppSettings = AppSettings()): SettingsRepository {
    runBlocking { update { cfg } }
    val deadline = System.currentTimeMillis() + 5_000
    while (state.value != cfg && System.currentTimeMillis() < deadline) Thread.sleep(10)
    check(state.value == cfg) { "settings did not reset" }
    return this
}
