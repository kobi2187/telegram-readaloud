// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class LEn extends L {
  LEn([String locale = 'en']) : super(locale);

  @override
  String get appName => 'TG Read Aloud';

  @override
  String get actionAdd => 'Add';

  @override
  String get actionBack => 'Back';

  @override
  String get actionBack10 => 'Back 10 seconds';

  @override
  String get actionForward10 => 'Forward 10 seconds';

  @override
  String get actionMore => 'More';

  @override
  String get actionNextChannel => 'Next channel';

  @override
  String get actionPrevChannel => 'Previous channel';

  @override
  String get actionNextChannelShort => 'Next channel';

  @override
  String get actionPrevChannelShort => 'Prev. channel';

  @override
  String get actionNextMessage => 'Next post';

  @override
  String get actionPrevMessage => 'Previous post';

  @override
  String get actionPause => 'Pause';

  @override
  String get actionPlay => 'Play';

  @override
  String get actionSync => 'Sync now';

  @override
  String get emptyChannels =>
      'Add a public Telegram channel to start listening.\n\nPaste its link (t.me/…) or @name, or share a link from Telegram to this app.';

  @override
  String get playerIdle => 'Ready to read';

  @override
  String channelFrom(String p1) {
    return 'from $p1';
  }

  @override
  String get channelCaughtUp => 'All caught up';

  @override
  String channelOfflineReady(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ready offline',
      one: '$count ready offline',
    );
    return '$_temp0';
  }

  @override
  String get channelPaused => 'Paused — skipped when syncing and switching';

  @override
  String get channelNoPosts =>
      'No posts downloaded yet. They appear after the next sync.';

  @override
  String channelRemaining(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count posts to hear',
      one: '$count post to hear',
    );
    return '$_temp0';
  }

  @override
  String get warningNoHebrewVoice =>
      'No Hebrew voice is available, so Hebrew posts would be skipped. Tap to add a free Gemini key or download the offline Hebrew voice.';

  @override
  String get tipBetterVoices =>
      'For natural-sounding Hebrew, add a free Gemini key or download the offline Hebrew voice. Tap to open settings.';

  @override
  String offlineVoiceNotification(String p1) {
    return 'Downloading the $p1 voice';
  }

  @override
  String get errorNoPreview =>
      'This channel has no public web preview, so it can’t be read without logging in.';

  @override
  String get errorRateLimited =>
      'Telegram asked us to slow down; will retry later.';

  @override
  String get errorSyncFailed => 'Last sync failed; will retry when online.';

  @override
  String get menuPosts => 'Show posts';

  @override
  String get menuStartDate => 'Start date';

  @override
  String get menuMarkHeard => 'Mark all as heard';

  @override
  String get menuRestart => 'Restart from start date';

  @override
  String get menuMoveUp => 'Move up';

  @override
  String get menuMoveDown => 'Move down';

  @override
  String get menuPauseChannel => 'Pause channel';

  @override
  String get menuResumeChannel => 'Resume channel';

  @override
  String get menuRemove => 'Remove';

  @override
  String removeTitle(String p1) {
    return 'Remove $p1?';
  }

  @override
  String get removeBody =>
      'Downloaded posts and audio of this channel will be deleted.';

  @override
  String get addChannelTitle => 'Add channel';

  @override
  String get addChannelHint => 'Telegram link or @name, or a website';

  @override
  String get addChecking => 'Checking…';

  @override
  String get addErrorExists => 'This channel is already in the list.';

  @override
  String get addErrorInvalid => 'Not a valid channel link or name.';

  @override
  String get addErrorNoPreview =>
      'This channel has no public web preview (private, or preview disabled).';

  @override
  String startFromLabel(String p1) {
    return 'Read posts from: $p1';
  }

  @override
  String get startToday => 'Today';

  @override
  String get startYesterday => 'Yesterday';

  @override
  String get start3Days => '3 days';

  @override
  String get startWeek => 'A week';

  @override
  String get startPickDate => 'Pick a date…';

  @override
  String get changeStartTitle => 'Start date';

  @override
  String get changeStartBody =>
      'The channel will be read again from this date.';

  @override
  String get statusPlaying => 'Playing';

  @override
  String get statusHeard => 'Heard';

  @override
  String get statusNeuralReady => 'Neural voice downloaded';

  @override
  String get statusDeviceReady => 'Offline audio ready';

  @override
  String get statusNotDownloaded => 'Audio not downloaded yet';

  @override
  String postMediaOnly(String p1) {
    return '[$p1, no text — skipped]';
  }

  @override
  String speedLabel(String p1) {
    return 'Speed $p1';
  }

  @override
  String get noticeCaughtUp => 'You are all caught up.';

  @override
  String get noticeNoOtherChannels => 'No new posts in other channels.';

  @override
  String syncTextProgress(String p1) {
    return 'Syncing $p1…';
  }

  @override
  String syncAudioProgress(int p1, int p2) {
    return 'Downloading voices $p1 of $p2…';
  }

  @override
  String get syncOffline =>
      'Offline — playing what is downloaded. Sync resumes automatically.';

  @override
  String syncLast(String p1) {
    return 'Last sync: $p1';
  }

  @override
  String get syncNotificationChannel => 'Sync';

  @override
  String get syncNotificationTitle => 'Downloading posts';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get settingsVoice => 'Online voice';

  @override
  String get onlineVoiceDesc =>
      'Downloaded with each post during sync, so it also plays offline.';

  @override
  String get ttsGemini => 'Google Gemini (free key, recommended)';

  @override
  String get ttsGeminiDesc =>
      'Very natural Hebrew and English. A free key from Google AI Studio needs only a Google account, no credit card. The free tier covers a limited number of posts a day; the offline voices read the rest.';

  @override
  String get ttsAzure => 'Microsoft Azure (your own key)';

  @override
  String get ttsAzureDesc =>
      'Uses your own Azure Speech key (free tier: 500,000 characters a month; signing up to Azure asks for a credit card).';

  @override
  String get ttsNone => 'None, offline voices only';

  @override
  String get ttsNoneDesc =>
      'Nothing is sent online; posts are read by the offline voices below.';

  @override
  String get keySaveTest => 'Save and test';

  @override
  String get geminiKey => 'Gemini API key';

  @override
  String get geminiGetKey => 'Get a free key';

  @override
  String get geminiNoKey =>
      'No key yet. Open Google AI Studio, sign in with a Google account, tap “Create API key”, and paste the key here.';

  @override
  String get geminiOk =>
      'Key works. Gemini audio will be downloaded on the next sync.';

  @override
  String get geminiBadKey =>
      'Google rejected this key. Copy it again from Google AI Studio.';

  @override
  String get geminiQuota =>
      'Today’s free Gemini requests are used up; the offline voices read until the quota resets (midnight Pacific time, about 10:00 in Israel).';

  @override
  String get geminiThrottled =>
      'Gemini asked us to slow down; downloads resume in a minute.';

  @override
  String get geminiNote =>
      'Each preview uses one of the day’s free requests. Google may use free-tier requests to improve its products.';

  @override
  String get voiceGemini => 'Voice';

  @override
  String get azureKey => 'Azure Speech key';

  @override
  String get azureRegion => 'Region (e.g. westeurope)';

  @override
  String get azureGetKey => 'Get a key';

  @override
  String get azureNoKey =>
      'No key yet. In the Azure portal create a “Speech” resource with the Free F0 tier, then copy Key 1 and its region here.';

  @override
  String get azureOk =>
      'Key works. Azure audio will be downloaded on the next sync.';

  @override
  String get azureBadKey =>
      'The key or region was rejected. Check both in the Azure portal (Keys and Endpoint).';

  @override
  String get azureQuota =>
      'This month’s free quota is used up; the offline voices read until it renews.';

  @override
  String get azureThrottled =>
      'Azure asked us to slow down; downloads resume shortly.';

  @override
  String get voiceHebrew => 'Hebrew voice';

  @override
  String get voiceEnglish => 'English voice';

  @override
  String get voicePreview => 'Preview';

  @override
  String get voicePreviewFailed =>
      'Could not reach the service (no connection, or the key is not set).';

  @override
  String get voiceSampleHe => 'שלום! כך אקריא לך את ההודעות מערוצי הטלגרם שלך.';

  @override
  String get voiceSampleEn =>
      'Hi! This is how I will read your Telegram channels to you.';

  @override
  String get settingsOfflineVoice => 'Offline voices';

  @override
  String offlineVoiceDownload(String p1) {
    return 'Get $p1';
  }

  @override
  String offlineVoiceDownloading(String p1, String p2) {
    return 'Downloading… $p1 of $p2';
  }

  @override
  String get offlineVoiceWaiting => 'Waiting for a connection…';

  @override
  String get offlineVoiceFailed => 'The download failed. Try again.';

  @override
  String get offlineVoiceRemove => 'Remove';

  @override
  String offlineVoiceRemoveBody(String p1) {
    return 'Delete the $p1 voice? Files other installed voices use are kept.';
  }

  @override
  String get offlineVoiceUnsupported =>
      'This voice does not run on this phone; another voice reads instead.';

  @override
  String deviceVoicesStatus(String p1, String p2) {
    return 'Phone voices (English when offline, and the last fallback). Hebrew: $p1  English: $p2';
  }

  @override
  String get deviceTtsSettings => 'Phone text-to-speech settings';

  @override
  String get settingsPlayback => 'Playback';

  @override
  String get announceChannel => 'Say the channel name';

  @override
  String get announceChannelDesc => 'When switching to a channel.';

  @override
  String get autoAdvance => 'Continue with the next channel';

  @override
  String get autoAdvanceDesc => 'When a channel has no more posts.';

  @override
  String get settingsVolumeKeys => 'Volume buttons';

  @override
  String get volumeKeysDesc =>
      'While reading, a quick double press of a volume button triggers an action; a single press still changes the volume. Works with the screen off.';

  @override
  String get volumeKeysChannels => 'Double press: next / previous channel';

  @override
  String get volumeKeysMessages => 'Double press: next / previous post';

  @override
  String get volumeKeysOff => 'Off (normal volume buttons)';

  @override
  String get settingsSync => 'Sync';

  @override
  String get syncInterval => 'Automatic sync';

  @override
  String get syncManual => 'Manual only';

  @override
  String minutes(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Every $count minutes',
      one: 'Every $count minute',
    );
    return '$_temp0';
  }

  @override
  String get allowMetered => 'Use mobile data';

  @override
  String get allowMeteredDesc => 'Turn off to sync only on Wi‑Fi.';

  @override
  String get prefetchCount => 'Posts to pre-download per channel';

  @override
  String get defaultStart => 'Default start for new channels';

  @override
  String daysBack(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count days back',
      one: '$count day back',
    );
    return '$_temp0';
  }

  @override
  String get settingsStorage => 'Storage';

  @override
  String get deletePlayed => 'Delete audio after listening';

  @override
  String get deletePlayedDesc => 'Keeps the text; frees space.';

  @override
  String cacheSize(String p1) {
    return 'Audio stored: $p1';
  }

  @override
  String get clearCache => 'Clear audio';

  @override
  String get clearCacheBody =>
      'All downloaded audio will be deleted. Text is kept and audio is downloaded again on the next sync.';

  @override
  String get settingsAbout => 'About';

  @override
  String get aboutText =>
      'Posts are read from Telegram’s public web preview (t.me/s/…), so no account is needed. Online voices come from Google’s Gemini API or Microsoft’s Azure Speech, with your own key. Offline Hebrew voices use renikud (Hebrew pronunciation, CC BY 4.0) with the Noa (CC BY 4.0), Phonikud Shaul and Michael, Kokoro Hebrew (non-commercial) and BlueTTS (MIT) voices, by thewh1teagle, the Phonikud team, Max Melichov and contributors. The phone’s own voice is always the last fallback.';

  @override
  String get offlineVoicesDesc =>
      'Neural Hebrew voices that run on the phone itself, without internet. The selected one reads Hebrew whenever the online voice is not available. Download any; files they share are downloaded once.';

  @override
  String get voiceMale => 'Male';

  @override
  String get voiceFemale => 'Female';

  @override
  String get voiceFast => 'fast';

  @override
  String get voiceHeavy => 'most natural, slower, needs a recent phone';

  @override
  String get offlineVoiceWaitingWifi => 'Waiting for Wi-Fi…';

  @override
  String offlineVoiceUseMobileData(String p1) {
    return 'Download now with mobile data ($p1)';
  }

  @override
  String offlineVoiceNoSpace(String p1) {
    return 'Not enough free space: $p1 needed.';
  }

  @override
  String get addErrorNoFeed =>
      'No news feed found on this site. Try its RSS link.';

  @override
  String get addErrorSiteRefused => 'This site refused the connection.';

  @override
  String get addErrorOffline =>
      'No connection. Websites can be added when online.';

  @override
  String get errorSiteRefused =>
      'The site refused the connection; it will be tried again.';

  @override
  String get savedTitle => 'Saved for listening';

  @override
  String get savedSubtitle => 'Links you shared to the app';

  @override
  String savedToast(String p1) {
    return 'Saved for listening: $p1';
  }

  @override
  String get savedToastPending =>
      'Saved for listening. The article will be fetched when online.';

  @override
  String get savedAlready => 'This link is already saved.';

  @override
  String get actionOpenLink => 'Open in browser';

  @override
  String offlineVoiceSpeed(String p1) {
    return 'On this phone: renders $p1× faster than it speaks';
  }

  @override
  String get offlineVoiceTooSlow =>
      'Too slow on this phone: playback will keep pausing. Choose a fast voice.';

  @override
  String get actionJumpEnd => 'Jump to the newest post';

  @override
  String get actionJumpStart => 'Jump to the first post';
}
