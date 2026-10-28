package io.github.kobi2187.readaloud.sync

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant

/** Serves t.me/s pages for in-memory channels, with t.me's paging links and window behaviour. */
class FakeTelegramServer : Dispatcher() {
    class Channel(val title: String) {
        val posts = sortedMapOf<Int, Long>()
        val newest: Int get() = posts.lastKey()
    }

    val channels = mutableMapOf<String, Channel>()
    val requests = mutableListOf<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        requests += url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val segments = url.pathSegments
        if (segments.size != 2 || segments[0] != "s") return MockResponse().setResponseCode(404)
        val name = segments[1]
        val ch = channels[name]
            ?: return MockResponse().setResponseCode(302).setHeader("Location", "/$name") // no preview
        val ids = ch.posts.keys.toList()
        val before = url.queryParameter("before")?.toInt()
        val after = url.queryParameter("after")?.toInt()
        val selected = when {
            before != null -> ids.filter { it < before }.takeLast(20)
            after != null -> ids.filter { it > after }.take(20)
            else -> ids.takeLast(20)
        }
        val prev = selected.minOrNull()?.takeIf { it > ids.first() }
        val next = selected.maxOrNull()?.takeIf { it < ids.last() }
        return MockResponse().setBody(html(name, ch, selected, prev, next))
    }

    private fun html(name: String, ch: Channel, ids: List<Int>, prev: Int?, next: Int?) = buildString {
        append("<html><head>")
        prev?.let { append("<link rel=\"prev\" href=\"/s/$name?before=$it\">") }
        next?.let { append("<link rel=\"next\" href=\"/s/$name?after=$it\">") }
        append("</head><body><div class=\"tgme_channel_info_header_title\"><span>${ch.title}</span></div>")
        for (id in ids) {
            val date = Instant.ofEpochMilli(ch.posts.getValue(id))
            append("<div class=\"tgme_widget_message_wrap\"><div class=\"tgme_widget_message\" data-post=\"$name/$id\">")
            append("<div class=\"tgme_widget_message_bubble\"><div class=\"tgme_widget_message_text js-message_text\">Post number $id</div>")
            append("<div class=\"tgme_widget_message_footer\"><span class=\"tgme_widget_message_meta\">")
            append("<a class=\"tgme_widget_message_date\"><time datetime=\"$date\" class=\"time\"></time></a></span></div>")
            append("</div></div></div>")
        }
        append("</body></html>")
    }
}
