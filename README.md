# TG Read Aloud

An Android app that reads public Telegram channels and news sites aloud, from a date you choose,
so you can listen while commuting. Links shared to it are saved to listen to later. Hebrew and English (and mixed posts) are supported. Everything is
downloaded ahead of time, so it keeps playing through tunnels and dead zones.

**Install:** download [`dist/TG-Read-Aloud-1.3.3.apk`](dist/TG-Read-Aloud-1.3.3.apk), open it on
your phone and allow installing from this source. Android 8.0 or newer, ARM phones (practically all
of them). Installing over an earlier version keeps your channels and settings.

**iPhone:** a cross-platform Flutter version lives in [`mobile/`](mobile/README.md), with an
unsigned iPhone build from CI that you can sideload with a free Apple ID (SideStore/AltStore).

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
- **Voices.** Each post gets the best voice available, and that choice never needs a credit card:
  1. *Online voice*, downloaded with each post during sync so it still plays offline:
     - **Google Gemini** (recommended): very natural Hebrew and English from one voice, with a free
       key from Google AI Studio (just a Google account). The free tier covers a limited number of
       posts a day; see below.
     - **Microsoft Azure**, with your own key (signing up to Azure asks for a credit card).
  2. *Offline Hebrew voices*, downloaded in Settings: neural voices that run on the phone itself, so
     Hebrew sounds natural even without internet or once the day's Gemini quota is used up. Numbers,
     percentages, prices, dates and times are spelled out in Hebrew. See the list below.
  3. *The phone's own voice* (Google Speech Services or similar): English when offline, and the last
     fallback for everything.

  Mixed posts are split by language at sentence level, so English sentences in a Hebrew post get an
  English voice offline (Gemini reads both languages itself). Links, emoji, hashtag signs and
  separators are not read out.
- **News sites.** Add a website the same way as a channel: type its address (e.g. `example.com`) or
  its RSS link. The app finds the site's RSS/Atom feed, and each new article becomes a post:
  its text is taken from the publisher's own structured data (schema.org `articleBody`) or, failing
  that, from the main block of the page, the way browser reader modes work. Paywalled articles are
  read as their summary and say so. Articles are downloaded during sync with their audio, like posts.
- **Share to listen.** Share any web link to the app (from the browser, Grokipedia, Wikipedia…): it
  lands in "Saved for listening" at the top of the list. Offline, the article is fetched at the next
  sync.
- **Listening.** Lock-screen and notification controls, Bluetooth/headset buttons (next/previous
  post), adjustable speed, the channel name is announced when switching, and when a channel ends
  the next channel with unheard posts continues automatically. When everything is heard it says so,
  and starts again by itself as soon as a new post arrives in any channel.
- **Volume buttons switch channels.** While reading, a quick *double press* of volume up goes to the
  next channel and of volume down to the previous one. This works with the screen off. A single
  press still changes the volume. In settings this can instead skip posts, or be turned off.
- **Android Auto** browsing, and voice search ("play Kan News on TG Read Aloud"). Android Auto only
  lists sideloaded apps after enabling "Unknown sources" in its developer settings.
- **Long channels:** a button in the channel view jumps to the newest post (and back to the first).
- English and Hebrew interface (follows the phone language).

## Getting a free Gemini key (recommended)

1. Open <https://aistudio.google.com/apikey> and sign in with a Google account.
2. Tap **Create API key** (accept the terms if asked; no billing or card is needed) and copy it.
3. In the app open **Settings → Online voice**, choose **Google Gemini**, paste the key and tap
   **Save and test**. You should hear a sample. Pick another voice under **Voice** if you like.

The next sync downloads Gemini audio for upcoming posts, as many as the free quota allows. When the
quota is used up the offline voices take over until it resets (midnight Pacific time, about 10:00
in Israel), and the app resumes by itself. Google does not publish the free limits; you can see
yours in AI Studio. Google may use free-tier requests to improve its products, so pick **None** or
Azure if that matters to you.

## Offline Hebrew voices

