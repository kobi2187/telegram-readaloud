import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../tts/azure_tts.dart';
import '../tts/gemini_tts.dart';
import '../tts/voices.dart';

/// Which online voice to download with posts, if any. Offline voices fill in otherwise.
enum CloudVoice { gemini, azure, none }

/// What a quick double press of a volume key does while reading.
enum VolumeKeyMode { off, channels, messages }

@immutable
class AppSettings {
  const AppSettings({
    this.cloudVoice = CloudVoice.gemini,
    this.geminiKey = '',
    this.geminiVoice = GeminiTts.defaultVoice,
    this.azureKey = '',
    this.azureRegion = AzureTts.defaultRegion,
    this.hebrewVoice = Voices.defaultHebrew,
    this.englishVoice = Voices.defaultEnglish,
    this.speed = 1.0,
    this.volumeKeys = VolumeKeyMode.channels,
    this.autoAdvance = true,
    this.announceChannel = true,
    this.syncIntervalMinutes = 60,
    this.allowMetered = true,
    this.prefetchCount = 100,
    this.deletePlayedAudio = true,
    this.defaultStartDays = 1,
    this.currentChannel,
    this.translateKey = '',
  });

  final CloudVoice cloudVoice;
  final String geminiKey;
  final String geminiVoice;
  final String azureKey;
  final String azureRegion;
  final String hebrewVoice;
  final String englishVoice;
  final double speed;
  final VolumeKeyMode volumeKeys;
  final bool autoAdvance;
  final bool announceChannel;
  final int syncIntervalMinutes;
  final bool allowMetered;
  final int prefetchCount;
  final bool deletePlayedAudio;
  final int defaultStartDays;
  final String? currentChannel;

  /// Optional Google Cloud Translation API key; without one the free endpoint is used.
  final String translateKey;

  /// Identifies the online voice; audio downloaded with another key is downloaded again.
  String get voiceKey => switch (cloudVoice) {
        CloudVoice.gemini => 'gemini|$geminiVoice',
        CloudVoice.azure => Voices.voiceKey(hebrewVoice, englishVoice),
        CloudVoice.none => 'none',
      };

  AzureAccount get azure => AzureAccount(azureKey, azureRegion);

  /// The credentials of the selected online service, so a rejected key is not retried.
  String get cloudKey => switch (cloudVoice) {
        CloudVoice.gemini => geminiKey,
        CloudVoice.azure => '$azureRegion/$azureKey',
        CloudVoice.none => '',
      };

  /// An online voice is configured (it still needs a network connection to download).
  bool get cloudEnabled => switch (cloudVoice) {
        CloudVoice.gemini => geminiKey.trim().isNotEmpty,
        CloudVoice.azure => azure.usable,
        CloudVoice.none => false,
      };

  static const _absent = Object();

  AppSettings copyWith({
    CloudVoice? cloudVoice,
    String? geminiKey,
    String? geminiVoice,
    String? azureKey,
    String? azureRegion,
    String? hebrewVoice,
    String? englishVoice,
    double? speed,
    VolumeKeyMode? volumeKeys,
    bool? autoAdvance,
    bool? announceChannel,
    int? syncIntervalMinutes,
    bool? allowMetered,
    int? prefetchCount,
    bool? deletePlayedAudio,
    int? defaultStartDays,
    Object? currentChannel = _absent,
    String? translateKey,
  }) =>
      AppSettings(
        cloudVoice: cloudVoice ?? this.cloudVoice,
        geminiKey: geminiKey ?? this.geminiKey,
        geminiVoice: geminiVoice ?? this.geminiVoice,
        azureKey: azureKey ?? this.azureKey,
        azureRegion: azureRegion ?? this.azureRegion,
        hebrewVoice: hebrewVoice ?? this.hebrewVoice,
        englishVoice: englishVoice ?? this.englishVoice,
        speed: speed ?? this.speed,
        volumeKeys: volumeKeys ?? this.volumeKeys,
        autoAdvance: autoAdvance ?? this.autoAdvance,
        announceChannel: announceChannel ?? this.announceChannel,
        syncIntervalMinutes: syncIntervalMinutes ?? this.syncIntervalMinutes,
        allowMetered: allowMetered ?? this.allowMetered,
        prefetchCount: prefetchCount ?? this.prefetchCount,
        deletePlayedAudio: deletePlayedAudio ?? this.deletePlayedAudio,
        defaultStartDays: defaultStartDays ?? this.defaultStartDays,
        currentChannel: identical(currentChannel, _absent) ? this.currentChannel : currentChannel as String?,
        translateKey: translateKey ?? this.translateKey,
      );
}

