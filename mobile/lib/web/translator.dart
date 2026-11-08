import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../tts/cloud_errors.dart';

/// A translated text and the language Google detected it was written in.
class Translation {
  const Translation(this.text, this.sourceLang);
  final String text;
  final String sourceLang;
}

/// Google Translate. Without a key it uses the free endpoint Google's own web widgets call
/// (translate.googleapis.com, no account needed, but rate-limited and unofficial); with a Google
/// Cloud Translation API key it uses the official API.
class Translator {
  Translator(this._http, {this.minInterval = const Duration(milliseconds: 400), DateTime Function()? clock}) : _now = clock ?? DateTime.now;

  final http.Client _http;

  /// Spacing between requests to the free endpoint, to stay under its limits.
  final Duration minInterval;
  final DateTime Function() _now;
  DateTime _last = DateTime(0);

  /// Languages a channel can be read in (each has a phone voice; Hebrew also the offline voices).
  static const targets = {'he': 'עברית', 'en': 'English', 'ru': 'Русский', 'ar': 'العربية'};

  /// Requests are kept under this size; longer posts go in parts, split between paragraphs.
  static const maxChunk = 4000;

  Future<Translation> translate(String text, String to, {String apiKey = ''}) async {
    final parts = chunks(text);
    final out = <String>[];
    String? source;
    for (final part in parts) {
      final t = apiKey.trim().isEmpty ? await _free(part, to) : await _cloud(part, to, apiKey.trim());
      out.add(t.text);
      source ??= t.sourceLang;
    }
    return Translation(out.join('\n\n'), normalizeLang(source ?? ''));
  }

  /// Google still reports Hebrew as "iw"; regional variants count as the language.
  static String normalizeLang(String code) {
    final base = code.toLowerCase().split(RegExp('[-_]')).first;
    return base == 'iw' ? 'he' : base;
  }

  /// [text] in parts of at most [maxChunk] characters, split between paragraphs (or lines).
  static List<String> chunks(String text) {
    final t = text.trim();
    if (t.isEmpty) return const [];
    if (t.length <= maxChunk) return [t];
    final out = <String>[];
    var current = StringBuffer();
    for (final para in t.split(RegExp(r'\n\s*\n'))) {
      final pieces = para.length <= maxChunk ? [para] : _hardSplit(para);
      for (final p in pieces) {
        if (current.length + p.length + 2 > maxChunk && current.isNotEmpty) {
          out.add(current.toString().trim());
          current = StringBuffer();
        }
        current
          ..write(p)
          ..write('\n\n');
      }
    }
    if (current.isNotEmpty) out.add(current.toString().trim());
    return out;
  }

  static List<String> _hardSplit(String s) {
    final out = <String>[];
    var rest = s;
    while (rest.length > maxChunk) {
      var cut = rest.lastIndexOf(RegExp(r'[.!?\n]\s'), maxChunk);
      if (cut <= 0) cut = rest.lastIndexOf(' ', maxChunk);
      if (cut <= 0) cut = maxChunk - 1;
      out.add(rest.substring(0, cut + 1).trim());
      rest = rest.substring(cut + 1).trim();
    }
    if (rest.isNotEmpty) out.add(rest);
    return out;
  }

  Future<Translation> _free(String text, String to) async {
    final wait = _last.add(minInterval).difference(_now());
    if (!wait.isNegative) await Future<void>.delayed(wait);
    _last = _now();
    // POST: long texts fit, and Google is less quick to ask for a captcha than with GET.
    final uri = Uri.https('translate.googleapis.com', '/translate_a/single', {'client': 'gtx', 'sl': 'auto', 'tl': to, 'dt': 't'});
    final req = http.Request('POST', uri)
      ..followRedirects = false
      ..bodyFields = {'q': text};
    final res = await http.Response.fromStream(await _http.send(req).timeout(const Duration(seconds: 30)));
    if (res.statusCode == 429 || res.statusCode == 302 || res.statusCode == 503) {
      // 302 to google.com/sorry: too many requests from this address for now.
      throw ThrottledException(int.tryParse(res.headers['retry-after'] ?? ''), quota: false);
    }
    if (res.statusCode != 200) throw CloudTtsException('Google Translate failed (HTTP ${res.statusCode})');
    final data = jsonDecode(utf8.decode(res.bodyBytes));
    if (data is! List || data.isEmpty || data[0] is! List) throw CloudTtsException('Unexpected Google Translate response');
    final sb = StringBuffer();
    for (final seg in data[0] as List) {
      if (seg is List && seg.isNotEmpty && seg[0] is String) sb.write(seg[0]);
    }
    final source = data.length > 2 && data[2] is String ? data[2] as String : '';
    return Translation(sb.toString().trim(), normalizeLang(source));
  }

  Future<Translation> _cloud(String text, String to, String key) async {
    final res = await _http
        .post(
          Uri.https('translation.googleapis.com', '/language/translate/v2', {'key': key}),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'q': text, 'target': to, 'format': 'text'}),
        )
        .timeout(const Duration(seconds: 30));
    if (res.statusCode == 400 || res.statusCode == 401 || res.statusCode == 403) throw AuthException('Google Cloud Translation', res.statusCode);
    if (res.statusCode == 429) throw ThrottledException(null, quota: true);
    if (res.statusCode != 200) throw CloudTtsException('Google Cloud Translation failed (HTTP ${res.statusCode})');
    final t = ((jsonDecode(utf8.decode(res.bodyBytes)) as Map)['data'] as Map)['translations'] as List;
    final first = t.first as Map;
    return Translation((first['translatedText'] as String).trim(), normalizeLang(first['detectedSourceLanguage'] as String? ?? ''));
  }
}
