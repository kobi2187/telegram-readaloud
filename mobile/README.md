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

Download the `read-aloud-flutter-apk` artifact from the same workflow run. The native Android app
(`dist/` in the repository root) has more features today and is still the recommended one on
Android; the two can be installed side by side.

## What works in this version

- Telegram channels (public web preview, no login), news sites via RSS/Atom, saved links.
- Start date per channel, remembered position, the jump to the end/start of a channel.
- Offline-first sync: post text and the next posts' audio are downloaded ahead of time.
- Voices: Google Gemini (free AI Studio key), Microsoft Azure (own key), and the phone's own voice
  as the fallback and for offline listening.
- Lock-screen / Control Center / headset controls, speed, channel announcements, continuing to the
  next channel with unheard posts.
- English and Hebrew interface.

## Not yet (coming next)

- The downloadable offline Hebrew neural voices (Noa, Shaul, Michael, Kokoro, Blue).
- Volume-button channel switching. On iPhone, apps cannot intercept the volume buttons; headset
  and lock-screen next/previous are used instead.
- Background sync while the app is closed. iOS decides itself when (and whether) background
  refreshes run; sync happens when the app is opened and periodically while it plays.
- Sharing links into the app from other apps (needs a share extension on iPhone).

## Building

```sh
flutter pub get
flutter test
flutter build apk --release                 # Android
flutter build ios --release --no-codesign   # iPhone (on a Mac), then zip Payload/Runner.app as .ipa
```

The UI strings are in `lib/l10n/app_*.arb` (generated into `AppLocalizations` on build).