/// Settings saved on the phone; listeners hear every change.
class SettingsStore extends ValueNotifier<AppSettings> {
  SettingsStore._(this._prefs, AppSettings initial) : super(initial);

  final SharedPreferences _prefs;

  static Future<SettingsStore> load() async {
    final p = await SharedPreferences.getInstance();
    const d = AppSettings();
    T pick<T>(T? v, T fallback) => v ?? fallback;
    E enumOf<E extends Enum>(List<E> values, String? name, E fallback) => values.where((v) => v.name == name).firstOrNull ?? fallback;
    return SettingsStore._(
      p,
      AppSettings(
        cloudVoice: enumOf(CloudVoice.values, p.getString('cloud_voice'), d.cloudVoice),
        geminiKey: pick(p.getString('gemini_key'), d.geminiKey),
        geminiVoice: pick(p.getString('gemini_voice'), d.geminiVoice),
        azureKey: pick(p.getString('azure_key'), d.azureKey),
        azureRegion: pick(p.getString('azure_region'), d.azureRegion),
        hebrewVoice: pick(p.getString('hebrew_voice'), d.hebrewVoice),
        englishVoice: pick(p.getString('english_voice'), d.englishVoice),
        speed: pick(p.getDouble('speed'), d.speed),
        volumeKeys: enumOf(VolumeKeyMode.values, p.getString('volume_keys'), d.volumeKeys),
        autoAdvance: pick(p.getBool('auto_advance'), d.autoAdvance),
        announceChannel: pick(p.getBool('announce_channel'), d.announceChannel),
        syncIntervalMinutes: pick(p.getInt('sync_interval'), d.syncIntervalMinutes),
        allowMetered: pick(p.getBool('allow_metered'), d.allowMetered),
        prefetchCount: pick(p.getInt('prefetch'), d.prefetchCount),
        deletePlayedAudio: pick(p.getBool('delete_played'), d.deletePlayedAudio),
        defaultStartDays: pick(p.getInt('default_start_days'), d.defaultStartDays),
        currentChannel: p.getString('current_channel'),
        translateKey: pick(p.getString('translate_key'), d.translateKey),
      ),
    );
  }

  Future<void> update(AppSettings Function(AppSettings) transform) async {
    final s = transform(value);
    value = s;
    await Future.wait([
      _prefs.setString('cloud_voice', s.cloudVoice.name),
      _prefs.setString('gemini_key', s.geminiKey.trim()),
      _prefs.setString('gemini_voice', s.geminiVoice),
      _prefs.setString('azure_key', s.azureKey.trim()),
      _prefs.setString('azure_region', s.azureRegion.trim().toLowerCase()),
      _prefs.setString('hebrew_voice', s.hebrewVoice),
      _prefs.setString('english_voice', s.englishVoice),
      _prefs.setDouble('speed', s.speed),
      _prefs.setString('volume_keys', s.volumeKeys.name),
      _prefs.setBool('auto_advance', s.autoAdvance),
      _prefs.setBool('announce_channel', s.announceChannel),
      _prefs.setInt('sync_interval', s.syncIntervalMinutes),
      _prefs.setBool('allow_metered', s.allowMetered),
      _prefs.setInt('prefetch', s.prefetchCount),
      _prefs.setBool('delete_played', s.deletePlayedAudio),
      _prefs.setInt('default_start_days', s.defaultStartDays),
      _prefs.setString('translate_key', s.translateKey.trim()),
      if (s.currentChannel != null) _prefs.setString('current_channel', s.currentChannel!) else _prefs.remove('current_channel'),
    ]);
  }
}
