# TG Read Aloud

An Android app that reads public Telegram channels aloud, from a date you choose, so you can
listen while commuting. Hebrew and English (and mixed posts) are supported. Everything is
downloaded ahead of time, so it keeps playing through tunnels and dead zones.

**Install:** download [`dist/TG-Read-Aloud-1.0.0.apk`](dist/TG-Read-Aloud-1.0.0.apk), open it on
your phone and allow installing from this source. Android 8.0 or newer.

## What it does

- **Public channels, no login.** Posts come from Telegram's public web preview
  (`https://t.me/s/<channel>`). Add a channel by pasting its link or `@name`, or share a `t.me`
  link from Telegram to the app.
- **From a certain date.** Each channel has a start date (today, yesterday, 3 days, a week, or any
  date). The app finds the first post at that date and reads forward from there. Your place in
  every channel is remembered.
- **Offline first.** Sync downloads the text of every post since the start date and the audio for
  the next posts of each channel (configurable, 100 by default). Audio is stored next to its post,
  so playback needs no network. Sync runs when you open the app, periodically in the background,
  and immediately whenever the connection comes back while you are listening. Each page and each
  audio file is saved as soon as it arrives, so an interrupted sync resumes where it stopped.
- **Voices.**
  - *Device voice* (works out of the box, fully offline): the phone's text-to-speech engine, e.g.
    Google Speech Services. Install the Hebrew voice in the phone's text-to-speech settings if it
    is missing; the app tells you when it is.
  - *Microsoft neural voices* (best quality, free): the same natural voices as Edge's read-aloud
    (Hebrew: Avri, Hila; many English voices), through Microsoft's official Azure Speech service
    with your own free key. The free tier includes 500,000 characters a month. See below.
  - Mixed posts are split by language at sentence level, so English sentences in a Hebrew post get
    the English voice. Links, emoji, hashtag signs and separators are not read out.
- **Listening.** Lock-screen and notification controls, Bluetooth/headset buttons (next/previous
  post), adjustable speed, the channel name is announced when switching, and when a channel ends
  the next channel with unheard posts continues automatically. When everything is heard it says so,
  and starts again by itself as soon as a new post arrives in any channel.
- **Volume buttons switch channels.** While reading, a quick *double press* of volume up goes to the
  next channel and of volume down to the previous one. This works with the screen off. A single
  press still changes the volume. In settings this can instead skip posts, or be turned off.
- **Android Auto** browsing, and voice search ("play Kan News on TG Read Aloud"). Android Auto only
  lists sideloaded apps after enabling "Unknown sources" in its developer settings.
- English and Hebrew interface (follows the phone language).

## Getting a free Azure Speech key (optional, for the best voices)

1. Sign in at <https://portal.azure.com> (a free Microsoft account works; Azure asks for a card to
   verify identity, but the Free tier is never charged).
2. Create a resource of type **Speech** ("Speech service"). Choose **Pricing tier: Free F0** and
   any region, e.g. *West Europe*.
3. Open the resource, then **Keys and Endpoint**. Copy **Key 1** and the **Location/Region**
   (e.g. `westeurope`).
4. In the app open **Settings → Voice**, paste the key and region, tap **Save and test**.

The next sync downloads neural audio for upcoming posts. If the key is wrong or the month's free
quota runs out, the app falls back to the device voice by itself.

## Notes and limits

- Only channels with a public web preview can be read. Private channels and channels that turned
  the preview off show a message instead.
- Posts that are only a photo or video with no text are skipped. A post that is only a link reads
  the link's title.
- **Volume buttons:** Android sends volume keys to the app only while it is playing. According to
  the Android source this works directly on Android 12 and earlier and on Android 16+; on 13–15 it
  depends on a manufacturer setting that Pixels turn off. Where keys are kept from the app, it
  watches the volume itself and the same double press still works, except at maximum or minimum
  volume where the volume cannot move. (Not yet verified on a physical device.)

## Building

Requirements: JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew testDebugUnitTest      # unit + Robolectric tests
./gradlew lintDebug
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the key described in `keystore.properties` (not in git):

```properties
store_file=/path/to/release.jks
store_password=…
key_alias=readaloud
key_password=…
```

or with the `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` environment variables. Without either, it falls back to the debug key.
GitHub Actions (`.github/workflows/android.yml`) builds, tests and uploads the APK on every push;
add the secrets listed in that file to sign CI builds with your key.

Live tests against the real services are opt-in:

```sh
LIVE_TESTS=1 AZURE_SPEECH_KEY=… AZURE_SPEECH_REGION=westeurope ./gradlew testDebugUnitTest
```

They write audio samples to `app/build/live-samples/`.

`./gradlew testDebugUnitTest` also renders the main screens to `app/build/screenshots/`
(English and Hebrew).

## How it works

| Piece | Where |
| --- | --- |
| Web preview parsing (posts, dates, replies, polls, link previews, paging links) | `telegram/TelegramParser.kt` |
| Finding the first post at a date (binary search over post ids) and paging forward | `sync/ChannelHistory.kt` |
| Sync: text, then neural audio round-robin across channels, then cleanup | `sync/SyncEngine.kt`, `sync/SyncWorker.kt` |
| Text cleanup for speech, language runs | `text/SpeechText.kt`, `text/LanguageSegmenter.kt` |
| Azure neural voices (one SSML request per post, a voice per language run) | `tts/AzureTts.kt` |
| Device voice to WAV, with engine fallback | `tts/SystemTts.kt` |
| Audio file per post: neural if available, else device; rendered on demand while playing | `tts/AudioRenderer.kt`, `playback/MediaIds.kt` |
| Playback service, queue, cursors, auto-advance | `playback/PlaybackService.kt`, `playback/QueueController.kt` |
| Volume keys | `playback/VolumeKeyPlayer.kt`, `playback/VolumeGestures.kt` |

The volume trick: while enabled, the media session reports *remote* playback. Android then routes
volume key presses (screen on or off) to the session instead of changing the stream. The app turns
two quick presses into a channel switch and applies single presses to the media volume itself.

## Privacy

No account and no analytics. The app talks only to `t.me` (posts), Telegram's image CDN (channel
pictures) and, if you add a key, your Azure Speech region. The key stays on the phone.
