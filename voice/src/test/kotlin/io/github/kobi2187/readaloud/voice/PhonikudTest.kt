package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checked against the reference Python packages (phonikud-onnx 1.0.6, phonikud 0.4.1 and the HF
 * tokenizer) with the same model files: the resources in phonikud/ were produced by them.
 */
class PhonikudTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/phonikud/$name")!!.readText()).jsonArray
    private val reference = resource("reference.json").map { it.jsonObject }

    @Test
    fun rulesTurnTheReferenceNikudIntoTheReferenceIpa() {
        for (r in reference) {
            val vocalized = r.getValue("vocalized").jsonPrimitive.content
            assertEquals(r.getValue("text").jsonPrimitive.content, r.getValue("ipa").jsonPrimitive.content, PhonikudPhonemizer.phonemize(vocalized))
            assertEquals(r.getValue("ipa_nostress").jsonPrimitive.content, PhonikudPhonemizer.phonemize(vocalized, keepStress = false))
        }
    }

    private val dir = File(System.getProperty("voice.models").orEmpty())
    private val model = File(dir, VoiceCatalog.PHONIKUD_MODEL)
    private val tokenizer = File(dir, VoiceCatalog.PHONIKUD_TOKENIZER)

    @Test
    fun tokensMatchTheReferenceTokenizer() {
        assumeTrue("Phonikud files missing (VOICE_MODELS_DIR)", model.exists() && tokenizer.exists())
        PhonikudDiacritizer(OrtEnvironment.getEnvironment(), model, tokenizer).use { d ->
            for (c in resource("tokens.json").map { it.jsonObject }) {
                val text = c.getValue("text").jsonPrimitive.content
                val ids = d.tokens(text.codePoints().toArray()).map { it.id }
                assertEquals(text, c.getValue("ids").jsonArray.map { it.jsonPrimitive.long }, ids)
            }
        }
    }

    @Test
    fun thePipelineGivesTheReferenceIpa() {
        assumeTrue("Phonikud files missing (VOICE_MODELS_DIR)", model.exists() && tokenizer.exists())
        PhonikudG2p(OrtEnvironment.getEnvironment(), model, tokenizer).use { g2p ->
            // The int8 model's scores shift a little between ONNX Runtime versions: a letter whose
            // two best readings are close may go either way, so those sentences are not compared.
            val clear = reference.filter { it.getValue("min_margin").jsonPrimitive.content.toDouble() >= 0.5 }
            assert(clear.size >= 8)
            for (r in clear) {
                val text = r.getValue("text").jsonPrimitive.content
                assertEquals(text, r.getValue("ipa").jsonPrimitive.content, g2p.phonemize(text))
            }
        }
    }
}
