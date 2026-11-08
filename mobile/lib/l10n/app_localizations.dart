import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_he.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of L
/// returned by `L.of(context)`.
///
/// Applications need to include `L.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: L.localizationsDelegates,
///   supportedLocales: L.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the L.supportedLocales
/// property.
abstract class L {
  L(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static L of(BuildContext context) {
    return Localizations.of<L>(context, L)!;
  }

  static const LocalizationsDelegate<L> delegate = _LDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('he'),
  ];

  /// No description provided for @appName.
  ///
  /// In en, this message translates to:
  /// **'TG Read Aloud'**
  String get appName;

  /// No description provided for @actionAdd.
  ///
  /// In en, this message translates to:
  /// **'Add'**
  String get actionAdd;

  /// No description provided for @actionBack.
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get actionBack;

  /// No description provided for @actionBack10.
  ///
  /// In en, this message translates to:
  /// **'Back 10 seconds'**
  String get actionBack10;

  /// No description provided for @actionForward10.
  ///
  /// In en, this message translates to:
  /// **'Forward 10 seconds'**
  String get actionForward10;

  /// No description provided for @actionMore.
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get actionMore;

  /// No description provided for @actionNextChannel.
  ///
  /// In en, this message translates to:
  /// **'Next channel'**
  String get actionNextChannel;

  /// No description provided for @actionPrevChannel.
  ///
  /// In en, this message translates to:
  /// **'Previous channel'**
  String get actionPrevChannel;

  /// No description provided for @actionNextChannelShort.
  ///
  /// In en, this message translates to:
  /// **'Next channel'**
  String get actionNextChannelShort;

  /// No description provided for @actionPrevChannelShort.
  ///
  /// In en, this message translates to:
  /// **'Prev. channel'**
  String get actionPrevChannelShort;

  /// No description provided for @actionNextMessage.
  ///
  /// In en, this message translates to:
  /// **'Next post'**
  String get actionNextMessage;

  /// No description provided for @actionPrevMessage.
  ///
  /// In en, this message translates to:
  /// **'Previous post'**
  String get actionPrevMessage;

  /// No description provided for @actionPause.
  ///
  /// In en, this message translates to:
  /// **'Pause'**
  String get actionPause;

  /// No description provided for @actionPlay.
  ///
  /// In en, this message translates to:
  /// **'Play'**
  String get actionPlay;

  /// No description provided for @actionSync.
  ///
  /// In en, this message translates to:
  /// **'Sync now'**
  String get actionSync;

  /// No description provided for @emptyChannels.
  ///
  /// In en, this message translates to:
  /// **'Add a public Telegram channel to start listening.\n\nPaste its link (t.me/…) or @name, or share a link from Telegram to this app.'**
  String get emptyChannels;

  /// No description provided for @playerIdle.
  ///
  /// In en, this message translates to:
  /// **'Ready to read'**
  String get playerIdle;

  /// No description provided for @channelFrom.
  ///
  /// In en, this message translates to:
  /// **'from {p1}'**
  String channelFrom(String p1);

  /// No description provided for @channelCaughtUp.
  ///
  /// In en, this message translates to:
  /// **'All caught up'**
  String get channelCaughtUp;

  /// No description provided for @channelOfflineReady.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{{count} ready offline} other{{count} ready offline}}'**
  String channelOfflineReady(int count);

  /// No description provided for @channelPaused.
  ///
  /// In en, this message translates to:
  /// **'Paused — skipped when syncing and switching'**
  String get channelPaused;

  /// No description provided for @channelNoPosts.
  ///
  /// In en, this message translates to:
  /// **'No posts downloaded yet. They appear after the next sync.'**
  String get channelNoPosts;

  /// No description provided for @channelRemaining.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{{count} post to hear} other{{count} posts to hear}}'**
  String channelRemaining(int count);

  /// No description provided for @warningNoHebrewVoice.
  ///
  /// In en, this message translates to:
  /// **'No Hebrew voice is available, so Hebrew posts would be skipped. Tap to add a free Gemini key or download the offline Hebrew voice.'**
  String get warningNoHebrewVoice;

  /// No description provided for @tipBetterVoices.
  ///
  /// In en, this message translates to:
  /// **'For natural-sounding Hebrew, add a free Gemini key or download the offline Hebrew voice. Tap to open settings.'**
  String get tipBetterVoices;

