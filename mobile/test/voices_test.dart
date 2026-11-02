import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:tg_read_aloud/data/database.dart';
import 'package:tg_read_aloud/data/models.dart';
import 'package:tg_read_aloud/data/settings.dart';
import 'package:tg_read_aloud/text/language_segmenter.dart';
import 'package:tg_read_aloud/tts/audio.dart';
import 'package:tg_read_aloud/tts/audio_renderer.dart';
import 'package:tg_read_aloud/tts/azure_tts.dart';
import 'package:tg_read_aloud/tts/cloud_errors.dart';
import 'package:tg_read_aloud/tts/gemini_tts.dart';

import 'helpers.dart';

Uint8List wav(int samples, {int rate = 24000}) => Wav.join([Pcm(Int16List(samples), rate)], pauseMs: 0);

http.Response audioResponse(Uint8List bytes) => http.Response(
    '{"id":"x","steps":[{"type":"user_input"},{"type":"model_output","content":[{"type":"audio","data":"${base64Encode(bytes)}"}]}]}', 200);

const dailyQuota = '''{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"30s"}]}}''';

class FakeOffline implements OfflineHebrew {
  final spoken = <String>[];
  @override
  String? activeId = 'noa';
  @override
  Future<Pcm?> synthesize(String text, {String? voiceId}) async {
    if ((voiceId ?? activeId) == null) return null;
    spoken.add(text);
    return Pcm(Int16List.fromList(List.filled(22050 ~/ 2, 1000)), 22050);
  }
}

