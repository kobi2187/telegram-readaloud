import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../tts/audio.dart';
import '../tts/audio_renderer.dart';
import 'voice_catalog.dart';
import 'voice_worker.dart';

/// What is installed and in progress.
@immutable
class OfflineVoicesState {
  const OfflineVoicesState({this.installed = const {}, this.active, this.speed = const {}, this.downloads = const {}});

  final Set<String> installed;
  final String? active;

  /// Measured real-time factor per voice (below 1 renders faster than it plays).
  final Map<String, double> speed;

  /// Voices downloading now: bytes done and bytes needed.
  final Map<String, (int, int)> downloads;

  OfflineVoicesState copyWith({Set<String>? installed, Object? active = _keep, Map<String, double>? speed, Map<String, (int, int)>? downloads}) =>
      OfflineVoicesState(
        installed: installed ?? this.installed,
        active: identical(active, _keep) ? this.active : active as String?,
        speed: speed ?? this.speed,
        downloads: downloads ?? this.downloads,
      );

  static const _keep = Object();
}

class ChecksumException implements IOException {
  ChecksumException(this.file);
  final String file;
  @override
  String toString() => '$file is corrupt';
}

class NotEnoughSpaceException implements IOException {
  const NotEnoughSpaceException();
  @override
  String toString() => 'Not enough free space';
}

class UnsupportedVoiceException implements IOException {
  UnsupportedVoiceException(this.cause);
  final Object cause;
  @override
  String toString() => 'The voice cannot run on this device: $cause';
}

class DownloadStoppedException implements IOException {
  const DownloadStoppedException();
  @override
  String toString() => 'Download stopped';
}

/// The downloadable offline Hebrew voices ([VoiceCatalog]): which are installed, which one is
/// active, and downloading them. Files shared by several voices (the renikud phonemizer, the Blue
/// engine) are downloaded once and kept while any installed voice needs them. Every download is
/// resumable, checked against its SHA-256, and test-spoken before the voice counts as installed.
class OfflineVoices implements OfflineHebrew {
  OfflineVoices({
    required this.dir,
    required http.Client client,
    required SharedPreferences prefs,
    VoiceRuntime? runtime,
    List<VoicePack>? catalog,
    this.retryDelay = const Duration(seconds: 2),
  })  : _http = client,
        _prefs = prefs,
        runtime = runtime ?? VoiceWorker(),
        catalog = catalog ?? VoiceCatalog.voices {
    _refresh();
  }

  final String dir;
  final http.Client _http;
  final SharedPreferences _prefs;
  final VoiceRuntime runtime;
  final List<VoicePack> catalog;

  /// The wait before the first retry of a dropped connection; it doubles each time.
  final Duration retryDelay;
  static const _attempts = 6;

  static const _keyActive = 'offline_active';
  static const _keySpeed = 'offline_speed_';

  final state = ValueNotifier(const OfflineVoicesState());
  final _failed = <String>{};
  final _stops = <String>{};

  @override
  String? get activeId {
    final id = state.value.active;
    return id == null || _failed.contains(id) ? null : id;
  }

  VoicePack? pack(String id) {
    for (final p in catalog) {
      if (p.id == id) return p;
    }
    return null;
  }

  File _file(String name) => File('$dir/$name');
  File _part(String name) => File('$dir/$name.part');

  int _length(File f) {
    try {
      return f.existsSync() ? f.lengthSync() : 0;
    } on FileSystemException {
      return 0;
    }
  }

  bool _isInstalled(VoicePack p) => p.files.every((f) => _length(_file(f.name)) == f.size);

