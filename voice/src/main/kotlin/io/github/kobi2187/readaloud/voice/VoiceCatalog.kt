package io.github.kobi2187.readaloud.voice

import java.io.File

/** A file of a downloadable voice, pinned to a revision and checked by size and SHA-256. */
data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/**
 * A downloadable offline Hebrew voice. Files with the same [ModelFile.name] are shared between
 * voices (the renikud phonemizer, the Blue engine) and downloaded once.
 */
data class VoicePack(
    val id: String,
    val name: String,
    val male: Boolean,
    /** Natural-sounding but several times slower to render than the fast voices. */
    val heavy: Boolean,
    val license: String,
    val files: List<ModelFile>,
    /** False for an add-on that is not a voice (the Phonikud pronunciation model). */
    val isVoice: Boolean = true,
    val model: (File) -> VoiceModel,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val g2p: ModelFile? get() = files.firstOrNull { it.name == VoiceCatalog.G2P_FILE }
}

/** The offline voices on offer, all speaking renikud IPA. */
object VoiceCatalog {
    const val G2P_FILE = "renikud.onnx"

    private const val HF = "https://huggingface.co"
    private const val RENIKUD = "$HF/renikud/renikud/resolve/a59c632c8cbac0f8c63f298cad77ef3a3a2547c9"
    private const val NOA = "$HF/thewh1teagle/piper-he-noa/resolve/23f8e06909e9a1e562a50f99df63e3785451379a"
    private const val PHONIKUD = "$HF/Phonikud/phonikud-tts-checkpoints/resolve/ff64ba389a3833c49a85c58fc51a7060a6984b87"
    private const val KOKORO = "$HF/thewh1teagle/kokoro-hebrew-nc/resolve/07e2334fc252d7965cb8dbd503bf7565bad71228"
    private const val BLUE = "$HF/notmax123/BlueTTS2.5-onnx/resolve/95feb5d0cbfc8e5a95bffb067d03537cbf35be3e"
    private const val BLUE_DIR = "blue25"

    private val renikud = ModelFile(G2P_FILE, "$RENIKUD/model.onnx", 20_620_594, "8b881a3a8f00283d86c6d1feea44d37e09c1ea6609a4de7d820d937e7f3dbbca")
    private val phonikudConfig = ModelFile(
        "phonikud-piper.config.json", "$PHONIKUD/model.config.json", 7_073,
        "7f790dce7e26969a535ecda2715cc7da9c5261269cbf11c213d107b611458679",
    )
    private val blueEngine = listOf(
        ModelFile("$BLUE_DIR/${BlueVoice.DURATION}", "$BLUE/${BlueVoice.DURATION}", 1_508_749, "3d0347cfc09234b78a20d5fa50b5c402d338fb99825f40ada8c70301edbb60ea"),
        ModelFile("$BLUE_DIR/${BlueVoice.TEXT_ENCODER}", "$BLUE/${BlueVoice.TEXT_ENCODER}", 27_419_694, "f971aa25315b93efff1850b39fe293aa93ae85df69fabf5e5022318cc632973c"),
        ModelFile("$BLUE_DIR/${BlueVoice.VECTOR_FIELD}", "$BLUE/${BlueVoice.VECTOR_FIELD}", 132_475_789, "50557b34acfafa4b34f21dae263faa74d3f3169f114ff927599a1eff110d0b6e"),
        ModelFile("$BLUE_DIR/${BlueVoice.VOCODER}", "$BLUE/${BlueVoice.VOCODER}", 101_411_919, "b0709df316ff44cc263c04fbf9d17371b16897071a48b79d3634d5f379fb3067"),
        ModelFile("$BLUE_DIR/${BlueVoice.CONFIG}", "$BLUE/${BlueVoice.CONFIG}", 9_260, "862d44194197de4873eccc8bfdab1835d15f98e469a90489df6da8f92a48c7ea"),
        ModelFile("$BLUE_DIR/${BlueVoice.VOCAB}", "$BLUE/${BlueVoice.VOCAB}", 3_157, "13fba5e735222fcf30d9c062d898982a1ba86d481f11560fbbdf6e0af79417b3"),
        ModelFile("$BLUE_DIR/${BlueVoice.UNCOND}", "$BLUE/${BlueVoice.UNCOND}", 52_732, "1c71c2b2836fd97f4c56eb21e258227650bbe0dcd906f64e2bba58e6ac09e76b"),
        ModelFile("$BLUE_DIR/${BlueVoice.STATS}", "$BLUE/${BlueVoice.STATS}", 1_920, "20d44fae010d46b859ee6238609780eb7bd5bf82320bad17b826a5dcfa4ed658"),
    )

    private fun blueVoice(id: String, name: String, male: Boolean, style: String, size: Long, sha: String): VoicePack {
        val styleFile = ModelFile("$BLUE_DIR/voices/$style.json", "$BLUE/voices/$style.json", size, sha)
        return VoicePack(id, name, male, heavy = true, license = "MIT", files = listOf(renikud) + blueEngine + styleFile) { dir ->
            VoiceModel.Blue(File(dir, BLUE_DIR), File(dir, styleFile.name))
        }
    }