void main() {
  group('gemini', () {
    final responses = <http.Response>[];
    final bodies = <Map<String, dynamic>>[];
    late RoutingClient client;
    var now = 1800000000000;
    late GeminiTts tts;

    setUp(() {
      responses.clear();
      bodies.clear();
      now = 1800000000000;
      client = RoutingClient((request, body) async {
        bodies.add(jsonDecode(body!) as Map<String, dynamic>);
        expect(request.headers['x-goog-api-key'], isNotEmpty);
        return responses.removeAt(0);
      });
      tts = GeminiTts(client, clock: () => now, minInterval: Duration.zero);
    });

    test('sends the documented request and decodes WAV', () async {
      responses.add(audioResponse(wav(2400)));
      final pcm = await tts.synthesize('KEY', 'שלום, hello', 'Kore');
      expect(pcm.sampleRate, 24000);
      expect(pcm.samples.length, 2400);
      expect(client.requests.single, '/v1beta/interactions');
      final body = bodies.single;
      expect(body['model'], 'gemini-3.8-flash-tts');
      expect(((body['input'] as List)[0]['content'] as List)[0]['text'], 'שלום, hello');
      expect(body['response_format']['type'], 'audio');
      expect((body['generation_config']['speech_config'] as List)[0]['voice'], 'Kore');
    });

    test('accepts raw PCM', () {
      final pcm = GeminiTts.parseAudio('{"steps":[{"type":"model_output","content":[{"type":"audio","mime_type":"audio/l16;rate=16000","data":"${base64Encode(Uint8List(320))}"}]}]}');
      expect(pcm.sampleRate, 16000);
      expect(pcm.samples.length, 160);
    });

    test('a bad key is reported as such', () async {
      responses.add(http.Response('{"error":{"code":400,"message":"API key not valid.","details":[{"reason":"API_KEY_INVALID"}]}}', 400));
      expect(() => tts.synthesize('BAD', 'text', 'Kore'), throwsA(isA<AuthException>()));
    });

    test('other bad requests reject the text', () async {
      responses.add(http.Response('{"error":{"code":400,"message":"Unsupported input"}}', 400));
      expect(() => tts.synthesize('KEY', 'text', 'Kore'), throwsA(isA<RejectedTextException>()));
    });

    test('a daily quota moves to the next model, then gives up until the reset', () async {
      responses
        ..add(http.Response(dailyQuota, 429))
        ..add(audioResponse(wav(100)));
      await tts.synthesize('KEY', 'text', 'Kore');
      expect(bodies[1]['model'], 'gemini-3.8-flash-lite-tts');
      responses.add(http.Response(dailyQuota, 429));
      try {
        await tts.synthesize('KEY', 'text', 'Kore');
        fail('expected a quota error');
      } on ThrottledException catch (e) {
        expect(e.quota, isTrue);
        expect(e.retryAfterSeconds, greaterThan(600));
        expect(tts.allPaused(), isTrue);
      }
      now += 25 * 3600000;
      responses.add(audioResponse(wav(100)));
      await tts.synthesize('KEY', 'text', 'Kore');
    });

    test('long posts are split between sentences', () {
      expect(GeminiTts.chunks('  short '), ['short']);
      expect(GeminiTts.chunks(' \n '), isEmpty);
      final text = 'זה משפט באורך בינוני שחוזר על עצמו. ' * 200;
      final chunks = GeminiTts.chunks(text, max: 500);
      expect(chunks.every((c) => c.length <= 500 && c.endsWith('.')), isTrue);
      expect(chunks.join().replaceAll(' ', ''), text.replaceAll(' ', ''));
    });
  });

  group('renderer', () {
    late AppDatabase db;
    late AudioRenderer renderer;
    late FakeDevice device;
    late FakeOffline offline;
    late RoutingClient client;
    final responses = <http.Response>[];
    final settings = settingsOf(const AppSettings(cloudVoice: CloudVoice.none));
    var online = false;

    setUp(() async {
      db = await memoryDb();
      device = FakeDevice();
      offline = FakeOffline();
      online = false;
      responses.clear();
      client = RoutingClient((_, _) async => responses.removeAt(0));
      settings.value = const AppSettings(cloudVoice: CloudVoice.none);
      renderer = AudioRenderer(
        root: tempDir(), db: db, settings: settings, gemini: GeminiTts(client, minInterval: Duration.zero), azure: AzureTts(client),
        device: device, isOnline: (_) async => online, offlineHebrew: offline,
      );
      await db.insertMessages(const [
        Message(channel: 'news', id: 1, date: 1000, text: '', speech: 'שלום לכולם. This is the English part.'),
        Message(channel: 'news', id: 2, date: 2000, text: '', speech: 'עוד הודעה בעברית.'),
        Message(channel: 'news', id: 3, date: 3000, text: '', speech: 'Only English here.'),
      ]);
    });

    tearDown(() => db.close());

    test('offline Hebrew voice for Hebrew and the device for English', () async {
      final file = await renderer.ensureMessageAudio('news', 1);
      expect(offline.spoken, ['שלום לכולם.']);
      expect(device.spoken, [(Lang.en, 'This is the English part.')]);
      final clip = Wav.parse(await file.readAsBytes());
      expect(clip.sampleRate, 22050);
      final ms = clip.pcm.length ~/ 2 * 1000 ~/ 22050;
      expect(ms, inInclusiveRange(1840, 1860)); // 500 + pause 250 + 400 + gap 700
      final m = (await db.message('news', 1))!;
      expect(m.audioState, AudioState.local);
      expect(m.audioVoiceKey, 'local|noa');
    });

    test('Hebrew is voiced again when another offline voice becomes active', () async {
      offline.activeId = null;
      await renderer.ensureMessageAudio('news', 2);
      expect(device.spoken, [(Lang.he, 'עוד הודעה בעברית.')]);
      offline.activeId = 'noa';
      await renderer.ensureMessageAudio('news', 2);
      await renderer.ensureMessageAudio('news', 2);
      expect(offline.spoken.length, 1);
      offline.activeId = 'shaul';
      await renderer.ensureMessageAudio('news', 2);
      expect((await db.message('news', 2))!.audioVoiceKey, 'local|shaul');
      offline.activeId = null;
      await renderer.ensureMessageAudio('news', 2);
      expect((await db.message('news', 2))!.audioVoiceKey, 'local|shaul');
    });

    test('Gemini audio is downloaded with the post; clips stay on the phone', () async {
      online = true;
      settings.value = const AppSettings(cloudVoice: CloudVoice.gemini, geminiKey: 'KEY', geminiVoice: 'Kore');
      responses.add(audioResponse(wav(7200)));
      final file = await renderer.ensureMessageAudio('news', 1);
      expect(file.path, endsWith('1.gemini.wav'));
      final m = (await db.message('news', 1))!;
      expect(m.audioState, AudioState.neural);
      expect(m.audioVoiceKey, 'gemini|Kore');
      await renderer.ensureClip('ch_news', 'חדשות');
      expect(client.requests.length, 1);
      expect(await renderer.needsNeural(m, settings.value), isFalse);
      expect(await renderer.needsNeural(m, settings.value.copyWith(geminiVoice: 'Puck')), isTrue);
    });

    test('a used-up quota falls back to the phone until the reset', () async {
      online = true;
      settings.value = const AppSettings(cloudVoice: CloudVoice.gemini, geminiKey: 'KEY');
      responses
        ..add(http.Response(dailyQuota, 429))
        ..add(http.Response(dailyQuota, 429));
      final file = await renderer.ensureMessageAudio('news', 2);
      expect(file.path, endsWith('2.wav'));
      expect(renderer.health.value.status, CloudStatus.quota);
      expect(renderer.cloudReady(settings.value), isFalse);
      await renderer.ensureMessageAudio('news', 1);
      expect(client.requests.length, 2);
      expect(await renderer.prefetchNeural((await db.message('news', 3))!, settings.value), Prefetch.cloudUnavailable);
    });

    test('a rejected key is not retried until changed', () async {
      online = true;
      settings.value = const AppSettings(cloudVoice: CloudVoice.gemini, geminiKey: 'BAD');
      responses.add(http.Response('{"error":{"code":400,"message":"API key not valid.","details":[{"reason":"API_KEY_INVALID"}]}}', 400));
      await renderer.ensureMessageAudio('news', 2);
      expect(renderer.health.value.status, CloudStatus.badKey);
      expect(renderer.cloudReady(settings.value), isFalse);
      expect(renderer.cloudReady(settings.value.copyWith(geminiKey: 'NEW')), isTrue);
    });

    test('sync replaces phone audio with the online voice', () async {
      settings.value = const AppSettings(cloudVoice: CloudVoice.gemini, geminiKey: 'KEY');
      final local = await renderer.ensureMessageAudio('news', 3);
      online = true;
      responses.add(audioResponse(wav(100)));
      expect(await renderer.prefetchNeural((await db.message('news', 3))!, settings.value), Prefetch.done);
      final m = (await db.message('news', 3))!;
      expect(m.audioState, AudioState.neural);
      expect(File(local.path).existsSync(), isFalse);
    });
  });
}
