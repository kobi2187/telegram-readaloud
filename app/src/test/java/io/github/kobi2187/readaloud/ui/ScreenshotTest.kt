package io.github.kobi2187.readaloud.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.ReadAloudApp
import io.github.kobi2187.readaloud.TestApp
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.ChannelKind
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.ui.theme.ReadAloudTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens with sample data and saves PNGs to app/build/screenshots, so layout
 * (including Hebrew right-to-left) can be reviewed without a device. Also a smoke test that every
 * screen composes without crashing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: MainViewModel
    private val now = System.currentTimeMillis()
    private val hour = 3600_000L

    @Before
    fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAloudApp>()
        val db = app.graph.db
        db.channels().insert(
            ChannelEntity(username = "kann_news", title = "חדשות כאן", startDate = now - 30 * hour, sortOrder = 0, cursorId = 102),
        )
        db.channels().insert(
            ChannelEntity(username = "durov", title = "Pavel Durov", startDate = now - 72 * hour, sortOrder = 1),
        )
        db.channels().insert(
            ChannelEntity(username = "hidden_chan", title = "hidden_chan", startDate = now, sortOrder = 2, lastError = "no_preview"),
        )
        db.channels().insert(
            ChannelEntity(
                username = "web_1", title = "ynet - חדשות", startDate = now - 24 * hour, sortOrder = 3,
                kind = ChannelKind.FEED, url = "https://www.ynet.co.il/Integration/StoryRss2.xml",
            ),
        )
        db.channels().insert(
            ChannelEntity(username = ChannelKind.SAVED_ID, title = "Saved for listening", startDate = 0, sortOrder = -1, kind = ChannelKind.SAVED),
        )
        db.messages().insertAll(
            listOf(
                MessageEntity("kann_news", 101, now - 20 * hour, "ראש הממשלה נפגש הבוקר עם שר הביטחון.\nפרטים נוספים בהמשך.", "ראש הממשלה נפגש הבוקר עם שר הביטחון.", played = true, audioState = AudioState.NEURAL, audioPath = "/x/101.mp3"),
                MessageEntity("kann_news", 102, now - 10 * hour, "התחזית: גשם מקומי בצפון ובמרכז, ירידה בטמפרטורות. 🌧", "התחזית: גשם מקומי בצפון ובמרכז, ירידה בטמפרטורות.", audioState = AudioState.NEURAL, audioPath = "/x/102.mp3"),
                MessageEntity("kann_news", 103, now - 5 * hour, "Breaking: markets open higher after the announcement.\nhttps://example.com/story", "Breaking: markets open higher after the announcement.", audioState = AudioState.LOCAL, audioPath = "/x/103.wav"),
                MessageEntity("kann_news", 104, now - 2 * hour, "", "", media = "photo"),
                MessageEntity("kann_news", 105, now - 1 * hour, "עדכון: הכביש נפתח מחדש לתנועה.", "עדכון: הכביש נפתח מחדש לתנועה."),
                MessageEntity("web_1", 1, now - 3 * hour, "הפרקליטות מערערת על העונש\n\nגוף הכתבה.", "הפרקליטות מערערת על העונש.", link = "https://www.ynet.co.il/news/article/x"),
                MessageEntity(ChannelKind.SAVED_ID, 1, now - 1 * hour, "A shared story\n\nBody.", "A shared story.", link = "https://example.com/story"),
                MessageEntity("durov", 552, now - 50 * hour, "✨ Designers × AI\n\nThe mockup era is over.", "Designers × AI.\nThe mockup era is over."),
            ),
        )
        vm = MainViewModel(app)
    }

    private fun show(content: @Composable () -> Unit) {
        // Material components keep subtle animations running; step the clock instead of waiting.
        compose.mainClock.autoAdvance = false
        compose.setContent { ReadAloudTheme { content() } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    /** The channel list loads from the database asynchronously; step the clock until [text] shows. */
    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    /** Draws the window in software (works with the paused clock, unlike PixelCopy). */
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        show { HomeScreen(vm, {}, null, {}) }
        awaitText("Saved for listening")
        capture("home")
    }

    @Test
    @Config(qualifiers = "+iw")
    fun homeHebrew() {
        show { HomeScreen(vm, {}, null, {}) }
        compose.onNodeWithText("הוספת ערוץ", useUnmergedTree = true).assertExists()
        capture("home-he")
    }

    @Test
    fun channelPosts() {
        show { ChannelScreen(vm, "kann_news", {}, {}) }
        capture("channel")
    }

    @Test
    fun channelJumpsToTheEnd() {
        runBlocking {
            ApplicationProvider.getApplicationContext<ReadAloudApp>().graph.db.messages().insertAll(
                (200..260).map { MessageEntity("long", it, now - (300 - it) * 60_000L, "הודעה מספר $it", "הודעה מספר $it.") },
            )
            ApplicationProvider.getApplicationContext<ReadAloudApp>().graph.db.channels().insert(
                ChannelEntity(username = "long", title = "Long channel", startDate = 0, sortOrder = 9),
            )
        }
        show { ChannelScreen(vm, "long", {}, {}) }
        compose.onNodeWithContentDescription("Jump to the newest post").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("הודעה מספר 260").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to the first post").assertExists()
    }

    @Test
    fun nowPlaying() {
        show { NowPlayingScreen(vm) {} }
        capture("now-playing")
    }

    @Test
    fun settings() {
        show { SettingsScreen(vm) {} }
        capture("settings")
    }

    /** A very tall screen shows the whole settings page, including the offline voices. */
    @Test
    @Config(qualifiers = "w411dp-h2200dp")
    fun settingsOfflineVoices() {
        show { SettingsScreen(vm) {} }
        compose.onNodeWithText("Blue · male").assertExists()
        capture("settings-voices")
    }

    @Test
    @Config(qualifiers = "+iw-w411dp-h2200dp")
    fun settingsOfflineVoicesHebrew() {
        show { SettingsScreen(vm) {} }
        capture("settings-voices-he")
    }

    @Test
    @Config(qualifiers = "+iw")
    fun settingsHebrew() {
        show { SettingsScreen(vm) {} }
        capture("settings-he")
    }

    // A text field inside a dialog never idles under Robolectric at phone densities (a stock
    // Material TextField does the same), so this smoke test uses Robolectric's default screen.
    @Test
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    fun addChannel() {
        compose.setContent { ReadAloudTheme { AddChannelDialog(vm, "https://t.me/kann_news") {} } }
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Read posts from", substring = true).assertExists()
    }

}
