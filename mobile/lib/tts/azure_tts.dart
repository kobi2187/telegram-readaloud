import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'cloud_errors.dart';

class AzureAccount {
  const AzureAccount(this.key, this.region);
  final String key;
  final String region;
  bool get usable => key.trim().isNotEmpty && region.trim().isNotEmpty;
}

/// Microsoft neural voices through the official Azure Speech REST API, with the user's own key.
class AzureTts {
  AzureTts(this._http);

  final http.Client _http;
  DateTime _last = DateTime.fromMillisecondsSinceEpoch(0);

  static const outputFormat = 'audio-24khz-48kbitrate-mono-mp3';
  static const defaultRegion = 'westeurope';
  static const _minInterval = Duration(milliseconds: 3100);

  /// Speaks each (voice, text) part in order and returns MP3, with [trailingSilenceMs] at the end.
  Future<Uint8List> synthesize(AzureAccount account, List<(String, String)> parts, {int trailingSilenceMs = 0, Duration timeout = const Duration(seconds: 60)}) async {
    if (!account.usable) throw ArgumentError('No Azure Speech key');
    final wait = _last.add(_minInterval).difference(DateTime.now());
    if (wait > Duration.zero) await Future<void>.delayed(wait);
    _last = DateTime.now();
    final response = await _http
        .post(
          Uri.parse('https://${account.region.trim().toLowerCase()}.tts.speech.microsoft.com/cognitiveservices/v1'),
          headers: {
            'Ocp-Apim-Subscription-Key': account.key.trim(),
            'X-Microsoft-OutputFormat': outputFormat,
            'User-Agent': 'TGReadAloud',
            'Content-Type': 'application/ssml+xml',
          },
          body: buildSsml(parts, trailingSilenceMs),
        )
        .timeout(timeout);
    switch (response.statusCode) {
      case 200:
        if (response.bodyBytes.isEmpty) throw RejectedTextException(200);
        return response.bodyBytes;
      case 401:
        throw AuthException('Azure Speech', 401);
      case 403:
        if (response.body.toLowerCase().contains('quota')) throw ThrottledException(null, quota: true);
        throw AuthException('Azure Speech', 403);
      case 429:
        final retry = int.tryParse(response.headers['retry-after'] ?? '');
        throw ThrottledException(retry, quota: retry == null || retry > 3600);
      case 400 || 413 || 415:
        throw RejectedTextException(response.statusCode);
      default:
        throw CloudTtsException('Azure Speech HTTP ${response.statusCode}');
    }
  }

  static String escape(String s) {
    final sb = StringBuffer();
    for (final c in s.runes) {
      switch (c) {
        case 0x26:
          sb.write('&amp;');
        case 0x3C:
          sb.write('&lt;');
        case 0x3E:
          sb.write('&gt;');
        case 0x22:
          sb.write('&quot;');
        case 0x27:
          sb.write('&apos;');
        default:
          sb.write(c < 0x20 && c != 0x0A && c != 0x09 ? ' ' : String.fromCharCode(c));
      }
    }
    return sb.toString();
  }

  static String _locale(String voice) => RegExp(r'^([a-z]{2,3}-[A-Z]{2})-').firstMatch(voice)?[1] ?? 'en-US';

  static String buildSsml(List<(String, String)> parts, int trailingSilenceMs) {
    final sb = StringBuffer("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${_locale(parts.first.$1)}'>");
    for (var i = 0; i < parts.length; i++) {
      final (voice, text) = parts[i];
      sb.write("<voice name='${escape(voice)}'>${escape(text)}");
      if (i == parts.length - 1 && trailingSilenceMs > 0) sb.write("<break time='${trailingSilenceMs.clamp(0, 5000)}ms'/>");
      sb.write('</voice>');
    }
    sb.write('</speak>');
    return sb.toString();
  }
}