  /// No description provided for @offlineVoiceNotification.
  ///
  /// In en, this message translates to:
  /// **'Downloading the {p1} voice'**
  String offlineVoiceNotification(String p1);

  /// No description provided for @errorNoPreview.
  ///
  /// In en, this message translates to:
  /// **'This channel has no public web preview, so it can’t be read without logging in.'**
  String get errorNoPreview;

  /// No description provided for @errorRateLimited.
  ///
  /// In en, this message translates to:
  /// **'Telegram asked us to slow down; will retry later.'**
  String get errorRateLimited;

  /// No description provided for @errorSyncFailed.
  ///
  /// In en, this message translates to:
  /// **'Last sync failed; will retry when online.'**
  String get errorSyncFailed;

  /// No description provided for @menuPosts.
  ///
  /// In en, this message translates to:
  /// **'Show posts'**
  String get menuPosts;

  /// No description provided for @menuStartDate.
  ///
  /// In en, this message translates to:
  /// **'Start date'**
  String get menuStartDate;

  /// No description provided for @menuMarkHeard.
  ///
  /// In en, this message translates to:
  /// **'Mark all as heard'**
  String get menuMarkHeard;

  /// No description provided for @menuRestart.
  ///
  /// In en, this message translates to:
  /// **'Restart from start date'**
  String get menuRestart;

  /// No description provided for @menuMoveUp.
  ///
  /// In en, this message translates to:
  /// **'Move up'**
  String get menuMoveUp;

  /// No description provided for @menuMoveDown.
  ///
  /// In en, this message translates to:
  /// **'Move down'**
  String get menuMoveDown;

  /// No description provided for @menuPauseChannel.
  ///
  /// In en, this message translates to:
  /// **'Pause channel'**
  String get menuPauseChannel;

  /// No description provided for @menuResumeChannel.
  ///
  /// In en, this message translates to:
  /// **'Resume channel'**
  String get menuResumeChannel;

  /// No description provided for @menuRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get menuRemove;

  /// No description provided for @removeTitle.
  ///
  /// In en, this message translates to:
  /// **'Remove {p1}?'**
  String removeTitle(String p1);

  /// No description provided for @removeBody.
  ///
  /// In en, this message translates to:
  /// **'Downloaded posts and audio of this channel will be deleted.'**
  String get removeBody;

  /// No description provided for @addChannelTitle.
  ///
  /// In en, this message translates to:
  /// **'Add channel'**
  String get addChannelTitle;

  /// No description provided for @addChannelHint.
  ///
  /// In en, this message translates to:
  /// **'Telegram link or @name, or a website'**
  String get addChannelHint;

  /// No description provided for @addChecking.
  ///
  /// In en, this message translates to:
  /// **'Checking…'**
  String get addChecking;

  /// No description provided for @addErrorExists.
  ///
  /// In en, this message translates to:
  /// **'This channel is already in the list.'**
  String get addErrorExists;

  /// No description provided for @addErrorInvalid.
  ///
  /// In en, this message translates to:
  /// **'Not a valid channel link or name.'**
  String get addErrorInvalid;

  /// No description provided for @addErrorNoPreview.
  ///
  /// In en, this message translates to:
  /// **'This channel has no public web preview (private, or preview disabled).'**
  String get addErrorNoPreview;

  /// No description provided for @startFromLabel.
  ///
  /// In en, this message translates to:
  /// **'Read posts from: {p1}'**
  String startFromLabel(String p1);

  /// No description provided for @startToday.
  ///
  /// In en, this message translates to:
  /// **'Today'**
  String get startToday;

  /// No description provided for @startYesterday.
  ///
  /// In en, this message translates to:
  /// **'Yesterday'**
  String get startYesterday;

  /// No description provided for @start3Days.
  ///
  /// In en, this message translates to:
  /// **'3 days'**
  String get start3Days;

  /// No description provided for @startWeek.
  ///
  /// In en, this message translates to:
  /// **'A week'**
  String get startWeek;

  /// No description provided for @startPickDate.
  ///
  /// In en, this message translates to:
  /// **'Pick a date…'**
  String get startPickDate;

  /// No description provided for @changeStartTitle.
  ///
  /// In en, this message translates to:
  /// **'Start date'**
  String get changeStartTitle;

