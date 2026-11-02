package io.github.kobi2187.readaloud.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Google's Gemini text-to-speech through the official Gemini API ("Interactions"). A free API key
 * from Google AI Studio needs only a Google account, no billing. The models detect the language
 * themselves, so a mixed Hebrew/English post is one request with one voice.
 *
 * Free-tier quotas are per model, so when one model's quota runs out the next one is tried.
 */
class GeminiTts(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://generativelanguage.googleapis.com/".toHttpUrl(),
    private val models: List<String> = DEFAULT_MODELS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 4_000,
) {
    private val pausedUntil = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private var lastRequestAt = 0L

    /**
     * Speaks [text] with [voice] and returns 16-bit mono PCM. A per-minute rate limit is waited out
     * for up to [maxWaitMs]; otherwise the next model is tried, then [ThrottledException] is thrown.
     */
    fun synthesize(key: String, text: String, voice: String, maxWaitMs: Long = 0, timeoutMs: Long = 90_000): Wav.Clip {
        require(key.isNotBlank()) { "No Gemini API key" }
        var waited = 0L
        while (true) {
            val now = clock()
            val model = models.firstOrNull { (pausedUntil[it] ?: 0L) <= now }
            if (model == null) {
                val soonest = models.minOf { pausedUntil[it] ?: now }
                val wait = soonest - now
                if (waited + wait <= maxWaitMs) {
                    Thread.sleep(wait.coerceAtLeast(0))
                    waited += wait
                    continue
                }
                throw ThrottledException(wait / 1000, quota = wait > 10 * 60_000L)
            }
            try {
                return request(model, key, text, voice, timeoutMs)
            } catch (e: ThrottledException) {
                val pauseMs = (e.retryAfterSeconds ?: 60L) * 1000
                pausedUntil[model] = now + pauseMs
                // A short per-minute pause is worth waiting out rather than switching to a lesser model.
                if (!e.quota && waited + pauseMs <= maxWaitMs) {
                    Thread.sleep(pauseMs)
                    waited += pauseMs
                    pausedUntil.remove(model)
                }
            } catch (e: ModelUnavailable) {
                pausedUntil[model] = now + 24 * 3600_000L
            }
        }
    }

    /** True while every model is waiting out a quota or rate limit. */
    fun allPaused(): Boolean = clock().let { now -> models.all { (pausedUntil[it] ?: 0L) > now } }

    private class ModelUnavailable : IOException("Model not available")

    private fun request(model: String, key: String, text: String, voice: String, timeoutMs: Long): Wav.Clip {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                addJsonObject {
                    put("type", "user_input")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                            putJsonArray("annotations") {
                                addJsonObject {
                                    put("type", "speech_metadata")
                                    put("style", STYLE)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("response_format") { put("type", "audio") }
            putJsonObject("generation_config") {
                putJsonArray("speech_config") { addJsonObject { put("voice", voice) } }
            }
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1beta/interactions").build())
            .header("x-goog-api-key", key.trim())
            .post(body.toString().toRequestBody(JSON))
            .build()
        pace()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.isSuccessful) return parseAudio(raw)
            throw errorFor(response.code, raw)
        }
    }

    /** The free tier allows only a few requests a minute; do not burst. */
    private fun pace() {
        synchronized(lock) {
            val wait = lastRequestAt + minIntervalMs - clock()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = clock()
        }
    }

    private fun errorFor(code: Int, raw: String): IOException {
        val error = runCatching { Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        val details = error?.get("details") as? JsonArray
        val reasons = details?.mapNotNull { (it as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }.orEmpty()
        val quotaIds = details?.flatMap { d ->
            ((d as? JsonObject)?.get("violations") as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.get("quotaId")?.jsonPrimitive?.contentOrNull
            }.orEmpty()
        }.orEmpty()
        val retryDelay = details?.firstNotNullOfOrNull { d ->
            (d as? JsonObject)?.get("retryDelay")?.jsonPrimitive?.contentOrNull?.removeSuffix("s")?.toDoubleOrNull()
        }
        return when {
            code == 401 || code == 403 -> AuthException(SERVICE, code)
            code == 400 && ("API_KEY_INVALID" in reasons || message.contains("API key", ignoreCase = true)) ->
                AuthException(SERVICE, code)
            code == 429 -> {
                val daily = quotaIds.any { it.contains("PerDay", ignoreCase = true) } || message.contains("per day", ignoreCase = true)
                val seconds = if (daily) secondsUntilQuotaReset() else (retryDelay?.toLong()?.plus(1) ?: 60L)
                ThrottledException(seconds, quota = daily)
            }
            code == 404 -> ModelUnavailable()
            code == 400 || code == 413 -> RejectedTextException(code)
            else -> IOException("Gemini API HTTP $code: ${message.take(200)}")
        }
    }

    /** Daily quotas reset at midnight Pacific time. */
    private fun secondsUntilQuotaReset(): Long {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), PACIFIC)
        val reset = now.toLocalDate().plusDays(1).atStartOfDay(PACIFIC)
        return java.time.Duration.between(now, reset).seconds + 60
    }

    companion object {
        val DEFAULT_MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")
        const val DEFAULT_VOICE = "Charon"
        const val SAMPLE_RATE = 24_000
        private const val SERVICE = "Gemini API"
        private const val STYLE = "calm, clear news reading"
        /** Longer posts are split; a single request can produce only a few minutes of audio. */
        private const val MAX_CHARS = 3_000
        private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        private val JSON = "application/json".toMediaType()

        /** Prebuilt voices with Google's one-word character descriptions. */
        val VOICES = listOf(
            "Charon" to "Informative", "Kore" to "Firm", "Puck" to "Upbeat", "Zephyr" to "Bright",
            "Fenrir" to "Excitable", "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy",
            "Callirrhoe" to "Easy-going", "Autonoe" to "Bright", "Enceladus" to "Breathy", "Iapetus" to "Clear",
            "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth", "Erinome" to "Clear",
            "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
            "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward",
            "Achird" to "Friendly", "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively",
            "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
        )

        /** [text] in pieces of at most [max] characters, cut between paragraphs or sentences. */
        fun chunks(text: String, max: Int = MAX_CHARS): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.length <= max) return listOf(trimmed)
            val pieces = trimmed.split(Regex("(?<=[.!?…])\\s+|\n+")).flatMap { piece ->
                if (piece.length <= max) listOf(piece) else piece.chunked(max) // no sentence breaks at all
            }
            val out = ArrayList<String>()
            val current = StringBuilder()
            for (piece in pieces) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > max) {
                    out += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
            if (current.isNotEmpty()) out += current.toString()
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /**
         * Finds the last audio item in an Interactions response (steps → model_output → content)
         * and decodes it: WAV as documented, or raw 16-bit PCM at 24 kHz.
         */
        fun parseAudio(raw: String): Wav.Clip {
            val root = Json.parseToJsonElement(raw)
            val audio = ArrayList<JsonObject>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        val type = (e["type"] as? JsonPrimitive)?.contentOrNull
                        if (type == "audio" && e["data"] is JsonPrimitive) audio += e
                        e.values.forEach(::walk)
                    }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(root)
            val item = audio.lastOrNull() ?: throw RejectedTextException(200)
            val bytes = Base64.getDecoder().decode(item.getValue("data").jsonPrimitive.content)
            if (bytes.isEmpty()) throw RejectedTextException(200)
            if (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return Wav.parse(bytes)
            val mime = (item["mime_type"] ?: item["mimeType"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: SAMPLE_RATE
            return Wav.Clip(Wav.Format(rate, 1, 16), bytes)
        }
    }
}
