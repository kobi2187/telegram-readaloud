import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'audio.dart';
import 'cloud_errors.dart';

class _ModelUnavailable implements Exception {}

/// Google's Gemini text-to-speech through the official Gemini API ("Interactions"). A free API key
/// from Google AI Studio needs only a Google account. The models detect the language themselves,
/// so a mixed Hebrew/English post is one request with one voice.
///
/// Free-tier quotas are per model, so when one model's quota runs out the next one is tried.
class GeminiTts {
  GeminiTts(this._http, {Uri? baseUrl, this.models = defaultModels, int Function()? clock, this.minInterval = const Duration(seconds: 4)})
      : _base = baseUrl ?? Uri.parse('https://generativelanguage.googleapis.com/'),
        _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final http.Client _http;
  final Uri _base;
  final List<String> models;
  final int Function() _clock;
  final Duration minInterval;
  final _pausedUntil = <String, int>{};
  int _lastRequestAt = 0;

  static const defaultModels = ['gemini-3.8-flash-tts', 'gemini-3.8-flash-lite-tts'];
  static const defaultVoice = 'Charon';
  static const sampleRate = 24000;
  static const _style = 'calm, clear news reading';
  static const _maxChars = 3000;

  /// Prebuilt voices with Google's one-word character descriptions.
  static const voices = [
    ('Charon', 'Informative'), ('Kore', 'Firm'), ('Puck', 'Upbeat'), ('Zephyr', 'Bright'),
    ('Fenrir', 'Excitable'), ('Leda', 'Youthful'), ('Orus', 'Firm'), ('Aoede', 'Breezy'),
    ('Callirrhoe', 'Easy-going'), ('Autonoe', 'Bright'), ('Enceladus', 'Breathy'), ('Iapetus', 'Clear'),
    ('Umbriel', 'Easy-going'), ('Algieba', 'Smooth'), ('Despina', 'Smooth'), ('Erinome', 'Clear'),
    ('Algenib', 'Gravelly'), ('Rasalgethi', 'Informative'), ('Laomedeia', 'Upbeat'), ('Achernar', 'Soft'),
    ('Alnilam', 'Firm'), ('Schedar', 'Even'), ('Gacrux', 'Mature'), ('Pulcherrima', 'Forward'),
    ('Achird', 'Friendly'), ('Zubenelgenubi', 'Casual'), ('Vindemiatrix', 'Gentle'), ('Sadachbia', 'Lively'),
    ('Sadaltager', 'Knowledgeable'), ('Sulafat', 'Warm'),
  ];

  /// Speaks [text] with [voice]. A per-minute limit is waited out for up to [maxWait]; otherwise
  /// the next model is tried, then [ThrottledException] is thrown.
  Future<Pcm> synthesize(String key, String text, String voice, {Duration maxWait = Duration.zero, Duration timeout = const Duration(seconds: 90)}) async {
    if (key.trim().isEmpty) throw ArgumentError('No Gemini API key');
    var waited = 0;
    while (true) {
      final now = _clock();
      final model = models.where((m) => (_pausedUntil[m] ?? 0) <= now).firstOrNull;
      if (model == null) {
        final soonest = models.map((m) => _pausedUntil[m] ?? now).reduce((a, b) => a < b ? a : b);
        final wait = soonest - now;
        if (waited + wait <= maxWait.inMilliseconds) {
          await Future<void>.delayed(Duration(milliseconds: wait < 0 ? 0 : wait));
          waited += wait;
          continue;
        }
        throw ThrottledException(wait ~/ 1000, quota: wait > 10 * 60000);
      }
      try {
        return await _request(model, key, text, voice, timeout);
      } on ThrottledException catch (e) {
        final pauseMs = (e.retryAfterSeconds ?? 60) * 1000;
        _pausedUntil[model] = now + pauseMs;
        // A short per-minute pause is worth waiting out rather than switching to a lesser model.
        if (!e.quota && waited + pauseMs <= maxWait.inMilliseconds) {
          await Future<void>.delayed(Duration(milliseconds: pauseMs));
          waited += pauseMs;
          _pausedUntil.remove(model);
        }
      } on _ModelUnavailable {
        _pausedUntil[model] = now + 24 * 3600000;
      }
    }
  }

  /// True while every model is waiting out a quota or rate limit.
  bool allPaused() {
    final now = _clock();
    return models.every((m) => (_pausedUntil[m] ?? 0) > now);
  }

  Future<Pcm> _request(String model, String key, String text, String voice, Duration timeout) async {
    final body = jsonEncode({
      'model': model,
      'input': [
        {
          'type': 'user_input',
          'content': [
            {
              'type': 'text',
              'text': text,
              'annotations': [
                {'type': 'speech_metadata', 'style': _style},
              ],
            },
          ],
        },
      ],
      'response_format': {'type': 'audio'},
      'generation_config': {
        'speech_config': [
          {'voice': voice},
        ],
      },
    });
    final wait = _lastRequestAt + minInterval.inMilliseconds - _clock();
    if (wait > 0) await Future<void>.delayed(Duration(milliseconds: wait));
    _lastRequestAt = _clock();
    final response = await _http
        .post(_base.resolve('v1beta/interactions'), headers: {'x-goog-api-key': key.trim(), 'Content-Type': 'application/json'}, body: body)
        .timeout(timeout);
    if (response.statusCode == 200) return parseAudio(utf8.decode(response.bodyBytes));
    throw _errorFor(response.statusCode, utf8.decode(response.bodyBytes, allowMalformed: true));
  }