  /// No description provided for @changeStartBody.
  ///
  /// In en, this message translates to:
  /// **'The channel will be read again from this date.'**
  String get changeStartBody;

  /// No description provided for @statusPlaying.
  ///
  /// In en, this message translates to:
  /// **'Playing'**
  String get statusPlaying;

  /// No description provided for @statusHeard.
  ///
  /// In en, this message translates to:
  /// **'Heard'**
  String get statusHeard;

  /// No description provided for @statusNeuralReady.
  ///
  /// In en, this message translates to:
  /// **'Neural voice downloaded'**
  String get statusNeuralReady;

  /// No description provided for @statusDeviceReady.
  ///
  /// In en, this message translates to:
  /// **'Offline audio ready'**
  String get statusDeviceReady;

  /// No description provided for @statusNotDownloaded.
  ///
  /// In en, this message translates to:
  /// **'Audio not downloaded yet'**
  String get statusNotDownloaded;

  /// No description provided for @postMediaOnly.
  ///
  /// In en, this message translates to:
  /// **'[{p1}, no text — skipped]'**
  String postMediaOnly(String p1);

  /// No description provided for @speedLabel.
  ///
  /// In en, this message translates to:
  /// **'Speed {p1}'**
  String speedLabel(String p1);

  /// No description provided for @noticeCaughtUp.
  ///
  /// In en, this message translates to:
  /// **'You are all caught up.'**
  String get noticeCaughtUp;

  /// No description provided for @noticeNoOtherChannels.
  ///
  /// In en, this message translates to:
  /// **'No new posts in other channels.'**
  String get noticeNoOtherChannels;

  /// No description provided for @syncTextProgress.
  ///
  /// In en, this message translates to:
  /// **'Syncing {p1}…'**
  String syncTextProgress(String p1);

  /// No description provided for @syncAudioProgress.
  ///
  /// In en, this message translates to:
  /// **'Downloading voices {p1} of {p2}…'**
  String syncAudioProgress(int p1, int p2);

  /// No description provided for @syncOffline.
  ///
  /// In en, this message translates to:
  /// **'Offline — playing what is downloaded. Sync resumes automatically.'**
  String get syncOffline;

  /// No description provided for @syncLast.
  ///
  /// In en, this message translates to:
  /// **'Last sync: {p1}'**
  String syncLast(String p1);

  /// No description provided for @syncNotificationChannel.
  ///
  /// In en, this message translates to:
  /// **'Sync'**
  String get syncNotificationChannel;

  /// No description provided for @syncNotificationTitle.
  ///
  /// In en, this message translates to:
  /// **'Downloading posts'**
  String get syncNotificationTitle;

  /// No description provided for @settingsTitle.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settingsTitle;

  /// No description provided for @settingsVoice.
  ///
  /// In en, this message translates to:
  /// **'Online voice'**
  String get settingsVoice;

  /// No description provided for @onlineVoiceDesc.
  ///
  /// In en, this message translates to:
  /// **'Downloaded with each post during sync, so it also plays offline.'**
  String get onlineVoiceDesc;

  /// No description provided for @ttsGemini.
  ///
  /// In en, this message translates to:
  /// **'Google Gemini (free key, recommended)'**
  String get ttsGemini;

  /// No description provided for @ttsGeminiDesc.
  ///
  /// In en, this message translates to:
  /// **'Very natural Hebrew and English. A free key from Google AI Studio needs only a Google account, no credit card. The free tier covers a limited number of posts a day; the offline voices read the rest.'**
  String get ttsGeminiDesc;

  /// No description provided for @ttsAzure.
  ///
  /// In en, this message translates to:
  /// **'Microsoft Azure (your own key)'**
  String get ttsAzure;

  /// No description provided for @ttsAzureDesc.
  ///
  /// In en, this message translates to:
  /// **'Uses your own Azure Speech key (free tier: 500,000 characters a month; signing up to Azure asks for a credit card).'**
  String get ttsAzureDesc;

  /// No description provided for @ttsNone.
  ///
  /// In en, this message translates to:
  /// **'None, offline voices only'**
  String get ttsNone;

  /// No description provided for @ttsNoneDesc.
  ///
  /// In en, this message translates to:
  /// **'Nothing is sent online; posts are read by the offline voices below.'**
  String get ttsNoneDesc;

  /// No description provided for @keySaveTest.
  ///
  /// In en, this message translates to:
  /// **'Save and test'**
  String get keySaveTest;

