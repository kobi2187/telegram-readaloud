package io.github.kobi2187.readaloud.web

import io.github.kobi2187.readaloud.tts.AuthException
import io.github.kobi2187.readaloud.tts.CloudTtsException
import io.github.kobi2187.readaloud.tts.ThrottledException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A translated text and the language Google detected it was written in. */
data class Translation(val text: String, val sourceLang: String)

/**
 * Google Translate. Without a key it uses the free endpoint Google's own web widgets call
 * (translate.googleapis.com, no account needed, but rate-limited and unofficial); with a Google
 * Cloud Translation API key it uses the official API.
 */
class Translator(
    http: OkHttpClient,
    private val freeUrl: HttpUrl = "https://translate.googleapis.com/translate_a/single".toHttpUrl(),
    private val cloudUrl: HttpUrl = "https://translation.googleapis.com/language/translate/v2".toHttpUrl(),
    private val minIntervalMs: Long = 400,
) {
    private val http = http.newBuilder().followRedirects(false).build()
    private var last = 0L

    fun translate(text: String, to: String, apiKey: String = ""): Translation {
        val parts = chunks(text)
        val out = ArrayList<String>()
        var source: String? = null
        for (part in parts) {
            val t = if (apiKey.isBlank()) free(part, to) else cloud(part, to, apiKey.trim())
            out += t.text
            if (source == null) source = t.sourceLang
        }
        return Translation(out.joinToString("\n\n"), normalizeLang(source.orEmpty()))
    }

    @Synchronized
    private fun pace() {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }

    private fun free(text: String, to: String): Translation {
        pace()
        // POST: long texts fit, and Google is less quick to ask for a captcha than with GET.
        val url = freeUrl.newBuilder().addQueryParameter("client", "gtx").addQueryParameter("sl", "auto")
            .addQueryParameter("tl", to).addQueryParameter("dt", "t").build()
        val request = Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
        http.newCall(request).execute().use { res ->
            if (res.code == 429 || res.code == 302 || res.code == 503) {
                // 302 to google.com/sorry: too many requests from this address for now.
                throw ThrottledException(res.header("Retry-After")?.toLongOrNull(), quota = false)
            }
            if (res.code != 200) throw CloudTtsException("Google Translate failed (HTTP ${res.code})")
            val data = Json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonArray
                ?: throw CloudTtsException("Unexpected Google Translate response")
            val sb = StringBuilder()
            (data.getOrNull(0) as? JsonArray)?.forEach { seg ->
                ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.let { sb.append(it.content) }
            } ?: throw CloudTtsException("Unexpected Google Translate response")
            val source = (data.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return Translation(sb.toString().trim(), normalizeLang(source))
        }
    }

    private fun cloud(text: String, to: String, key: String): Translation {
        val body = buildJsonObject {
            put("q", text)
            put("target", to)
            put("format", "text")
        }.toString().toRequestBody("application/json".toMediaType())
        val url = cloudUrl.newBuilder().addQueryParameter("key", key).build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
            if (res.code == 400 || res.code == 401 || res.code == 403) throw AuthException("Google Cloud Translation", res.code)
            if (res.code == 429) throw ThrottledException(null, quota = true)
            if (res.code != 200) throw CloudTtsException("Google Cloud Translation failed (HTTP ${res.code})")
            val first = Json.parseToJsonElement(res.body?.string().orEmpty()).jsonObject.getValue("data").jsonObject
                .getValue("translations").jsonArray.first().jsonObject
            return Translation(
                first.getValue("translatedText").jsonPrimitive.content.trim(),
                normalizeLang(first["detectedSourceLanguage"]?.jsonPrimitive?.content.orEmpty()),
            )
        }
    }

    companion object {
        /** Languages a channel can be read in (each has a phone voice; Hebrew also the offline voices). */
        val TARGETS = linkedMapOf("he" to "עברית", "en" to "English", "ru" to "Русский", "ar" to "العربية")

        /** Requests are kept under this size; longer posts go in parts, split between paragraphs. */
        const val MAX_CHUNK = 4000

        /** Google still reports Hebrew as "iw"; regional variants count as the language. */
        fun normalizeLang(code: String): String {
            val base = code.lowercase().split('-', '_').first()
            return if (base == "iw") "he" else base
        }

        fun chunks(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (t.length <= MAX_CHUNK) return listOf(t)
            val out = ArrayList<String>()
            var current = StringBuilder()
            for (para in t.split(Regex("\\n\\s*\\n"))) {
                for (p in if (para.length <= MAX_CHUNK) listOf(para) else hardSplit(para)) {
                    if (current.length + p.length + 2 > MAX_CHUNK && current.isNotEmpty()) {
                        out += current.toString().trim()
                        current = StringBuilder()
                    }
                    current.append(p).append("\n\n")
                }
            }
            if (current.isNotEmpty()) out += current.toString().trim()
            return out
        }

        private fun hardSplit(s: String): List<String> {
            val out = ArrayList<String>()
            var rest = s
            while (rest.length > MAX_CHUNK) {
                var cut = Regex("[.!?\\n]\\s").findAll(rest.substring(0, MAX_CHUNK)).lastOrNull()?.range?.first ?: -1
                if (cut <= 0) cut = rest.lastIndexOf(' ', MAX_CHUNK)
                if (cut <= 0) cut = MAX_CHUNK - 1
                out += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