  Exception _errorFor(int code, String raw) {
    Map<String, dynamic>? error;
    try {
      error = (jsonDecode(raw) as Map<String, dynamic>)['error'] as Map<String, dynamic>?;
    } catch (_) {}
    final message = (error?['message'] as String?) ?? '';
    final details = (error?['details'] as List?)?.whereType<Map<String, dynamic>>().toList() ?? const [];
    final reasons = details.map((d) => d['reason']).whereType<String>().toList();
    final quotaIds = details
        .expand((d) => (d['violations'] as List?)?.whereType<Map<String, dynamic>>() ?? const <Map<String, dynamic>>[])
        .map((v) => v['quotaId'])
        .whereType<String>()
        .toList();
    final retryDelay = details.map((d) => d['retryDelay']).whereType<String>().map((s) => double.tryParse(s.replaceAll('s', ''))).whereType<double>().firstOrNull;
    if (code == 401 || code == 403) return AuthException('Gemini API', code);
    if (code == 400 && (reasons.contains('API_KEY_INVALID') || message.toLowerCase().contains('api key'))) return AuthException('Gemini API', code);
    if (code == 429) {
      final daily = quotaIds.any((q) => q.toLowerCase().contains('perday')) || message.toLowerCase().contains('per day');
      final seconds = daily ? _secondsUntilQuotaReset() : (retryDelay == null ? 60 : retryDelay.toInt() + 1);
      return ThrottledException(seconds, quota: daily);
    }
    if (code == 404) return _ModelUnavailable();
    if (code == 400 || code == 413) return RejectedTextException(code);
    return CloudTtsException('Gemini API HTTP $code: ${message.length > 200 ? message.substring(0, 200) : message}');
  }

  /// Daily quotas reset at midnight Pacific time (UTC-8, or UTC-7 in summer).
  int _secondsUntilQuotaReset() {
    final now = DateTime.fromMillisecondsSinceEpoch(_clock(), isUtc: true);
    final offset = _pacificDst(now) ? 7 : 8;
    final pacific = now.subtract(Duration(hours: offset));
    final nextMidnight = DateTime.utc(pacific.year, pacific.month, pacific.day + 1).add(Duration(hours: offset));
    return nextMidnight.difference(now).inSeconds + 60;
  }

  static bool _pacificDst(DateTime utc) {
    // US daylight time: second Sunday of March 10:00 UTC to first Sunday of November 09:00 UTC.
    DateTime nthSunday(int month, int n) {
      var d = DateTime.utc(utc.year, month, 1);
      while (d.weekday != DateTime.sunday) {
        d = d.add(const Duration(days: 1));
      }
      return d.add(Duration(days: 7 * (n - 1)));
    }

    final start = nthSunday(3, 2).add(const Duration(hours: 10));
    final end = nthSunday(11, 1).add(const Duration(hours: 9));
    return utc.isAfter(start) && utc.isBefore(end);
  }

  /// [text] in pieces of at most [max] characters, cut between paragraphs or sentences.
  static List<String> chunks(String text, {int max = _maxChars}) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return const [];
    if (trimmed.length <= max) return [trimmed];
    final pieces = trimmed.split(RegExp(r'(?<=[.!?…])\s+|\n+')).expand((p) {
      if (p.length <= max) return [p];
      return [for (var i = 0; i < p.length; i += max) p.substring(i, i + max > p.length ? p.length : i + max)];
    });
    final out = <String>[];
    final current = StringBuffer();
    for (final piece in pieces) {
      if (current.isNotEmpty && current.length + 1 + piece.length > max) {
        out.add(current.toString());
        current.clear();
      }
      if (current.isNotEmpty) current.write(' ');
      current.write(piece);
    }
    if (current.isNotEmpty) out.add(current.toString());
    return out.map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
  }

  /// Finds the last audio item in an Interactions response (steps → model_output → content) and
  /// decodes it: WAV as documented, or raw 16-bit PCM at the rate in its mime type.
  static Pcm parseAudio(String raw) {
    final audio = <Map<String, dynamic>>[];
    void walk(dynamic e) {
      if (e is Map<String, dynamic>) {
        if (e['type'] == 'audio' && e['data'] is String) audio.add(e);
        e.values.forEach(walk);
      } else if (e is List) {
        e.forEach(walk);
      }
    }

    walk(jsonDecode(raw));
    if (audio.isEmpty) throw RejectedTextException(200);
    final item = audio.last;
    final bytes = base64Decode(item['data'] as String);
    if (bytes.isEmpty) throw RejectedTextException(200);
    if (bytes.length > 12 && String.fromCharCodes(bytes.sublist(0, 4)) == 'RIFF') return Wav.parse(bytes).toPcm();
    final mime = (item['mime_type'] ?? item['mimeType'] ?? '') as String;
    final rate = int.tryParse(RegExp(r'rate=(\d+)').firstMatch(mime)?[1] ?? '') ?? sampleRate;
    final copy = Uint8List.fromList(bytes.sublist(0, bytes.length & ~1)); // phones are little-endian, like the data
    return Pcm(copy.buffer.asInt16List(), rate);
  }
}
