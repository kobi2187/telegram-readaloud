import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';

import '../data/database.dart';
import '../data/models.dart';
import '../data/settings.dart';
import '../text/language_segmenter.dart';
import 'audio.dart';
import 'azure_tts.dart';
import 'cloud_errors.dart';
import 'device_tts.dart';
import 'gemini_tts.dart';
import 'voices.dart';

/// Why the online voice is currently not used, for the settings screen.
enum CloudStatus { ok, badKey, quota, throttled }

/// The last known [status] of one online [voice] service.
@immutable
class CloudHealth {
  const CloudHealth(this.voice, this.status);
  final CloudVoice voice;
  final CloudStatus status;
}

/// Speaks Hebrew without a network connection (the downloadable offline voices).
abstract class OfflineHebrew {
  /// The offline voice that reads Hebrew now, or null when none is installed.
  String? get activeId;

  /// 16-bit mono speech from [voiceId], or null when that voice is not available.
  Future<Pcm?> synthesize(String text, {String? voiceId});
}

class NoOfflineHebrew implements OfflineHebrew {
  const NoOfflineHebrew();
  @override
  String? get activeId => null;
  @override
  Future<Pcm?> synthesize(String text, {String? voiceId}) async => null;
}

enum Prefetch { done, networkFailed, cloudUnavailable }

/// Produces the audio file paired with each post. The online voice (Gemini or Azure) is preferred
/// and is pre-downloaded during sync; without it, posts are voiced on the phone.
class AudioRenderer {
  AudioRenderer({
    required this.root,
    required this.db,
    required this.settings,
    required this.gemini,
    required this.azure,
    required this.device,
    required this.isOnline,
    this.offlineHebrew = const NoOfflineHebrew(),
  });

  /// App storage; audio lives in `root/audio/<channel>/`.
  final Directory root;
  final AppDatabase db;
  final ValueListenable<AppSettings> settings;
  final GeminiTts gemini;
  final AzureTts azure;
  final SpeechEngine device;
  final Future<bool> Function(bool allowMetered) isOnline;
  final OfflineHebrew offlineHebrew;

  static const gapAfterPostMs = 700;
  static const gapAfterClipMs = 300;
  static const _pauseMs = 250;
  static const localDevice = 'local|device';
  static const localVoicePrefix = 'local|';

  Directory get audioDir => Directory('${root.path}/audio');
  Directory get _clipsDir => Directory('${audioDir.path}/_clips');
  Directory get _scratch => Directory('${root.path}/tts-scratch');

  final health = ValueNotifier(const CloudHealth(CloudVoice.none, CloudStatus.ok));
  final _pausedUntil = <CloudVoice, int>{};
  final _rejectedKeys = <String>{};
  final _locks = <String, Future<void>>{};

  /// Runs [task] for [key] after any earlier task for the same key (one render per post at a time).
  Future<T> _locked<T>(String key, Future<T> Function() task) {
    final previous = _locks[key] ?? Future.value();
    final result = previous.then((_) => task());
    _locks[key] = result.then((_) {}, onError: (_) {});
    return result;
  }

  File _messageFile(String channel, int id, String ext) => File('${audioDir.path}/$channel/$id.$ext');
  String _keyId(AppSettings cfg) => '${cfg.cloudVoice.name}|${cfg.cloudKey}';

  /// An online voice is configured, its key was not rejected, and we are not backing off.
  bool cloudReady(AppSettings cfg) =>
      cfg.cloudEnabled && !_rejectedKeys.contains(_keyId(cfg)) && DateTime.now().millisecondsSinceEpoch >= (_pausedUntil[cfg.cloudVoice] ?? 0);

  Future<bool> _canUseCloudNow(AppSettings cfg) async => cloudReady(cfg) && await isOnline(cfg.allowMetered);

  String _localVoiceKey(String text) {
    final voice = offlineHebrew.activeId;
    return voice != null && RegExp('[א-ת]').hasMatch(text) ? '$localVoicePrefix$voice' : localDevice;
  }

