import 'dart:async';
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
import 'package:tg_read_aloud/tts/audio_compressor.dart';
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
  String variant = '';
  @override
  String? activeId = 'noa';

  /// Awaited before each sentence of a streaming render (none: no waiting).
  Future<void> Function(int sentence)? gate;
  var cancelled = false;

  @override
  Future<Pcm?> synthesize(String text, {String? voiceId}) async {
    if ((voiceId ?? activeId) == null) return null;
    spoken.add(text);
    return Pcm(Int16List.fromList(List.filled(22050 ~/ 2, 1000)), 22050);
  }

  /// Two quarter-second sentences per text, like the real voices' sentence-by-sentence output.
  @override
  Stream<Pcm>? speak(String text, {String? voiceId}) {
    if ((voiceId ?? activeId) == null) return null;
    spoken.add(text);
    late final StreamController<Pcm> c;
    c = StreamController<Pcm>(
      onListen: () async {
        for (var i = 0; i < 2; i++) {
          if (gate != null) await gate!(i);
          if (cancelled) break;
          c.add(Pcm(Int16List.fromList(List.filled(22050 ~/ 4, 1000)), 22050));
        }
        await c.close();
      },
      onCancel: () => cancelled = true,
    );
    return c.stream;
  }
}

class FakeCompressor implements AudioCompressor {
  var works = true;
  @override
  Future<File?> compress(File wav) async {
    if (!works) return null;
    final out = File(wav.path.replaceAll('.wav', '.m4a'));
    await out.writeAsBytes([1, 2, 3]);
    await wav.delete();
    return out;
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

    test('voiced posts are stored compressed, or as WAV when the phone cannot encode', () async {
      final compressor = FakeCompressor();
      final r = AudioRenderer(
        root: tempDir(), db: db, settings: settings, gemini: GeminiTts(client, minInterval: Duration.zero), azure: AzureTts(client),
        device: device, isOnline: (_) async => online, offlineHebrew: offline, compressor: compressor,
      );
      final f = await r.ensureMessageAudio('news', 3);
      expect(f.path, endsWith('/news/3.m4a'));
      expect((await db.message('news', 3))!.audioPath, f.path);
      expect(File(f.path.replaceAll('.m4a', '.wav')).existsSync(), isFalse);
      final live = await r.openMessageAudio('news', 2) as LiveAudio;
      expect((await live.done).path, endsWith('/news/2.m4a'));
      compressor.works = false;
      offline.activeId = null;
      await db.setAudio('news', 1, AudioState.none, null, null);
      expect((await r.ensureMessageAudio('news', 1)).path, endsWith('/news/1.wav'));
    });

    test("a channel's repeated signature is not read aloud", () async {
      const sig = 'להצטרפות לערוץ לחצו כאן.';
      await db.insertMessages([
        for (var i = 1; i <= 3; i++) Message(channel: 'sig', id: i, date: i * 1000, text: '', speech: 'ידיעה מספר $i.\n\n$sig'),
      ]);
      await renderer.ensureMessageAudio('sig', 2);
      final live = await renderer.openMessageAudio('sig', 3) as LiveAudio;
      await live.done;
      expect(offline.spoken.where((t) => t.contains('ידיעה')), hasLength(2));
      expect(offline.spoken.any((t) => t.contains('להצטרפות')), isFalse);
    });

    test('changing the pronunciation voices Hebrew posts again', () async {
      final first = await renderer.ensureMessageAudio('news', 2);
      expect((await renderer.ensureMessageAudio('news', 2)).path, first.path, reason: 'kept while nothing changed');
      expect(offline.spoken.where((t) => t.contains('עוד הודעה')), hasLength(1));
      offline.variant = '|phonikud';
      final again = await renderer.ensureMessageAudio('news', 2);
      expect((await db.message('news', 2))!.audioVoiceKey, 'local|noa|phonikud');
      expect(offline.spoken.where((t) => t.contains('עוד הודעה')), hasLength(2));
      expect(again.existsSync(), isTrue);
    });

    test('a Hebrew post voiced on the phone plays sentence by sentence', () async {
      final audio = await renderer.openMessageAudio('news', 1);
      expect(audio, isA<LiveAudio>());
      final live = audio as LiveAudio;
      final parts = await live.parts.toList();
      // Two Hebrew sentences, the English run from the device voice, then the gap after the post.
      expect(parts, hasLength(4));
      final done = await live.done;
      int dataBytes(File f) => f.lengthSync() - 44;
      final partsBytes = parts.fold<int>(0, (a, f) => a + dataBytes(f));
      // Parts are at their own rates; the whole file is at the highest (22.05 kHz) with the same audio.
      // Two Hebrew sentences, the pause between the runs, the English sentence (no pause after the last one), the gap.
      expect(dataBytes(done), closeTo(22050 * 2 * (0.25 + 0.25 + 0.25 + 0.4 + 0.7), 22050 * 2 * 0.01));
      expect(partsBytes, greaterThan(0));
      final m = (await db.message('news', 1))!;
      expect(m.audioPath, done.path);
      expect(m.audioState, AudioState.local);
      expect(m.audioVoiceKey, 'local|noa');
      expect(await renderer.openMessageAudio('news', 1), isA<ReadyAudio>(), reason: 'voiced once');
    });

    test('the phone voice alone renders the whole post at once', () async {
      expect(await renderer.openMessageAudio('news', 3), isA<ReadyAudio>());
      offline.activeId = null;
      expect(await renderer.openMessageAudio('news', 2), isA<ReadyAudio>());
    });

    test('moving on stops voicing a post', () async {
      final second = Completer<void>();
      offline.gate = (i) => i == 0 ? Future.value() : second.future;
      final live = await renderer.openMessageAudio('news', 2) as LiveAudio;
      final parts = <File>[];
      final sub = live.parts.listen(parts.add);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(parts, hasLength(1), reason: 'the first sentence plays while the second is voiced');
      live.cancel();
      second.complete(); // the sentence being voiced finishes, then the render stops
      await expectLater(live.done, throwsA(isA<RenderCancelledException>()));
      await sub.cancel();
      expect(offline.cancelled, isTrue, reason: 'the voice was told to stop');
      expect((await db.message('news', 2))!.audioPath, isNull, reason: 'a partial render is not kept');
      // The next request starts afresh.
      offline.gate = null;
      offline.cancelled = false;
      final again = await renderer.openMessageAudio('news', 2) as LiveAudio;
      await again.done;
    });

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