  /// No description provided for @geminiKey.
  ///
  /// In en, this message translates to:
  /// **'Gemini API key'**
  String get geminiKey;

  /// No description provided for @geminiGetKey.
  ///
  /// In en, this message translates to:
  /// **'Get a free key'**
  String get geminiGetKey;

  /// No description provided for @geminiNoKey.
  ///
  /// In en, this message translates to:
  /// **'No key yet. Open Google AI Studio, sign in with a Google account, tap “Create API key”, and paste the key here.'**
  String get geminiNoKey;

  /// No description provided for @geminiOk.
  ///
  /// In en, this message translates to:
  /// **'Key works. Gemini audio will be downloaded on the next sync.'**
  String get geminiOk;

  /// No description provided for @geminiBadKey.
  ///
  /// In en, this message translates to:
  /// **'Google rejected this key. Copy it again from Google AI Studio.'**
  String get geminiBadKey;

  /// No description provided for @geminiQuota.
  ///
  /// In en, this message translates to:
  /// **'Today’s free Gemini requests are used up; the offline voices read until the quota resets (midnight Pacific time, about 10:00 in Israel).'**
  String get geminiQuota;

  /// No description provided for @geminiThrottled.
  ///
  /// In en, this message translates to:
  /// **'Gemini asked us to slow down; downloads resume in a minute.'**
  String get geminiThrottled;

  /// No description provided for @geminiNote.
  ///
  /// In en, this message translates to:
  /// **'Each preview uses one of the day’s free requests. Google may use free-tier requests to improve its products.'**
  String get geminiNote;

  /// No description provided for @voiceGemini.
  ///
  /// In en, this message translates to:
  /// **'Voice'**
  String get voiceGemini;

  /// No description provided for @azureKey.
  ///
  /// In en, this message translates to:
  /// **'Azure Speech key'**
  String get azureKey;

  /// No description provided for @azureRegion.
  ///
  /// In en, this message translates to:
  /// **'Region (e.g. westeurope)'**
  String get azureRegion;

  /// No description provided for @azureGetKey.
  ///
  /// In en, this message translates to:
  /// **'Get a key'**
  String get azureGetKey;

  /// No description provided for @azureNoKey.
  ///
  /// In en, this message translates to:
  /// **'No key yet. In the Azure portal create a “Speech” resource with the Free F0 tier, then copy Key 1 and its region here.'**
  String get azureNoKey;

  /// No description provided for @azureOk.
  ///
  /// In en, this message translates to:
  /// **'Key works. Azure audio will be downloaded on the next sync.'**
  String get azureOk;

  /// No description provided for @azureBadKey.
  ///
  /// In en, this message translates to:
  /// **'The key or region was rejected. Check both in the Azure portal (Keys and Endpoint).'**
  String get azureBadKey;

  /// No description provided for @azureQuota.
  ///
  /// In en, this message translates to:
  /// **'This month’s free quota is used up; the offline voices read until it renews.'**
  String get azureQuota;

  /// No description provided for @azureThrottled.
  ///
  /// In en, this message translates to:
  /// **'Azure asked us to slow down; downloads resume shortly.'**
  String get azureThrottled;

  /// No description provided for @voiceHebrew.
  ///
  /// In en, this message translates to:
  /// **'Hebrew voice'**
  String get voiceHebrew;

  /// No description provided for @voiceEnglish.
  ///
  /// In en, this message translates to:
  /// **'English voice'**
  String get voiceEnglish;

  /// No description provided for @voicePreview.
  ///
  /// In en, this message translates to:
  /// **'Preview'**
  String get voicePreview;

  /// No description provided for @voicePreviewFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not reach the service (no connection, or the key is not set).'**
  String get voicePreviewFailed;

  /// No description provided for @voiceSampleHe.
  ///
  /// In en, this message translates to:
  /// **'שלום! כך אקריא לך את ההודעות מערוצי הטלגרם שלך.'**
  String get voiceSampleHe;

  /// No description provided for @voiceSampleEn.
  ///
  /// In en, this message translates to:
  /// **'Hi! This is how I will read your Telegram channels to you.'**
  String get voiceSampleEn;

  /// No description provided for @settingsOfflineVoice.
  ///
  /// In en, this message translates to:
  /// **'Offline voices'**
  String get settingsOfflineVoice;

