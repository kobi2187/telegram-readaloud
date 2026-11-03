package io.github.kobi2187.tg_read_aloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * The music stream's volume for the volume-button gestures: while the media session reports remote
 * playback, Android hands volume presses to the app, which applies single presses here itself.
 * Volume changes are also reported, for devices that keep volume keys away from media sessions.
 */
object VolumeChannel {
    private var attachedTo: BinaryMessenger? = null

    private const val STREAM = AudioManager.STREAM_MUSIC
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

    fun attach(context: Context, messenger: BinaryMessenger) {
        if (attachedTo === messenger) return
        attachedTo = messenger
        val audio = context.getSystemService(AudioManager::class.java)

        fun state(): Map<String, Int> {
            val max = audio.getStreamMaxVolume(STREAM)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(STREAM) else 0
            return mapOf("min" to min, "max" to max, "current" to audio.getStreamVolume(STREAM).coerceIn(min, max))
        }

        MethodChannel(messenger, "readaloud/volume").setMethodCallHandler { call, result ->
            when (call.method) {
                "state" -> result.success(state())
                "adjust" -> {
                    val up = (call.arguments as Number).toInt() > 0
                    audio.adjustStreamVolume(STREAM, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    result.success(state())
                }
                "set" -> {
                    val args = call.arguments as Map<*, *>
                    val showUi = args["showUi"] == true
                    audio.setStreamVolume(STREAM, (args["volume"] as Number).toInt(), if (showUi) AudioManager.FLAG_SHOW_UI else 0)
                    result.success(state())
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, "readaloud/volume_changes").setStreamHandler(object : EventChannel.StreamHandler {
            private var receiver: BroadcastReceiver? = null

            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != STREAM) return
                        events.success(listOf(intent.getIntExtra(EXTRA_PREV_VALUE, -1), intent.getIntExtra(EXTRA_VALUE, -1)))
                    }
                }
                receiver = r
                val filter = IntentFilter(VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(r, filter)
                }
            }

            override fun onCancel(arguments: Any?) {
                receiver?.let { runCatching { context.unregisterReceiver(it) } }
                receiver = null
            }
        })
    }
}
