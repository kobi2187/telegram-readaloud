package io.github.kobi2187.readaloud.ui

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.kobi2187.readaloud.ui.theme.ReadAloudTheme

sealed interface Screen {
    data object Home : Screen
    data class Channel(val username: String) : Screen
    data object NowPlaying : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var sharedText by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedText = sharedTextOf(intent)
        handleSearch(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ReadAloudTheme {
                AppRoot(vm, sharedText) { sharedText = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.connect()
        vm.syncNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedTextOf(intent)?.let { sharedText = it }
        handleSearch(intent)
    }

    private fun handleSearch(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            vm.playFromSearch(intent.getStringExtra(SearchManager.QUERY))
        }
    }

    private fun sharedTextOf(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
}

@Composable
private fun AppRoot(vm: MainViewModel, sharedText: String?, onSharedConsumed: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    val navigate: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    when (current) {
        Screen.Home -> HomeScreen(vm, navigate, sharedText, onSharedConsumed)
        is Screen.Channel -> ChannelScreen(vm, current.username, back, navigate)
        Screen.NowPlaying -> NowPlayingScreen(vm, back)
        Screen.Settings -> SettingsScreen(vm, back)
    }
}