    private fun phonikudVoice(id: String, name: String, file: String, sha: String) = VoicePack(
        id, name, male = true, heavy = false, license = "CC BY-NC (non-commercial)",
        files = listOf(renikud, ModelFile("phonikud-$file", "$PHONIKUD/$file", 63_516_050, sha), phonikudConfig),
    ) { dir -> VoiceModel.Piper(File(dir, "phonikud-$file"), File(dir, phonikudConfig.name)) }

    /** The first installed voice is the default, so Kokoro (the most natural) comes first. */
    val voices: List<VoicePack> = listOf(
        VoicePack(
            "kokoro-shaul", "Kokoro · Shaul", male = true, heavy = true, license = "Non-commercial",
            files = listOf(
                renikud,
                ModelFile("kokoro-he.onnx", "$KOKORO/kokoro.onnx", 325_505_393, "6b9b16fbba87fec17a21cd3b11203286e1e657014dbab236703fdf0cf13a9a2f"),
                ModelFile("kokoro-he-voices.npz", "$KOKORO/voices-hebrew.bin", 522_510, "5efaa05741532c144a535677f02e02fd72c76fd8ec45840d6e3194b38194e9a7"),
                ModelFile("kokoro-he-config.json", "$KOKORO/config.json", 2_526, "042f62ac55870757c2945a3a8d4b7fb01a9f9f775c12b1ef09f41fef1efd24f5"),
            ),
        ) { dir -> VoiceModel.Kokoro(File(dir, "kokoro-he.onnx"), File(dir, "kokoro-he-voices.npz"), File(dir, "kokoro-he-config.json"), "he_shaul") },
        VoicePack(
            "noa", "Noa", male = false, heavy = false, license = "CC BY 4.0",
            files = listOf(
                renikud,
                ModelFile("he_IL-noa-medium.onnx.json", "$NOA/he_IL-noa-medium.onnx.json", 5_048, "109a7a2a0739061510f318d987e81c27ba81ca57c9303444298f712f71b1082c"),
                ModelFile("he_IL-noa-medium.onnx", "$NOA/he_IL-noa-medium.onnx", 77_056_101, "83b18c0b8fad6ac00fcea5c5bdf3c1fdbec182f9eadad9f97bc423ab4e22be7d"),
            ),
        ) { dir -> VoiceModel.Piper(File(dir, "he_IL-noa-medium.onnx"), File(dir, "he_IL-noa-medium.onnx.json"), "noa") },
        phonikudVoice("shaul", "Shaul", "shaul.onnx", "7bcbd364b0bc357df8ef6c7c873e48f22897df16ec6c9d8f9d7ef792f6ae03c9"),
        phonikudVoice("michael", "Michael", "michael.onnx", "d2824d46ecd7ca8a206686d818d3178effe4661ce78bdb4e754eed32fe604320"),
        blueVoice("blue-female", "Blue · female", male = false, "female", 288_787, "2d15c822bd38005e1f59f47eea07cc3540d4ad3fc52b85d46dce04e5145a7494"),
        blueVoice("blue-male", "Blue · male", male = true, "libri_male_8088", 288_870, "3ad60cb68f8838dc00bacac968d55fd8469c1453283f27a210e15114671fb240"),
    )

    /** The voice suggested to new users. */
    const val RECOMMENDED_ID = "kokoro-shaul"

    /** Phonikud's pronunciation model, an optional download used by every voice instead of renikud. */
    const val PHONIKUD_ID = "phonikud"
    const val PHONIKUD_MODEL = "phonikud-1.0.int8.onnx"
    const val PHONIKUD_TOKENIZER = "phonikud-tokenizer.json"
    val phonikud = VoicePack(
        PHONIKUD_ID, "Phonikud", male = false, heavy = false, license = "MIT (model: CC BY 4.0)", isVoice = false,
        files = listOf(
            ModelFile(
                PHONIKUD_MODEL, "$HF/Phonikud/phonikud-onnx/resolve/b806189fe1fc0085b1012b7560ffb5e8ecfd72a2/phonikud-1.0.int8.onnx",
                307_844_158, "113afb58d3140502aa1e7691cdc6b240b56cf97e5852fc870e1a7fb5a400dd62",
            ),
            ModelFile(
                PHONIKUD_TOKENIZER, "$HF/dicta-il/dictabert-large-char-menaked/resolve/4dfc20b94565d7868e1c8c00bf039d38c071a062/tokenizer.json",
                18_016, "8e62e3b46c924e14fc32c749ef8944c311411ce9c4dc01c5b606953a169140ba",
            ),
        ),
    ) { throw UnsupportedOperationException("Phonikud is a pronunciation model, not a voice") }

    /** The voices and the add-ons, downloaded and installed the same way. */
    val all: List<VoicePack> = voices + phonikud

    fun byId(id: String?): VoicePack? = all.firstOrNull { it.id == id }

    /** All files of [pack] are present with the expected sizes in [dir]. */
    fun isInstalled(dir: File, pack: VoicePack) = pack.files.all { File(dir, it.name).length() == it.size }

    /** [pack] ready to speak; with [phonikud] (when its files are there) instead of renikud. */
    fun open(dir: File, pack: VoicePack, phonikud: Boolean = false) = HebrewNeuralVoice(
        File(dir, G2P_FILE),
        pack.model(dir),
        if (phonikud && isInstalled(dir, this.phonikud)) File(dir, PHONIKUD_MODEL) to File(dir, PHONIKUD_TOKENIZER) else null,
    )
}
