package io.github.kobi2187.tg_read_aloud

import android.content.Intent
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/** Text shared to the app (a link to listen to, or a Telegram channel to add), handed to Dart. */
object ShareChannel {
    private var attachedTo: BinaryMessenger? = null
    private var pending: String? = null
    private var sink: EventChannel.EventSink? = null

    fun attach(messenger: BinaryMessenger) {
        if (attachedTo === messenger) return
        attachedTo = messenger
        MethodChannel(messenger, "readaloud/share").setMethodCallHandler { call, result ->
            if (call.method == "initial") {
                result.success(pending)
                pending = null
            } else {
                result.notImplemented()
            }
        }
        EventChannel(messenger, "readaloud/shares").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                sink = events
                pending?.let { events.success(it) }
                pending = null
            }

            override fun onCancel(arguments: Any?) {
                sink = null
            }
        })
    }

    fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        sink?.success(text) ?: run { pending = text }
    }
}