  /// Recomputes what is installed; the active voice falls back to another installed one.
  void _refresh() {
    final installed = {for (final p in catalog) if (_isInstalled(p)) p.id};
    final saved = _prefs.getString(_keyActive);
    String? active = saved != null && installed.contains(saved) ? saved : null;
    if (active == null) {
      for (final p in catalog) {
        if (installed.contains(p.id)) {
          active = p.id;
          break;
        }
      }
    }
    if (active != saved) {
      if (active == null) {
        unawaited(_prefs.remove(_keyActive));
      } else {
        unawaited(_prefs.setString(_keyActive, active));
      }
    }
    final speed = <String, double>{
      for (final id in installed)
        if ((_prefs.getDouble('$_keySpeed$id') ?? -1) > 0) id: _prefs.getDouble('$_keySpeed$id')!,
    };
    state.value = state.value.copyWith(installed: installed, active: active, speed: speed);
  }

  Future<void> select(String id) async {
    if (!state.value.installed.contains(id)) return;
    _failed.remove(id);
    await _prefs.setString(_keyActive, id);
    _refresh();
  }

  @override
  Future<Pcm?> synthesize(String text, {String? voiceId}) async {
    final parts = <Pcm>[];
    final stream = speak(text, voiceId: voiceId);
    if (stream == null) return null;
    await for (final p in stream) {
      parts.add(p);
    }
    if (parts.isEmpty) return null;
    final out = Int16List(parts.fold(0, (a, p) => a + p.samples.length));
    var pos = 0;
    for (final p in parts) {
      out.setAll(pos, p.samples);
      pos += p.samples.length;
    }
    return Pcm(out, parts.first.sampleRate);
  }

  /// [text] sentence by sentence from [voiceId] (the active voice by default), or null when that
  /// voice is not available. A voice that fails to load is not tried again until it is selected.
  @override
  Stream<Pcm>? speak(String text, {String? voiceId}) {
    final id = voiceId ?? activeId;
    if (id == null || _failed.contains(id) || !state.value.installed.contains(id)) return null;
    return runtime.speak(dir, id, text).handleError((Object e) {
      if (e is VoiceUnavailableException) _failed.add(id);
      throw e;
    });
  }

  /// Keeps a smoothed measurement, so Settings can warn about voices too slow for this phone.
  Future<void> reportSpeed(String id, double realTimeFactor) async {
    if (!realTimeFactor.isFinite || realTimeFactor <= 0) return;
    final old = _prefs.getDouble('$_keySpeed$id') ?? -1;
    await _prefs.setDouble('$_keySpeed$id', old > 0 ? old * 0.7 + realTimeFactor * 0.3 : realTimeFactor);
    _refresh();
  }

  /// Bytes still to download for [id] (shared files already present count as done).
  int remainingBytes(String id) {
    final p = pack(id);
    if (p == null) return 0;
    var total = 0;
    for (final f in p.files) {
      if (_length(_file(f.name)) == f.size) continue;
      final part = _length(_part(f.name));
      total += f.size - (part > f.size ? 0 : part);
    }
    return total;
  }

  /// Stops the download of [id] after the current chunk; what was downloaded is kept.
  void stopDownload(String id) => _stops.add(id);

  void _progress(String id, (int, int)? value) {
    final downloads = Map.of(state.value.downloads);
    if (value == null) {
      downloads.remove(id);
    } else {
      downloads[id] = value;
    }
    state.value = state.value.copyWith(downloads: downloads);
  }