  /// No description provided for @offlineVoiceDownload.
  ///
  /// In en, this message translates to:
  /// **'Get {p1}'**
  String offlineVoiceDownload(String p1);

  /// No description provided for @offlineVoiceDownloading.
  ///
  /// In en, this message translates to:
  /// **'Downloading… {p1} of {p2}'**
  String offlineVoiceDownloading(String p1, String p2);

  /// No description provided for @offlineVoiceWaiting.
  ///
  /// In en, this message translates to:
  /// **'Waiting for a connection…'**
  String get offlineVoiceWaiting;

  /// No description provided for @offlineVoiceFailed.
  ///
  /// In en, this message translates to:
  /// **'The download failed. Try again.'**
  String get offlineVoiceFailed;

  /// No description provided for @offlineVoiceRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get offlineVoiceRemove;

  /// No description provided for @offlineVoiceRemoveBody.
  ///
  /// In en, this message translates to:
  /// **'Delete the {p1} voice? Files other installed voices use are kept.'**
  String offlineVoiceRemoveBody(String p1);

  /// No description provided for @offlineVoiceUnsupported.
  ///
  /// In en, this message translates to:
  /// **'This voice does not run on this phone; another voice reads instead.'**
  String get offlineVoiceUnsupported;

  /// No description provided for @deviceVoicesStatus.
  ///
  /// In en, this message translates to:
  /// **'Phone voices (English when offline, and the last fallback). Hebrew: {p1}  English: {p2}'**
  String deviceVoicesStatus(String p1, String p2);

  /// No description provided for @deviceTtsSettings.
  ///
  /// In en, this message translates to:
  /// **'Phone text-to-speech settings'**
  String get deviceTtsSettings;

  /// No description provided for @settingsPlayback.
  ///
  /// In en, this message translates to:
  /// **'Playback'**
  String get settingsPlayback;

  /// No description provided for @announceChannel.
  ///
  /// In en, this message translates to:
  /// **'Say the channel name'**
  String get announceChannel;

  /// No description provided for @announceChannelDesc.
  ///
  /// In en, this message translates to:
  /// **'When switching to a channel.'**
  String get announceChannelDesc;

  /// No description provided for @autoAdvance.
  ///
  /// In en, this message translates to:
  /// **'Continue with the next channel'**
  String get autoAdvance;

  /// No description provided for @autoAdvanceDesc.
  ///
  /// In en, this message translates to:
  /// **'When a channel has no more posts.'**
  String get autoAdvanceDesc;

  /// No description provided for @settingsVolumeKeys.
  ///
  /// In en, this message translates to:
  /// **'Volume buttons'**
  String get settingsVolumeKeys;

  /// No description provided for @volumeKeysDesc.
  ///
  /// In en, this message translates to:
  /// **'While reading, a quick double press of a volume button triggers an action; a single press still changes the volume. Works with the screen off.'**
  String get volumeKeysDesc;

  /// No description provided for @volumeKeysChannels.
  ///
  /// In en, this message translates to:
  /// **'Double press: next / previous channel'**
  String get volumeKeysChannels;

  /// No description provided for @volumeKeysMessages.
  ///
  /// In en, this message translates to:
  /// **'Double press: next / previous post'**
  String get volumeKeysMessages;

  /// No description provided for @volumeKeysOff.
  ///
  /// In en, this message translates to:
  /// **'Off (normal volume buttons)'**
  String get volumeKeysOff;

  /// No description provided for @settingsSync.
  ///
  /// In en, this message translates to:
  /// **'Sync'**
  String get settingsSync;

  /// No description provided for @syncInterval.
  ///
  /// In en, this message translates to:
  /// **'Automatic sync'**
  String get syncInterval;

  /// No description provided for @syncManual.
  ///
  /// In en, this message translates to:
  /// **'Manual only'**
  String get syncManual;

  /// No description provided for @minutes.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Every {count} minute} other{Every {count} minutes}}'**
  String minutes(int count);

  /// No description provided for @allowMetered.
  ///
  /// In en, this message translates to:
  /// **'Use mobile data'**
  String get allowMetered;

  /// No description provided for @allowMeteredDesc.
  ///
  /// In en, this message translates to:
  /// **'Turn off to sync only on Wi‑Fi.'**
  String get allowMeteredDesc;

