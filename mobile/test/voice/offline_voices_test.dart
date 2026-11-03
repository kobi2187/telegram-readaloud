import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tg_read_aloud/tts/audio.dart';
import 'package:tg_read_aloud/voice/engines.dart';
import 'package:tg_read_aloud/voice/offline_voices.dart';
import 'package:tg_read_aloud/voice/voice_catalog.dart';
import 'package:tg_read_aloud/voice/voice_worker.dart';

/// Downloading, switching and removing offline voices over a flaky connection, as in the
/// Android app's OfflineVoicesTest.
void main() {
  late HttpServer server;
  late Directory dir;
  late SharedPreferences prefs;
  late FakeRuntime runtime;
  final blobs = {
    '/shared.onnx': _bytes(1, 200000),
    '/a.onnx': _bytes(2, 300000),
    '/b.onnx': _bytes(3, 100000),
  };
  final requests = <String>[];
  final ranges = <String?>[];
  var honourRanges = true;
  var corrupt = false;
  var failAfter = -1; // drop the connection after this many bytes of a response
  var serverErrors = 0; // answer this many requests with 503

  String sha(List<int> b) => sha256.convert(b).toString();
  ModelFile file(String path, [String? name]) =>
      ModelFile(name ?? path.substring(1), 'http://127.0.0.1:${server.port}$path', blobs[path]!.length, sha(blobs[path]!));

  List<VoicePack> catalog() => [
        VoicePack(id: 'a', name: 'A', male: false, heavy: false, license: 'test', files: [file('/shared.onnx', 'renikud.onnx'), file('/a.onnx')], model: (d) => PiperModel('$d/a.onnx', '')),
        VoicePack(id: 'b', name: 'B', male: true, heavy: true, license: 'test', files: [file('/shared.onnx', 'renikud.onnx'), file('/b.onnx', 'sub/b.onnx')], model: (d) => PiperModel('$d/b.onnx', '')),
      ];

  OfflineVoices voices() =>
      OfflineVoices(dir: dir.path, client: http.Client(), prefs: prefs, runtime: runtime, catalog: catalog(), retryDelay: const Duration(milliseconds: 10));

  setUp(() async {
    requests.clear();
    ranges.clear();
    honourRanges = true;
    corrupt = false;
    failAfter = -1;
    serverErrors = 0;
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      final blob = blobs[req.uri.path];
      if (blob == null) {
        req.response.statusCode = 404;
        await req.response.close();
        return;
      }
      requests.add(req.uri.path);
      if (serverErrors > 0) {
        serverErrors--;
        req.response.statusCode = 503;
        await req.response.close();
        return;
      }
      final data = corrupt ? Uint8List.fromList(blob.reversed.toList()) : blob;
      final range = req.headers.value('range');
      ranges.add(range);
      final start = range == null ? null : int.parse(range.substring(6, range.length - 1));
      var body = data;
      if (start != null && honourRanges) {
        req.response.statusCode = 206;
        req.response.headers.set('content-range', 'bytes $start-${data.length - 1}/${data.length}');
        body = Uint8List.sublistView(data, start);
      }
      if (failAfter >= 0) {
        // Announce the whole body, send part of it, and hang up.
        req.response.contentLength = body.length;
        req.response.add(Uint8List.sublistView(body, 0, min(failAfter, body.length)));
        failAfter = -1;
        await req.response.close().catchError((Object _) {});
        return;
      }
      req.response.add(body);
      await req.response.close();
    });
    dir = await Directory.systemTemp.createTemp('voices');
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    runtime = FakeRuntime();
  });

  tearDown(() async {
    await server.close(force: true);
    await dir.delete(recursive: true);
  });

  test('shared files are downloaded once and the new voice becomes active', () async {
    final v = voices();
    expect(v.activeId, isNull);
    final seen = <(int, int)>[];
    v.state.addListener(() {
      final p = v.state.value.downloads['a'];
      if (p != null) seen.add(p);
    });
    await v.download('a');
    expect(seen.last, (500000, 500000));
    expect(v.state.value.installed, {'a'});
    expect(v.activeId, 'a');
    expect(v.state.value.downloads, isEmpty);
    expect(v.remainingBytes('b'), 100000, reason: 'the phonemizer is already there');

    requests.clear();
    await v.download('b');
    expect(requests, ['/b.onnx']);
    expect(File('${dir.path}/sub/b.onnx').readAsBytesSync(), blobs['/b.onnx']);
    expect(v.activeId, 'b');
    expect(runtime.probed, ['a', 'b'], reason: 'each voice was test-spoken');
    expect(v.state.value.speed['b'], 0.5);

    await v.select('a');
    expect(voices().activeId, 'a');

    await v.remove('a');
    expect(File('${dir.path}/renikud.onnx').existsSync(), isTrue, reason: 'B still needs the shared file');
    expect(File('${dir.path}/a.onnx').existsSync(), isFalse);
    expect(v.activeId, 'b');
    expect(runtime.unloaded, ['a']);
    await v.remove('b');
    expect(v.activeId, isNull);
    expect(File('${dir.path}/renikud.onnx').existsSync(), isFalse);
  });

  test('synthesis uses the active or requested voice', () async {
    final v = voices();
    expect(await v.synthesize('שלום'), isNull);
    await v.download('a');
    await v.download('b');
    expect((await v.synthesize('שלום'))!.sampleRate, 22050);
    expect(runtime.spoken.last, 'b');
    await v.synthesize('שלום', voiceId: 'a');
    expect(runtime.spoken.last, 'a');
    expect(await v.synthesize('שלום', voiceId: 'nope'), isNull);
  });

  test('a voice that fails to load falls back to the phone until selected again', () async {
    final v = voices();
    await v.download('a');
    runtime.broken = true;
    await expectLater(v.synthesize('שלום'), throwsA(isA<VoiceUnavailableException>()));
    expect(v.activeId, isNull, reason: 'the renderer uses the device voice meanwhile');
    runtime.broken = false;
    await v.select('a');
    expect(await v.synthesize('שלום'), isNotNull);
  });

  test('resumes a partial download', () async {
    final v = voices();
    await dir.create(recursive: true);
    File('${dir.path}/a.onnx.part').writeAsBytesSync(Uint8List.sublistView(blobs['/a.onnx']!, 0, 100000));
    expect(v.remainingBytes('a'), 400000);
    await v.download('a');
    expect(ranges, contains('bytes=100000-'));
    expect(File('${dir.path}/a.onnx').readAsBytesSync(), blobs['/a.onnx']);
  });

  test('a dropped connection resumes where it stopped', () async {
    final v = voices();
    failAfter = 50000;
    // The first response ends early; one call still completes the voice.
    await v.download('a');
    expect(v.activeId, 'a');
    expect(File('${dir.path}/renikud.onnx').readAsBytesSync(), blobs['/shared.onnx']);
    expect(ranges.whereType<String>(), isNotEmpty, reason: 'the rest was fetched with a range');
  });

  test('a temporary server error is retried', () async {
    serverErrors = 2;
    final v = voices();
    await v.download('a');
    expect(v.activeId, 'a');
  });

  test('a missing file fails without retrying forever', () async {
    final v = OfflineVoices(dir: dir.path, client: http.Client(), prefs: prefs, runtime: runtime, retryDelay: const Duration(milliseconds: 10), catalog: [
      VoicePack(id: 'x', name: 'X', male: false, heavy: false, license: 'test', files: [ModelFile('x.onnx', 'http://127.0.0.1:${server.port}/missing', 10, 'x')], model: (d) => PiperModel('$d/x.onnx', '')),
    ]);
    await expectLater(v.download('x'), throwsA(isA<HttpException>()));
  });

  test('starts over when the server ignores ranges', () async {
    honourRanges = false;
    final v = voices();
    await dir.create(recursive: true);
    File('${dir.path}/a.onnx.part').writeAsBytesSync(Uint8List.sublistView(blobs['/a.onnx']!, 0, 100000));
    await v.download('a');
    expect(File('${dir.path}/a.onnx').readAsBytesSync(), blobs['/a.onnx']);
  });

  test('a corrupt download is discarded', () async {
    corrupt = true;
    final v = voices();
    await expectLater(v.download('a'), throwsA(isA<ChecksumException>()));
    expect(v.state.value.installed, isEmpty);
    expect(File('${dir.path}/renikud.onnx.part').existsSync(), isFalse);
  });

  test('a voice that cannot run here is not installed', () async {
    final v = voices();
    await v.download('a');
    runtime.unsupported = {'b'};
    await expectLater(v.download('b'), throwsA(isA<UnsupportedVoiceException>()));
    expect(v.state.value.installed, {'a'});
    expect(v.activeId, 'a');
    expect(File('${dir.path}/sub/b.onnx').existsSync(), isFalse);
    expect(File('${dir.path}/renikud.onnx').existsSync(), isTrue);
  });

  test('stopping keeps what was downloaded', () async {
    final v = voices();
    final download = v.download('a');
    v.state.addListener(() {
      final p = v.state.value.downloads['a'];
      if (p != null && p.$1 > 0) v.stopDownload('a');
    });
    await expectLater(download, throwsA(isA<DownloadStoppedException>()));
    expect(v.remainingBytes('a'), lessThan(500000));
    expect(v.remainingBytes('a'), greaterThan(0));
    await v.download('a');
    expect(v.activeId, 'a');
  });
}

Uint8List _bytes(int seed, int n) {
  final r = Random(seed);
  return Uint8List.fromList(List.generate(n, (_) => r.nextInt(256)));
}

class FakeRuntime implements VoiceRuntime {
  final probed = <String>[];
  final spoken = <String>[];
  final unloaded = <String>[];
  var unsupported = <String>{};
  var broken = false;

  @override
  Future<double> probe(String dir, String voiceId) async {
    if (unsupported.contains(voiceId)) throw VoiceUnavailableException('no native library');
    probed.add(voiceId);
    return 0.5;
  }

  @override
  Stream<Pcm> speak(String dir, String voiceId, String text) async* {
    if (broken) throw VoiceUnavailableException('cannot load');
    spoken.add(voiceId);
    yield Pcm(Int16List(100)..fillRange(0, 100, 1), 22050);
  }

  @override
  Future<void> unload(String voiceId) async => unloaded.add(voiceId);
}
