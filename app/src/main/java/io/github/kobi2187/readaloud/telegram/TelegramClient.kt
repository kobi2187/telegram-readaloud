package io.github.kobi2187.readaloud.telegram

import io.github.kobi2187.readaloud.net.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class ChannelUnavailableException(val username: String) :
    IOException("t.me/$username has no public web preview (not a public channel, or preview disabled)")

class RateLimitedException(val retryAfterSeconds: Long?) : IOException("Telegram rate limit")

/**
 * Reads public channels through Telegram's web preview (https://t.me/s/<channel>), which needs no
 * account or API key. Pages hold about 20 posts; `before`/`after` page through history by post id.
 */
class TelegramClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://t.me/".toHttpUrl(),
) {

    suspend fun fetchPage(username: String, before: Int? = null, after: Int? = null): TgPage {
        val url = baseUrl.newBuilder()
            .addPathSegment("s")
            .addPathSegment(username)
            .apply {
                if (before != null) addQueryParameter("before", before.toString())
                if (after != null) addQueryParameter("after", after.toString())
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "he,en;q=0.8")
            .build()
        http.newCall(request).await().use { response ->
            if (response.code == 429) {
                throw RateLimitedException(response.header("Retry-After")?.toLongOrNull())
            }
            // Channels without a web preview redirect from /s/<name> to /<name>.
            val finalPath = response.request.url.encodedPath
            if (!finalPath.startsWith("/s/")) throw ChannelUnavailableException(username)
            if (response.code == 404) throw ChannelUnavailableException(username)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body?.string() ?: throw IOException("Empty response for $url")
            val page = TelegramParser.parse(body, username)
            if (page.title == null && page.messages.isEmpty() && before == null && after == null) {
                throw ChannelUnavailableException(username)
            }
            return page
        }
    }

    suspend fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not write $target")
            }
        }
    }

    companion object {
        const val USER_AGENT = "TGReadAloud/1.0 (Android; +https://github.com/kobi2187/telegram-readaloud)"
    }
}
