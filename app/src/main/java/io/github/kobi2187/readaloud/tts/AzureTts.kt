package io.github.kobi2187.readaloud.tts

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Microsoft neural voices through the official Azure AI Speech REST API, using the user's own
 * key. The free tier (F0) includes 0.5 million characters a month and the same Hebrew voices
 * (Avri, Hila) and English voices as Microsoft Edge's read-aloud.
 */
class AzureTts(private val http: OkHttpClient) {

    data class Account(val key: String, val region: String) {
        val usable: Boolean get() = key.isNotBlank() && region.isNotBlank()
    }

    open class AzureException(message: String) : IOException(message)

    /** The key or region is wrong (401), or the resource is disabled (403). */
    class AuthException(code: Int) : AzureException("Azure Speech rejected the key (HTTP $code)")

    /** Monthly free quota used up, or too many requests; retry after [retryAfterSeconds]. */
    class ThrottledException(val retryAfterSeconds: Long?, val quota: Boolean) :
        AzureException(if (quota) "Azure Speech quota exhausted" else "Azure Speech throttled")

    /** The service refused this particular text; retrying will not help. */
    class RejectedTextException(code: Int) : AzureException("Azure Speech rejected the text (HTTP $code)")

    private val lock = Any()
    private var lastRequestAt = 0L

    /**
     * Speaks each (voice, text) part in order and returns MP3 (24 kHz, 48 kbit/s, mono), with
     * [trailingSilenceMs] of silence at the end. Blocking.
     */
    fun synthesize(
        account: Account,
        parts: List<Pair<String, String>>,
        trailingSilenceMs: Int = 0,
        timeoutMs: Long = 60_000,
    ): ByteArray {
        require(account.usable) { "No Azure Speech key" }
        val ssml = buildSsml(parts, trailingSilenceMs)
        val request = Request.Builder()
            .url("https://${account.region.trim().lowercase()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", account.key.trim())
            .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .header("User-Agent", USER_AGENT)
            .post(ssml.toRequestBody(SSML))
            .build()
        pace()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            when (response.code) {
                200 -> {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) throw RejectedTextException(200)
                    return bytes
                }
                401 -> throw AuthException(401)
                403 -> {
                    val body = response.body?.string().orEmpty()
                    if (body.contains("quota", ignoreCase = true)) throw ThrottledException(null, quota = true)
                    throw AuthException(403)
                }
                429 -> {
                    val retry = response.header("Retry-After")?.toLongOrNull()
                    throw ThrottledException(retry, quota = retry == null || retry > 3600)
                }
                400, 413, 415 -> throw RejectedTextException(response.code)
                else -> throw IOException("Azure Speech HTTP ${response.code}")
            }
        }
    }

    /** The free tier allows 20 requests a minute; keep below it. */
    private fun pace() {
        synchronized(lock) {
            val wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        const val DEFAULT_REGION = "westeurope"
        private const val USER_AGENT = "TGReadAloud-Android"
        private const val MIN_INTERVAL_MS = 3_100L
        private val SSML = "application/ssml+xml".toMediaType()

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) {
                when {
                    c == '&' -> append("&amp;")
                    c == '<' -> append("&lt;")
                    c == '>' -> append("&gt;")
                    c == '"' -> append("&quot;")
                    c == '\'' -> append("&apos;")
                    // Control characters are invalid in XML.
                    c.code < 0x20 && c != '\n' && c != '\t' -> append(' ')
                    else -> append(c)
                }
            }
        }

        private fun localeOf(voice: String): String =
            Regex("^([a-z]{2,3}-[A-Z]{2})-").find(voice)?.groupValues?.get(1) ?: "en-US"

        fun buildSsml(parts: List<Pair<String, String>>, trailingSilenceMs: Int): String {
            require(parts.isNotEmpty())
            val sb = StringBuilder()
            sb.append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='")
                .append(localeOf(parts.first().first)).append("'>")
            parts.forEachIndexed { i, (voice, text) ->
                sb.append("<voice name='").append(escape(voice)).append("'>").append(escape(text))
                if (i == parts.lastIndex && trailingSilenceMs > 0) {
                    sb.append("<break time='").append(trailingSilenceMs.coerceAtMost(5000)).append("ms'/>")
                }
                sb.append("</voice>")
            }
            sb.append("</speak>")
            return sb.toString()
        }
    }
}
