package io.github.kobi2187.readaloud.tts

import io.github.kobi2187.readaloud.text.Lang

data class NeuralVoice(val id: String, val label: String, val male: Boolean)

/** Curated Microsoft neural voices (Azure Speech). Multilingual voices can also read Hebrew with the same speaker. */
object Voices {
    val hebrew = listOf(
        NeuralVoice("he-IL-AvriNeural", "Avri", male = true),
        NeuralVoice("he-IL-HilaNeural", "Hila", male = false),
        NeuralVoice("en-US-AndrewMultilingualNeural", "Andrew (multilingual)", male = true),
        NeuralVoice("en-US-AvaMultilingualNeural", "Ava (multilingual)", male = false),
        NeuralVoice("en-US-EmmaMultilingualNeural", "Emma (multilingual)", male = false),
        NeuralVoice("en-US-BrianMultilingualNeural", "Brian (multilingual)", male = true),
    )

    val english = listOf(
        NeuralVoice("en-US-AndrewNeural", "Andrew (US)", male = true),
        NeuralVoice("en-US-AvaNeural", "Ava (US)", male = false),
        NeuralVoice("en-US-EmmaNeural", "Emma (US)", male = false),
        NeuralVoice("en-US-BrianNeural", "Brian (US)", male = true),
        NeuralVoice("en-US-AriaNeural", "Aria (US, news)", male = false),
        NeuralVoice("en-US-GuyNeural", "Guy (US, news)", male = true),
        NeuralVoice("en-US-JennyNeural", "Jenny (US)", male = false),
        NeuralVoice("en-US-ChristopherNeural", "Christopher (US, news)", male = true),
        NeuralVoice("en-GB-SoniaNeural", "Sonia (UK)", male = false),
        NeuralVoice("en-GB-RyanNeural", "Ryan (UK)", male = true),
        NeuralVoice("en-US-AndrewMultilingualNeural", "Andrew (multilingual)", male = true),
        NeuralVoice("en-US-AvaMultilingualNeural", "Ava (multilingual)", male = false),
    )

    const val DEFAULT_HEBREW = "he-IL-AvriNeural"
    const val DEFAULT_ENGLISH = "en-US-AndrewNeural"

    /** Voices for scripts other than Hebrew and Latin, so quoted Arabic or Russian is not garbled. */
    private const val ARABIC = "ar-SA-HamedNeural"
    private const val RUSSIAN = "ru-RU-DmitryNeural"

    fun neuralFor(lang: Lang, hebrewVoice: String, englishVoice: String): String = when (lang) {
        Lang.HE -> hebrewVoice
        Lang.EN -> englishVoice
        Lang.AR -> ARABIC
        Lang.RU -> RUSSIAN
    }

    fun voiceKey(hebrewVoice: String, englishVoice: String) = "$hebrewVoice|$englishVoice|$ARABIC|$RUSSIAN"
}
