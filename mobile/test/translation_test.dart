import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:tg_read_aloud/data/database.dart';
import 'package:tg_read_aloud/data/models.dart';
import 'package:tg_read_aloud/data/settings.dart';
import 'package:tg_read_aloud/sync/translations.dart';
import 'package:tg_read_aloud/tts/audio.dart';
import 'package:tg_read_aloud/tts/audio_renderer.dart';
import 'package:tg_read_aloud/tts/azure_tts.dart';
import 'package:tg_read_aloud/tts/cloud_errors.dart';
import 'package:tg_read_aloud/tts/gemini_tts.dart';
import 'package:tg_read_aloud/web/translator.dart';

import 'helpers.dart';

/// The free endpoint's answer: segments of [translated, original, ...], then the source language.
http.Response gtx(List<String> parts, String source) => http.Response.bytes(
      utf8.encode(jsonEncode([
        [for (final p in parts) [p, 'orig', null, null, 3]],
        null,
        source,
      ])),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

class RecordingOffline implements OfflineHebrew {
  final spoken = <String>[];
  @override
  String? activeId = 'noa';
  @override
  Future<Pcm?> synthesize(String text, {String? voiceId}) async {
    spoken.add(text);
    return Pcm(Int16List(2205), 22050);
  }

  @override
  Stream<Pcm>? speak(String text, {String? voiceId}) {
    spoken.add(text);
    return Stream.value(Pcm(Int16List(2205), 22050));
  }
}

void main() {
  group('translator', () {
    test('posts the text to the free endpoint and joins the segments', () async {
      late http.BaseRequest sent;
      String? body;
      final client = RoutingClient((r, b) async {
        sent = r;
        body = b;
        return gtx(['The Prime Minister met the minister.\n', 'Second paragraph.'], 'iw');
      });
      final t = await Translator(client, minInterval: Duration.zero).translate('ראש הממשלה נפגש עם השר.\nפסקה שנייה.', 'en');
      expect(t.text, 'The Prime Minister met the minister.\nSecond paragraph.');
      expect(t.sourceLang, 'he', reason: 'Google says "iw" for Hebrew');
      expect(sent.method, 'POST');
      expect(sent.url.host, 'translate.googleapis.com');
      expect(sent.url.queryParameters, containsPair('tl', 'en'));
      expect(Uri.splitQueryString(body!)['q'], 'ראש הממשלה נפגש עם השר.\nפסקה שנייה.');
    });

    test('long posts go in parts split between paragraphs', () {
      final para = 'מילה ' * 500; // 2500 characters
      final parts = Translator.chunks([para, para, para].join('\n\n'));
      expect(parts, hasLength(3));
      expect(parts.every((p) => p.length <= Translator.maxChunk), isTrue);
      expect(Translator.chunks('  '), isEmpty);
    });

    test("Google's request limit is reported as throttling", () async {
      final client = RoutingClient((_, _) async => http.Response('', 302, headers: {'location': 'https://www.google.com/sorry/index'}));
      await expectLater(Translator(client, minInterval: Duration.zero).translate('שלום', 'en'), throwsA(isA<ThrottledException>()));
    });

    test('with a key, the official API is used', () async {
      late http.BaseRequest sent;
      final client = RoutingClient((r, _) async {
        sent = r;
        return http.Response(jsonEncode({'data': {'translations': [{'translatedText': 'Hello', 'detectedSourceLanguage': 'he'}]}}), 200);
      });
      final t = await Translator(client).translate('שלום', 'en', apiKey: 'KEY');
      expect(t.text, 'Hello');
      expect(sent.url.host, 'translation.googleapis.com');
      expect(sent.url.queryParameters['key'], 'KEY');
    });
  });

  group('translations', () {
    late AppDatabase db;
    late List<String> asked;
    late List<http.Response> answers;
    late Translations translations;
    final settings = settingsOf(const AppSettings(cloudVoice: CloudVoice.none));

    setUp(() async {
      db = await memoryDb();
      asked = [];
      answers = [];
      final client = RoutingClient((_, b) async {
        asked.add(Uri.splitQueryString(b!)['q']!);
        return answers.removeAt(0);
      });
      translations = Translations(db: db, translator: Translator(client, minInterval: Duration.zero), settings: settings);
      await db.insertChannel(const Channel(username: 'news', title: 'News', startDate: 0, sortOrder: 0, addedAt: 0, translateTo: 'he'));
      await db.insertMessages(const [
        Message(channel: 'news', id: 1, date: 1000, text: 'Hello world.', speech: 'Hello world.'),
        Message(channel: 'news', id: 2, date: 2000, text: 'שלום עולם.', speech: 'שלום עולם.'),
        Message(channel: 'news', id: 3, date: 3000, text: 'Good night.', speech: 'Good night.'),
      ]);
    });

    tearDown(() => db.close());

    test('upcoming posts are translated once; those already in the language are not sent', () async {
      answers.addAll([gtx(['שלום עולם.'], 'en'), gtx(['לילה טוב.'], 'en')]);
      await translations.translatePending([(await db.channel('news'))!], () => false);
      expect(asked, ['Hello world.', 'Good night.']);
      expect((await db.message('news', 1))!.speechIn('he'), 'שלום עולם.');
      expect((await db.message('news', 2))!.speechIn('he'), 'שלום עולם.');
      expect((await db.message('news', 1))!.speechIn(null), 'Hello world.', reason: 'the original is kept');
      await translations.translatePending([(await db.channel('news'))!], () => false);
      expect(asked, hasLength(2), reason: 'nothing left to translate');
    });

    test('when Google limits requests, translation pauses and the original is read', () async {
      answers.add(http.Response('', 429));
      expect(await translations.ensure((await db.message('news', 1))!, 'he'), isNull);
      expect(translations.paused, isTrue);
      expect(await translations.ensure((await db.message('news', 3))!, 'he'), isNull);
      expect(asked, hasLength(1), reason: 'no requests while paused');
    });

    test('a translated channel is voiced from its translation', () async {
      answers.add(gtx(['שלום עולם.'], 'en'));
      final offline = RecordingOffline();
      final renderer = AudioRenderer(
        root: tempDir(), db: db, settings: settings, gemini: GeminiTts(http.Client()), azure: AzureTts(http.Client()),
        device: FakeDevice(), isOnline: (_) async => false, offlineHebrew: offline, translations: translations,
      );
      expect(await renderer.spokenText((await db.message('news', 1))!), 'שלום עולם.');
      await db.setTranslateTo('news', null);
      expect(await renderer.spokenText((await db.message('news', 1))!), 'Hello world.');
    });
  });

  test('a version 1 database gains the translation columns', () async {
    sqfliteFfiInit();
    final dir = Directory.systemTemp.createTempSync('readaloud_db');
    final path = '${dir.path}/v1.db';
    final v1 = await databaseFactoryFfi.openDatabase(path, options: OpenDatabaseOptions(version: 1, onCreate: (db, _) async {
      await db.execute('''CREATE TABLE channels (
        username TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, description TEXT, avatarUrl TEXT, avatarPath TEXT,
        startDate INTEGER NOT NULL, sortOrder INTEGER NOT NULL, enabled INTEGER NOT NULL, startId INTEGER,
        fetchedFrom INTEGER NOT NULL, fetchedTo INTEGER NOT NULL, latestId INTEGER NOT NULL, cursorId INTEGER NOT NULL,
        cursorPositionMs INTEGER NOT NULL, lastSyncAt INTEGER NOT NULL, lastError TEXT, addedAt INTEGER NOT NULL,
        kind TEXT NOT NULL DEFAULT 'telegram', url TEXT)''');
      await db.execute('''CREATE TABLE messages (
        channel TEXT NOT NULL, id INTEGER NOT NULL, date INTEGER NOT NULL, text TEXT NOT NULL, speech TEXT NOT NULL,
        media TEXT, edited INTEGER NOT NULL, forwardedFrom TEXT, audioState INTEGER NOT NULL, audioPath TEXT,
        audioVoiceKey TEXT, played INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, link TEXT, guid TEXT,
        PRIMARY KEY(channel, id))''');
      await db.insert('channels', const Channel(username: 'old', title: 'Old', startDate: 0, sortOrder: 0, addedAt: 0).toRow()..remove('translateTo'));
    }));
    await v1.close();
    final db = await AppDatabase.open(path, factory: databaseFactoryFfi);
    expect((await db.channel('old'))!.translateTo, isNull);
    await db.setTranslateTo('old', 'en');
    expect((await db.channel('old'))!.translateTo, 'en');
    await db.close();
    dir.deleteSync(recursive: true);
  });
}
