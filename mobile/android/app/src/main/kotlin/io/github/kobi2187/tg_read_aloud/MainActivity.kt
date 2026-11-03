package io.github.kobi2187.tg_read_aloud

import android.content.Intent
import android.os.Bundle
import com.ryanheise.audioservice.AudioServiceActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : AudioServiceActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The engine outlives the activity while audio plays (audio_service keeps it), so the
        // channels are attached to it once, with the application context.
        VolumeChannel.attach(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
        ShareChannel.attach(flutterEngine.dartExecutor.binaryMessenger)
        AudioChannel.attach(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ShareChannel.handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareChannel.handle(intent)
    }
}
