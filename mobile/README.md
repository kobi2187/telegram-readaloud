# Read Aloud for iPhone and Android (Flutter)

The cross-platform version of TG Read Aloud: the same app as the native Android one in the
repository root, written in Flutter so it also runs on iPhone. It reads public Telegram channels
and news sites aloud from a date you choose, downloads everything ahead of time, and keeps playing
offline.

## Installing on an iPhone

Apple only lets apps onto an iPhone through the App Store, TestFlight, or by signing them with an
Apple ID. The CI build produces an **unsigned** `.ipa` that you sign yourself with a free Apple ID:

1. Open the latest successful run of the **Flutter (Android + iPhone)** workflow under the
   repository's *Actions* tab and download the `read-aloud-ios-unsigned-ipa` artifact (unzip it to
   get `ReadAloud-unsigned.ipa`).
2. Install it with one of:
   - **SideStore** (no computer needed after setup, refreshes itself over Wi‑Fi): install SideStore
     once following <https://sidestore.io>, then open the `.ipa` from the Files app with SideStore.
   - **AltStore** (<https://altstore.io>) with AltServer on a Mac or PC.
   - **Sideloadly** (<https://sideloadly.io>) from a Mac or PC.
3. On the iPhone, enable *Settings → Privacy & Security → Developer Mode* when asked, and trust
   your Apple ID under *Settings → General → VPN & Device Management*.

With a free Apple ID the app must be re-signed every 7 days (SideStore and AltStore do it
automatically while the app is refreshed), and at most 3 sideloaded apps can be active. A paid
Apple Developer account (99 USD/year) removes both limits; with it the workflow can also upload
builds to TestFlight — see the optional step in `.github/workflows/flutter.yml` and its secrets.

## Installing on Android

Download the `read-aloud-flutter-apk` artifact from the same workflow run (ARM phones, Android 7
or newer). It installs next to the native Android app (`dist/` in the repository root); both offer
the same features.

## What it does

- Telegram channels (public web preview, no login), news sites via RSS/Atom, and links saved for
  listening.
- Start date per channel, remembered position, the jump to the end/start of a channel.
- Offline-first sync: post text and the next posts' audio are downloaded ahead of time, and stored
  compressed (AAC, 7–14 times smaller than WAV).
- Voices:
  - Google Gemini (free AI Studio key) or Microsoft Azure (own key), downloaded with each post.
  - **Offline Hebrew voices** that run on the phone itself (Settings → Offline voices): Noa, Shaul
    and Michael (fast), Kokoro · Shaul and Blue · female/male (most natural, need a recent phone).
    Each is a one-time download of 85–350 MB; the app measures how fast it runs on your phone and
    warns if it is too slow. Posts voiced on the phone start playing after their first sentence.
  - The phone's own voice for English offline and as the last fallback.
- Lock-screen / Control Center / headset controls, speed, channel announcements, continuing to the
  next channel with unheard posts.
- English and Hebrew interface.

## iPhone and Android differences

| | Android | iPhone |
| --- | --- | --- |
| Switch channels with the volume buttons (double press) | Yes, also with the screen off | Not possible: iOS does not give apps the volume buttons. Use headset or lock-screen next/previous. |
| Sync while the app is closed | Every hour (as set in Settings), on Wi-Fi unless mobile data is allowed | When iOS allows background refresh (iOS decides, often while charging); the app always syncs when opened and while it plays |
| Share a link to listen later | Share it to the app from any app | Tap the clipboard button on the home screen after copying a link, or add the "Listen later" Shortcut below |
| Voice downloads | Continue in the background | Keep the app open until done; an interrupted download resumes next time |

### iPhone: a "Listen later" Shortcut in the share sheet

1. Open the **Shortcuts** app and create a new shortcut named *Listen later*.
2. Tap the shortcut's settings (ⓘ) and turn on **Show in Share Sheet**; accept URLs and Safari web
   pages.
3. Add the action **URL Encode** with *Shortcut Input*.
4. Add the action **Text** with `readaloud://share?text=` followed by the *URL Encoded Text*
   variable.
5. Add the action **Open URLs** with that text.

Now *Listen later* appears when you share a page from Safari or any app; it opens Read Aloud and
saves the article (or, for a `t.me` link, offers to add the channel).

## Building

```sh
flutter pub get
flutter test
flutter build apk --release --target-platform android-arm,android-arm64   # Android
flutter build ios --release --no-codesign   # iPhone (on a Mac), then zip Payload/Runner.app as .ipa
```

The UI strings are in `lib/l10n/app_*.arb` (generated into `AppLocalizations` on build).

The offline voices' tests run the real models when `VOICE_MODELS_DIR` points at a folder with the
model files (named as in `lib/voice/voice_catalog.dart`) and ONNX Runtime can be loaded (on Linux:
`libonnxruntime.so.1.22.0` on `LD_LIBRARY_PATH`); the CI workflow sets this up for Noa.