  /// Downloads what [id] still needs, resuming partial files, then test-speaks it and makes it the
  /// active voice.
  Future<void> download(String id) async {
    final p = pack(id) ?? (throw ArgumentError('Unknown voice $id'));
    if (state.value.downloads.containsKey(id)) return;
    _stops.remove(id);
    await Directory(dir).create(recursive: true);
    final needed = remainingBytes(id);
    var finished = 0;
    _progress(id, (0, needed));
    try {
      for (final file in p.files) {
        final target = _file(file.name);
        if (_length(target) == file.size) continue;
        await target.parent.create(recursive: true);
        final part = _part(file.name);
        if (_length(part) > file.size) await part.delete();
        final before = _length(part);
        var failures = 0;
        while (_length(part) < file.size) {
          try {
            await _fetch(file, part, id, (written) => _progress(id, (finished + written - before, needed)));
            failures = 0;
          } on Exception catch (e) {
            // A dropped or stalled connection: wait, then continue from where it stopped.
            if (!_isTransient(e) || ++failures >= _attempts) rethrow;
            debugPrint('Download of ${file.name} interrupted ($e), retrying');
            await Future<void>.delayed(retryDelay * (1 << (failures - 1)));
            if (_stops.contains(id)) throw const DownloadStoppedException();
          }
        }
        if (await _sha256(part) != file.sha256) {
          await part.delete();
          throw ChecksumException(file.name);
        }
        await part.rename(target.path);
        finished += file.size - before;
        _progress(id, (finished, needed));
      }
      _failed.remove(id);
      try {
        final rtf = await runtime.probe(dir, id);
        await _prefs.setDouble('$_keySpeed$id', rtf);
      } catch (e) {
        debugPrint('Voice $id failed its test: $e');
        await _deleteFiles(p);
        throw UnsupportedVoiceException(e);
      }
      await _prefs.setString(_keyActive, id);
    } on FileSystemException catch (e) {
      // ENOSPC is 28 on Linux, Android and iOS.
      if (e.osError?.errorCode == 28) throw const NotEnoughSpaceException();
      rethrow;
    } finally {
      _stops.remove(id);
      _progress(id, null);
      _refresh();
    }
  }

  Future<void> _fetch(ModelFile file, File part, String id, void Function(int written) onBytes) async {
    final offset = _length(part);
    final request = http.Request('GET', Uri.parse(file.url));
    if (offset > 0) request.headers['Range'] = 'bytes=$offset-';
    final response = await _http.send(request).timeout(const Duration(seconds: 60));
    final bool append;
    if (response.statusCode == 206 && (response.headers['content-range'] ?? '').startsWith('bytes $offset-')) {
      append = true;
    } else if (response.statusCode == 200) {
      append = false; // no range support: start over
    } else if (response.statusCode == 416) {
      await response.stream.drain<void>();
      await part.delete();
      return;
    } else {
      await response.stream.drain<void>();
      if (response.statusCode >= 500 || response.statusCode == 429) throw _ServerError(response.statusCode);
      throw HttpException('Download of ${file.name} failed: HTTP ${response.statusCode}', uri: request.url);
    }
    var written = append ? offset : 0;
    final sink = part.openWrite(mode: append ? FileMode.append : FileMode.write);
    try {
      await for (final chunk in response.stream.timeout(const Duration(seconds: 60))) {
        if (_stops.contains(id)) throw const DownloadStoppedException();
        sink.add(chunk);
        written += chunk.length;
        if (written > file.size) throw HttpException('${file.name} is larger than expected');
        onBytes(written);
      }
    } finally {
      await sink.close();
    }
  }

  static bool _isTransient(Exception e) =>
      e is http.ClientException || e is SocketException || e is TimeoutException || (e is _ServerError);

  static Future<String> _sha256(File file) async => (await sha256.bind(file.openRead()).first).toString();

  /// Deletes [id]; files other installed voices still use are kept.
  Future<void> remove(String id) async {
    final p = pack(id);
    if (p == null) return;
    await runtime.unload(id);
    await _deleteFiles(p);
    _refresh();
  }

  Future<void> _deleteFiles(VoicePack p) async {
    final keep = {
      for (final other in catalog)
        if (other.id != p.id && state.value.installed.contains(other.id))
          for (final f in other.files) f.name,
    };
    for (final f in p.files) {
      if (keep.contains(f.name)) continue;
      for (final file in [_file(f.name), _part(f.name)]) {
        try {
          if (await file.exists()) await file.delete();
        } on FileSystemException catch (_) {}
      }
    }
  }
}

/// A temporary server-side failure (5xx, 429): worth retrying.
class _ServerError implements IOException {
  _ServerError(this.status);
  final int status;
  @override
  String toString() => 'HTTP $status';
}