**Settings → Offline voices.** Download any of these and pick the one that reads; you can switch at
any time, and upcoming posts are voiced again with the new choice. Downloads resume after
interruptions, wait for Wi-Fi when mobile data is off for sync (with a one-tap "use mobile data
now"), check free space first, are verified against known checksums, and every voice is
test-spoken on the phone before it counts as installed. Files shared by several voices are
downloaded once and kept while any voice needs them. Version 1.1's Noa download is kept.

| Voice | Download | Speed | License |
| --- | --- | --- | --- |
| Noa (female) | 98 MB | fast | CC BY 4.0 |
| Shaul (male) | 84 MB | fast | non-commercial |
| Michael (male) | 84 MB | fast | non-commercial |
| Kokoro · Shaul (male) | 346 MB | most natural, ~8× slower | non-commercial |
| Blue · female / male | 284 MB (shared engine) | natural, 44 kHz, ~4× slower | MIT |

All of them read the pronunciation produced by [renikud](https://huggingface.co/renikud/renikud)
(CC BY 4.0, 20 MB, shared). Sources:
[Noa](https://huggingface.co/thewh1teagle/piper-he-noa),
[Shaul and Michael](https://huggingface.co/Phonikud/phonikud-tts-checkpoints) (Phonikud),
[Kokoro Hebrew](https://huggingface.co/thewh1teagle/kokoro-hebrew-nc),
[BlueTTS 2.5](https://huggingface.co/notmax123/BlueTTS2.5-onnx), all run with ONNX Runtime and
pinned to fixed revisions. The fast voices render several times faster than real time on a phone;
the natural ones need a recent phone and are rendered while the previous post plays.

## Using Azure instead (optional)

1. Sign in at <https://portal.azure.com> (Azure asks for a card to verify identity; the Free tier
   is never charged).
2. Create a resource of type **Speech** ("Speech service"). Choose **Pricing tier: Free F0** and
   any region, e.g. *West Europe*.
3. Open the resource, then **Keys and Endpoint**. Copy **Key 1** and the **Location/Region**
   (e.g. `westeurope`).
4. In the app open **Settings → Online voice**, choose **Microsoft Azure**, paste the key and
   region, tap **Save and test**.

The free tier includes 500,000 characters a month. If the key is wrong or the quota runs out, the
offline voices take over by themselves.

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
LIVE_TESTS=1 GEMINI_API_KEY=… ./gradlew testDebugUnitTest
LIVE_TESTS=1 AZURE_SPEECH_KEY=… AZURE_SPEECH_REGION=westeurope ./gradlew testDebugUnitTest
```

They write audio samples to `app/build/live-samples/`.

The offline voices live in the plain-JVM `:voice` module. Its model tests run every catalog voice
whose files are in a folder laid out with the catalog's file names (`VoiceCatalog.kt`; at least
`renikud.onnx`):
`VOICE_MODELS_DIR=/path/to/models ./gradlew :voice:test` writes samples to
`voice/build/voice-samples/`.

`./gradlew testDebugUnitTest` also renders the main screens to `app/build/screenshots/`
(English and Hebrew).

## How it works

| Piece | Where |
| --- | --- |
| News sites: feed discovery, RSS/Atom parsing, article extraction | `web/WebClient.kt`, `web/FeedParser.kt`, `web/ArticleExtractor.kt` |
| Web preview parsing (posts, dates, replies, polls, link previews, paging links) | `telegram/TelegramParser.kt` |
| Finding the first post at a date (binary search over post ids) and paging forward | `sync/ChannelHistory.kt` |
| Sync: text, then neural audio round-robin across channels, then cleanup | `sync/SyncEngine.kt`, `sync/SyncWorker.kt` |
| Text cleanup for speech, language runs | `text/SpeechText.kt`, `text/LanguageSegmenter.kt` |
| Gemini voices (one request per post; per-model quotas, waiting out rate limits) | `tts/GeminiTts.kt` |
| Azure neural voices (one SSML request per post, a voice per language run) | `tts/AzureTts.kt` |
| Offline Hebrew: number spelling, renikud phonemes, Piper / Kokoro / BlueTTS engines, voice catalog | `voice/` module |
| Offline voices: download (resumable, checksummed, test-spoken), shared files, active voice | `tts/OfflineVoices.kt`, `sync/VoiceDownloadWorker.kt` |
| Device voice to WAV, with engine fallback | `tts/SystemTts.kt` |
| Audio file per post: online voice if available, else offline voices; rendered on demand while playing | `tts/AudioRenderer.kt`, `playback/MediaIds.kt` |
| Playback service, queue, cursors, auto-advance | `playback/PlaybackService.kt`, `playback/QueueController.kt` |
| Volume keys | `playback/VolumeKeyPlayer.kt`, `playback/VolumeGestures.kt` |

The volume trick: while enabled, the media session reports *remote* playback. Android then routes
volume key presses (screen on or off) to the session instead of changing the stream. The app turns
two quick presses into a channel switch and applies single presses to the media volume itself.

## Privacy

No account and no analytics. The app talks only to `t.me` (posts), Telegram's image CDN (channel
pictures), the news sites you add and the links you share, Hugging Face (only to download the offline voice, if you ask for it) and the online
voice you chose: Google's Gemini API or your Azure Speech region, which receive the text of the
posts being voiced. Keys stay on the phone.