  bool _canImproveLocal(Message m) {
    if (m.audioState == AudioState.neural) return false;
    final key = _localVoiceKey(m.speech);
    return key != localDevice && key != m.audioVoiceKey;
  }

  /// A playable file for the post, rendering it now if needed: with the online voice when
  /// possible (short timeout, since a listener is waiting), otherwise on the phone.
  Future<File> ensureMessageAudio(String channel, int id) => _locked('$channel/$id', () async {
        final m = await db.message(channel, id);
        if (m == null) throw StateError('Post $channel/$id not found');
        final existing = m.audioPath == null ? null : File(m.audioPath!);
        if (existing != null && await existing.exists() && await existing.length() > 0 && !_canImproveLocal(m)) return existing;
        final cfg = settings.value;
        var cloudFailed = m.audioState == AudioState.neuralFailed;
        if (!cloudFailed && await _canUseCloudNow(cfg)) {
          try {
            return await _writeCloud(m, cfg, timeout: const Duration(seconds: 25));
          } on Exception catch (e) {
            debugPrint('Online voice failed for $channel/$id, rendering on the phone: $e');
            _noteCloudFailure(e, cfg);
            if (e is RejectedTextException) cloudFailed = true;
          }
        }
        return _writeLocal(m, cloudFailed ? AudioState.neuralFailed : AudioState.local);
      });

  /// Downloads online-voice audio for an upcoming post ahead of time.
  Future<Prefetch> prefetchNeural(Message m, AppSettings cfg) => _locked('${m.channel}/${m.id}', () async {
        if (!cloudReady(cfg)) return Prefetch.cloudUnavailable;
        final fresh = await db.message(m.channel, m.id);
        if (fresh == null || !await needsNeural(fresh, cfg)) return Prefetch.done;
        try {
          // A sync can afford to wait out a short per-minute limit.
          await _writeCloud(fresh, cfg, timeout: const Duration(seconds: 90), maxWait: const Duration(seconds: 65));
          return Prefetch.done;
        } on RejectedTextException {
          await db.setAudio(fresh.channel, fresh.id, AudioState.neuralFailed, fresh.audioPath, fresh.audioState == AudioState.local ? fresh.audioVoiceKey : null);
          return Prefetch.done;
        } on Exception catch (e) {
          debugPrint('Prefetch failed for ${m.channel}/${m.id}: $e');
          return _noteCloudFailure(e, cfg) ? Prefetch.cloudUnavailable : Prefetch.networkFailed;
        }
      });

  /// Records key/quota problems; returns true if the online voice is now paused.
  bool _noteCloudFailure(Exception e, AppSettings cfg) {
    if (e is AuthException) {
      _rejectedKeys.add(_keyId(cfg));
      health.value = CloudHealth(cfg.cloudVoice, CloudStatus.badKey);
      return true;
    }
    if (e is ThrottledException) {
      final seconds = e.retryAfterSeconds ?? (e.quota ? 6 * 3600 : 60);
      _pausedUntil[cfg.cloudVoice] = DateTime.now().millisecondsSinceEpoch + seconds * 1000;
      health.value = CloudHealth(cfg.cloudVoice, e.quota ? CloudStatus.quota : CloudStatus.throttled);
      return true;
    }
    return false;
  }

  Future<bool> needsNeural(Message m, AppSettings cfg) async {
    if (m.speech.trim().isEmpty || m.audioState == AudioState.neuralFailed) return false;
    final path = m.audioPath;
    final current = m.audioState == AudioState.neural && m.audioVoiceKey == cfg.voiceKey && path != null && await File(path).exists();
    return !current;
  }

  Future<File> _writeCloud(Message m, AppSettings cfg, {required Duration timeout, Duration maxWait = Duration.zero}) async {
    final (bytes, ext) = await _renderCloud(m.speech, cfg, gapAfterPostMs, timeout, maxWait);
    final file = _messageFile(m.channel, m.id, ext);
    await Wav.write(file, bytes);
    await db.setAudio(m.channel, m.id, AudioState.neural, file.path, cfg.voiceKey);
    final old = m.audioPath;
    if (old != null && old != file.path) await _delete(File(old));
    health.value = CloudHealth(cfg.cloudVoice, CloudStatus.ok);
    return file;
  }