  /// No description provided for @prefetchCount.
  ///
  /// In en, this message translates to:
  /// **'Posts to pre-download per channel'**
  String get prefetchCount;

  /// No description provided for @defaultStart.
  ///
  /// In en, this message translates to:
  /// **'Default start for new channels'**
  String get defaultStart;

  /// No description provided for @daysBack.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{{count} day back} other{{count} days back}}'**
  String daysBack(int count);

  /// No description provided for @settingsStorage.
  ///
  /// In en, this message translates to:
  /// **'Storage'**
  String get settingsStorage;

  /// No description provided for @deletePlayed.
  ///
  /// In en, this message translates to:
  /// **'Delete audio after listening'**
  String get deletePlayed;

  /// No description provided for @deletePlayedDesc.
  ///
  /// In en, this message translates to:
  /// **'Keeps the text; frees space.'**
  String get deletePlayedDesc;

  /// No description provided for @cacheSize.
  ///
  /// In en, this message translates to:
  /// **'Audio stored: {p1}'**
  String cacheSize(String p1);

  /// No description provided for @clearCache.
  ///
  /// In en, this message translates to:
  /// **'Clear audio'**
  String get clearCache;

  /// No description provided for @clearCacheBody.
  ///
  /// In en, this message translates to:
  /// **'All downloaded audio will be deleted. Text is kept and audio is downloaded again on the next sync.'**
  String get clearCacheBody;

  /// No description provided for @settingsAbout.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get settingsAbout;

  /// No description provided for @aboutText.
  ///
  /// In en, this message translates to:
  /// **'Posts are read from Telegram’s public web preview (t.me/s/…), so no account is needed. Online voices come from Google’s Gemini API or Microsoft’s Azure Speech, with your own key. Offline Hebrew voices use renikud (Hebrew pronunciation, CC BY 4.0) with the Noa (CC BY 4.0), Phonikud Shaul and Michael, Kokoro Hebrew (non-commercial) and BlueTTS (MIT) voices, by thewh1teagle, the Phonikud team, Max Melichov and contributors. The phone’s own voice is always the last fallback.'**
  String get aboutText;

  /// No description provided for @offlineVoicesDesc.
  ///
  /// In en, this message translates to:
  /// **'Neural Hebrew voices that run on the phone itself, without internet. The selected one reads Hebrew whenever the online voice is not available. Download any; files they share are downloaded once.'**
  String get offlineVoicesDesc;

  /// No description provided for @voiceMale.
  ///
  /// In en, this message translates to:
  /// **'Male'**
  String get voiceMale;

  /// No description provided for @voiceFemale.
  ///
  /// In en, this message translates to:
  /// **'Female'**
  String get voiceFemale;

  /// No description provided for @voiceFast.
  ///
  /// In en, this message translates to:
  /// **'fast'**
  String get voiceFast;

  /// No description provided for @voiceHeavy.
  ///
  /// In en, this message translates to:
  /// **'most natural, slower, needs a recent phone'**
  String get voiceHeavy;

  /// No description provided for @offlineVoiceWaitingWifi.
  ///
  /// In en, this message translates to:
  /// **'Waiting for Wi-Fi…'**
  String get offlineVoiceWaitingWifi;

  /// No description provided for @offlineVoiceUseMobileData.
  ///
  /// In en, this message translates to:
  /// **'Download now with mobile data ({p1})'**
  String offlineVoiceUseMobileData(String p1);

  /// No description provided for @offlineVoiceNoSpace.
  ///
  /// In en, this message translates to:
  /// **'Not enough free space: {p1} needed.'**
  String offlineVoiceNoSpace(String p1);

  /// No description provided for @addErrorNoFeed.
  ///
  /// In en, this message translates to:
  /// **'No news feed found on this site. Try its RSS link.'**
  String get addErrorNoFeed;

  /// No description provided for @addErrorSiteRefused.
  ///
  /// In en, this message translates to:
  /// **'This site refused the connection.'**
  String get addErrorSiteRefused;

  /// No description provided for @addErrorOffline.
  ///
  /// In en, this message translates to:
  /// **'No connection. Websites can be added when online.'**
  String get addErrorOffline;

  /// No description provided for @errorSiteRefused.
  ///
  /// In en, this message translates to:
  /// **'The site refused the connection; it will be tried again.'**
  String get errorSiteRefused;

