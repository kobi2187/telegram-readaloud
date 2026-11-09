import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:audio_session/audio_session.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'data/channel_repository.dart';
import 'data/database.dart';
import 'data/settings.dart';
import 'playback/android_volume.dart';
import 'playback/reader_handler.dart';
import 'sync/sync_engine.dart';
import 'sync/translations.dart';
import 'telegram/telegram_client.dart';
import 'tts/audio_compressor.dart';
import 'tts/audio_renderer.dart';
import 'tts/azure_tts.dart';
import 'tts/device_tts.dart';
import 'tts/gemini_tts.dart';
import 'voice/hebrew_neural_voice.dart';
import 'voice/offline_voices.dart';
import 'voice/voice_downloads.dart';
import 'web/translator.dart';
import 'web/web_client.dart';

/// Hand-wired dependencies; one instance per process.
class AppGraph {
  AppGraph._(this.root, this.db, this.settings, this.httpClient, this.telegram, this.web, this.renderer, this.sync, this.channels, this.device,
      this.voices, this.voiceDownloads);

  final Directory root;
  final AppDatabase db;
  final SettingsStore settings;
  final http.Client httpClient;
  final TelegramClient telegram;
  final WebClient web;
  final AudioRenderer renderer;
  final SyncEngine sync;
  final ChannelRepository channels;
  final DeviceTts device;
  final OfflineVoices voices;
  final VoiceDownloads voiceDownloads;
  late final ReaderHandler player;

  static Future<bool> isOnline(bool allowMetered) async {
    final results = await Connectivity().checkConnectivity();
    if (results.contains(ConnectivityResult.wifi) || results.contains(ConnectivityResult.ethernet) || results.contains(ConnectivityResult.vpn)) return true;
    if (results.contains(ConnectivityResult.mobile)) return allowMetered;
    return results.any((r) => r != ConnectivityResult.none);
  }

  /// Everything but the player: enough for a background sync.
  static Future<AppGraph> core() async {
    final root = await getApplicationSupportDirectory();
    if (Platform.isIOS) {
      // Voice models and post audio can be downloaded again; iCloud backups need not hold them.
      for (final dir in ['models', 'audio']) {
        try {
          await const MethodChannel('readaloud/files').invokeMethod<bool>('excludeFromBackup', '${root.path}/$dir');
        } on Exception catch (e) {
          debugPrint('Could not exclude $dir from backup: $e');
        }
      }
    }
    final db = await AppDatabase.open('${root.path}/readaloud.db');
    final settings = await SettingsStore.load();
    final client = http.Client();
    final telegram = TelegramClient(client);
    final web = WebClient(client);
    final device = DeviceTts();
    final translations = Translations(db: db, translator: Translator(client), settings: settings);
    final voices = OfflineVoices(
      dir: '${root.path}/models', client: client, prefs: await SharedPreferences.getInstance(),
      pronunciation: () => Pronunciation(phonikud: settings.value.usePhonikud, stress: settings.value.ipaStress),
    );
    final renderer = AudioRenderer(
      root: root, db: db, settings: settings, gemini: GeminiTts(client), azure: AzureTts(client), device: device, isOnline: isOnline,
      offlineHebrew: voices,
      compressor: Platform.isAndroid || Platform.isIOS ? PlatformAacCompressor() : const NoCompression(),
      translations: translations,
    );
    final sync = SyncEngine(db: db, telegram: telegram, renderer: renderer, settings: settings, isOnline: isOnline, avatarDir: Directory('${root.path}/avatars'), web: web,
        translations: translations);
    return AppGraph._(root, db, settings, client, telegram, web, renderer, sync, ChannelRepository(db, telegram, renderer, web), device,
        voices, VoiceDownloads(voices));
  }

  /// The whole app, with the player behind the lock screen and notification controls.
  static Future<AppGraph> create({required String Function(String key) noticeText, required String appName}) async {
    final graph = await core();
    final (db, settings, renderer) = (graph.db, graph.settings, graph.renderer);

    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.speech());
    graph.player = await AudioService.init(
      builder: () => ReaderHandler(
        db: db, settings: settings, renderer: renderer, noticeText: noticeText, appName: appName,
        volume: Platform.isAndroid ? AndroidVolume() : null,
      ),
      config: const AudioServiceConfig(
        androidNotificationChannelId: 'io.github.kobi2187.readaloud.playback',
        androidNotificationChannelName: 'Playback',
        androidNotificationOngoing: true,
        androidStopForegroundOnPause: true,
      ),
    );
    return graph;
  }
}