  Future<File> _writeLocal(Message m, int state) async {
    final voiceKey = _localVoiceKey(m.speech);
    final bytes = await _renderLocal(m.speech, gapAfterPostMs);
    final file = _messageFile(m.channel, m.id, 'wav');
    await Wav.write(file, bytes);
    await db.setAudio(m.channel, m.id, state, file.path, voiceKey);
    final old = m.audioPath;
    if (old != null && old != file.path) await _delete(File(old));
    return file;
  }

  /// Audio for a short spoken phrase (channel name, "all caught up"), cached per text and voice.
  /// Only Azure is used online here: Gemini's small free quota is kept for posts.
  Future<File> ensureClip(String key, String text) {
    final safeKey = key.replaceAll(RegExp(r'[^A-Za-z0-9_]'), '_');
    return _locked('clip/$safeKey', () async {
      final cfg = settings.value;
      if (cfg.cloudVoice == CloudVoice.azure) {
        final cloudFile = File('${_clipsDir.path}/${safeKey}_${_tag(text + cfg.voiceKey)}.mp3');
        if (await cloudFile.exists()) return cloudFile;
        if (await _canUseCloudNow(cfg)) {
          try {
            final (bytes, _) = await _renderCloud(text, cfg, gapAfterClipMs, const Duration(seconds: 15), Duration.zero);
            await Wav.write(cloudFile, bytes);
            return cloudFile;
          } on Exception catch (e) {
            _noteCloudFailure(e, cfg);
          }
        }
      }
      final localFile = File('${_clipsDir.path}/${safeKey}_${_tag(text + _localVoiceKey(text))}.wav');
      if (await localFile.exists()) return localFile;
      await Wav.write(localFile, await _renderLocal(text, gapAfterClipMs));
      return localFile;
    });
  }

  String _tag(String s) => s.hashCode.toRadixString(16);

  /// A sample of [voice] from the online service selected in [cfg], for the settings screen.
  Future<File> previewCloud(AppSettings cfg, String voice, String text) async {
    final isGemini = cfg.cloudVoice == CloudVoice.gemini;
    final file = File('${_scratch.path}/previews/${isGemini ? 'gemini_${voice}_${_tag(text + cfg.geminiKey)}.wav' : 'azure.mp3'}');
    if (isGemini && await file.exists()) return file;
    Uint8List bytes;
    try {
      bytes = isGemini
          ? Wav.join([await gemini.synthesize(cfg.geminiKey, text, voice, timeout: const Duration(seconds: 30))], trailingMs: 200)
          : await azure.synthesize(cfg.azure, [(voice, text)], timeout: const Duration(seconds: 20));
    } on Exception catch (e) {
      _noteCloudFailure(e, cfg);
      rethrow;
    }
    _rejectedKeys.remove(_keyId(cfg));
    _pausedUntil.remove(cfg.cloudVoice);
    health.value = CloudHealth(cfg.cloudVoice, CloudStatus.ok);
    await Wav.write(file, bytes);
    return file;
  }

  /// A sample of an installed offline voice.
  Future<File> previewOffline(String voiceId, String text) async {
    final pcm = await offlineHebrew.synthesize(text, voiceId: voiceId);
    if (pcm == null) throw StateError('Offline voice $voiceId is not available');
    final file = File('${_scratch.path}/previews/offline_$voiceId.wav');
    await Wav.write(file, Wav.join([pcm], trailingMs: 200));
    return file;
  }

