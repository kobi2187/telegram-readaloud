import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:tg_read_aloud/data/database.dart';
import 'package:tg_read_aloud/data/settings.dart';
import 'package:tg_read_aloud/text/language_segmenter.dart';
import 'package:tg_read_aloud/tts/audio.dart';
import 'package:tg_read_aloud/tts/device_tts.dart';

Future<AppDatabase> memoryDb() async {
  sqfliteFfiInit();
  return AppDatabase.open(inMemoryDatabasePath, factory: databaseFactoryFfiNoIsolate);
}

class FakeDevice implements SpeechEngine {
  final spoken = <(Lang, String)>[];
  @override
  Future<bool> isLanguageAvailable(Lang lang) async => true;
  @override
  Future<Pcm> synthesize(String text, Lang lang, Directory scratch) async {
    spoken.add((lang, text));
    return Pcm(Int16List(16000 * 400 ~/ 1000), 16000);
  }
}

ValueNotifier<AppSettings> settingsOf(AppSettings s) => ValueNotifier(s);

/// Routes requests to [handler]; records every request path (with query).
class RoutingClient extends http.BaseClient {
  RoutingClient(this.handler);
  final Future<http.Response> Function(http.BaseRequest request, String? body) handler;
  final requests = <String>[];

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    requests.add(request.url.hasQuery ? '${request.url.path}?${request.url.query}' : request.url.path);
    final body = request is http.Request ? request.body : null;
    final r = await handler(request, body);
    return http.StreamedResponse(Stream.value(r.bodyBytes), r.statusCode, headers: r.headers, request: request);
  }
}

Directory tempDir() => Directory.systemTemp.createTempSync('readaloud_test');