  /// No description provided for @savedTitle.
  ///
  /// In en, this message translates to:
  /// **'Saved for listening'**
  String get savedTitle;

  /// No description provided for @savedSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Links you shared to the app'**
  String get savedSubtitle;

  /// No description provided for @savedToast.
  ///
  /// In en, this message translates to:
  /// **'Saved for listening: {p1}'**
  String savedToast(String p1);

  /// No description provided for @savedToastPending.
  ///
  /// In en, this message translates to:
  /// **'Saved for listening. The article will be fetched when online.'**
  String get savedToastPending;

  /// No description provided for @savedAlready.
  ///
  /// In en, this message translates to:
  /// **'This link is already saved.'**
  String get savedAlready;

  /// No description provided for @actionOpenLink.
  ///
  /// In en, this message translates to:
  /// **'Open in browser'**
  String get actionOpenLink;

  /// No description provided for @offlineVoiceSpeed.
  ///
  /// In en, this message translates to:
  /// **'On this phone: renders {p1}× faster than it speaks'**
  String offlineVoiceSpeed(String p1);

  /// No description provided for @offlineVoiceTooSlow.
  ///
  /// In en, this message translates to:
  /// **'Too slow on this phone: playback will keep pausing. Choose a fast voice.'**
  String get offlineVoiceTooSlow;

  /// No description provided for @actionJumpEnd.
  ///
  /// In en, this message translates to:
  /// **'Jump to the newest post'**
  String get actionJumpEnd;

  /// No description provided for @actionJumpStart.
  ///
  /// In en, this message translates to:
  /// **'Jump to the first post'**
  String get actionJumpStart;

  /// No description provided for @actionPasteLink.
  ///
  /// In en, this message translates to:
  /// **'Listen to a copied link'**
  String get actionPasteLink;

  /// No description provided for @pasteNoLink.
  ///
  /// In en, this message translates to:
  /// **'Copy a link first, then tap here to listen to it.'**
  String get pasteNoLink;

  /// No description provided for @voiceRecommended.
  ///
  /// In en, this message translates to:
  /// **'Recommended'**
  String get voiceRecommended;

  /// No description provided for @actionTranslate.
  ///
  /// In en, this message translates to:
  /// **'Translate…'**
  String get actionTranslate;

  /// No description provided for @translateTitle.
  ///
  /// In en, this message translates to:
  /// **'Read this channel in'**
  String get translateTitle;

  /// No description provided for @translateOff.
  ///
  /// In en, this message translates to:
  /// **'As written (no translation)'**
  String get translateOff;

  /// No description provided for @translateNote.
  ///
  /// In en, this message translates to:
  /// **'Posts are translated with Google Translate and read in that language. They are translated during sync, so they also play offline.'**
  String get translateNote;

  /// No description provided for @translatedTo.
  ///
  /// In en, this message translates to:
  /// **'Translated to {p1}'**
  String translatedTo(String p1);

  /// No description provided for @showOriginal.
  ///
  /// In en, this message translates to:
  /// **'Show original'**
  String get showOriginal;

  /// No description provided for @showTranslation.
  ///
  /// In en, this message translates to:
  /// **'Show translation'**
  String get showTranslation;

  /// No description provided for @settingsTranslation.
  ///
  /// In en, this message translates to:
  /// **'Translation'**
  String get settingsTranslation;

  /// No description provided for @translationDesc.
  ///
  /// In en, this message translates to:
  /// **'Any channel can be read in another language: choose “Translate…” in its menu. Google Translate is used for free; for heavy use, add a Google Cloud Translation API key (optional).'**
  String get translationDesc;

  /// No description provided for @translateKey.
  ///
  /// In en, this message translates to:
  /// **'Google Cloud Translation API key (optional)'**
  String get translateKey;

  /// No description provided for @translateKeySaved.
  ///
  /// In en, this message translates to:
  /// **'Saved. Translations use your key.'**
  String get translateKeySaved;
}

class _LDelegate extends LocalizationsDelegate<L> {
  const _LDelegate();

  @override
  Future<L> load(Locale locale) {
    return SynchronousFuture<L>(lookupL(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'he'].contains(locale.languageCode);

  @override
  bool shouldReload(_LDelegate old) => false;
}

L lookupL(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return LEn();
    case 'he':
      return LHe();
  }

  throw FlutterError(
    'L.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