  Future<(Uint8List, String)> _renderCloud(String text, AppSettings cfg, int gapMs, Duration timeout, Duration maxWait) async {
    switch (cfg.cloudVoice) {
      case CloudVoice.gemini:
        // One request per post (the model reads mixed Hebrew/English itself), split only if very long.
        final clips = <Pcm>[];
        for (final chunk in GeminiTts.chunks(text)) {
          clips.add(await gemini.synthesize(cfg.geminiKey, chunk, cfg.geminiVoice, maxWait: maxWait, timeout: timeout));
        }
        if (clips.isEmpty) throw RejectedTextException(0);
        return (Wav.join(clips, pauseMs: _pauseMs, trailingMs: gapMs), 'gemini.wav');
      case CloudVoice.azure:
        // One request with a voice per language run (the official API accepts several voices).
        final parts = LanguageSegmenter.segment(text).map((s) => (Voices.neuralFor(s.lang, cfg.hebrewVoice, cfg.englishVoice), s.text)).toList();
        if (parts.isEmpty) throw RejectedTextException(0);
        return (await azure.synthesize(cfg.azure, parts, trailingSilenceMs: gapMs, timeout: timeout), 'mp3');
      case CloudVoice.none:
        throw CloudTtsException('No online voice selected');
    }
  }

  /// On the phone, a voice per language run (the offline voice for Hebrew, the device engine for
  /// the rest); one device voice for everything if a run fails.
  Future<Uint8List> _renderLocal(String text, int gapMs) async {
    final segments = LanguageSegmenter.segment(text);
    final clips = <Pcm>[];
    for (final seg in segments) {
      final pcm = await _renderSegment(seg);
      if (pcm != null) clips.add(pcm);
    }
    if (clips.isNotEmpty && clips.length == segments.length) return Wav.join(clips, pauseMs: _pauseMs, trailingMs: gapMs);
    final lang = LanguageSegmenter.dominant(text) ?? Lang.en;
    try {
      return Wav.join([await device.synthesize(text, lang, _scratch)], trailingMs: gapMs);
    } catch (_) {
      if (clips.isNotEmpty) return Wav.join(clips, pauseMs: _pauseMs, trailingMs: gapMs); // part of the post beats silence
      throw TtsUnavailableException('No voice available for this post');
    }
  }

  Future<Pcm?> _renderSegment(Segment seg) async {
    if (seg.lang == Lang.he && offlineHebrew.activeId != null) {
      try {
        final pcm = await offlineHebrew.synthesize(seg.text);
        if (pcm != null && pcm.samples.isNotEmpty) return pcm;
      } catch (e) {
        debugPrint('Offline voice failed, using the device voice: $e');
      }
    }
    try {
      return await device.synthesize(seg.text, seg.lang, _scratch);
    } catch (e) {
      debugPrint('Device TTS skipped a ${seg.lang} run: $e');
      return null;
    }
  }

  Future<void> _delete(File f) async {
    try {
      if (await f.exists()) await f.delete();
    } catch (_) {}
  }

  Future<void> deleteChannelAudio(String channel) async {
    final dir = Directory('${audioDir.path}/$channel');
    if (await dir.exists()) await dir.delete(recursive: true);
  }

  Future<void> clearAll() async {
    if (await audioDir.exists()) await audioDir.delete(recursive: true);
    if (await _scratch.exists()) await _scratch.delete(recursive: true);
  }

  Future<int> cacheSizeBytes() async {
    if (!await audioDir.exists()) return 0;
    var total = 0;
    await for (final e in audioDir.list(recursive: true)) {
      if (e is File) total += await e.length();
    }
    return total;
  }

  /// Deletes files no post references any more (edited posts, removed channels).
  Future<void> deleteOrphans(Set<String> referenced) async {
    if (!await audioDir.exists()) return;
    final recent = DateTime.now().subtract(const Duration(minutes: 10));
    final weekAgo = DateTime.now().subtract(const Duration(days: 7));
    await for (final e in audioDir.list(recursive: true)) {
      if (e is! File) continue;
      final modified = await e.lastModified();
      if (e.parent.path == _clipsDir.path) {
        if (modified.isBefore(weekAgo)) await _delete(e);
      } else if (!referenced.contains(e.path) && modified.isBefore(recent)) {
        await _delete(e);
      }
    }
  }
}
