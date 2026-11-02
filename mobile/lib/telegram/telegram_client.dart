import 'dart:io';

import 'package:http/http.dart' as http;

import 'telegram_parser.dart';

class ChannelUnavailableException implements IOException {
  ChannelUnavailableException(this.username);
  final String username;
  @override
  String toString() => 't.me/$username has no public web preview (not a public channel, or preview disabled)';
}

class RateLimitedException implements IOException {
  RateLimitedException(this.retryAfterSeconds);
  final int? retryAfterSeconds;
  @override
  String toString() => 'Telegram rate limit';
}

class HttpStatusException implements IOException {
  HttpStatusException(this.code, this.url);
  final int code;
  final Uri url;
  @override
  String toString() => 'HTTP $code for $url';
}

/// Reads public channels through Telegram's web preview (https://t.me/s/<channel>), which needs no
/// account or API key. Pages hold about 20 posts; `before`/`after` page through history by post id.
class TelegramClient {
  TelegramClient(this._http, {Uri? baseUrl}) : _base = baseUrl ?? Uri.parse('https://t.me/');

  final http.Client _http;
  final Uri _base;

  static const userAgent = 'TGReadAloud/1.0 (+https://github.com/kobi2187/telegram-readaloud)';

  Future<TgPage> fetchPage(String username, {int? before, int? after}) async {
    final url = _base.resolve('s/$username').replace(queryParameters: {
      if (before != null) 'before': '$before',
      if (after != null) 'after': '$after',
    });
    final request = http.Request('GET', url)
      ..headers['User-Agent'] = userAgent
      ..headers['Accept-Language'] = 'he,en;q=0.8'
      ..followRedirects = false;
    final response = await http.Response.fromStream(await _http.send(request));
    if (response.statusCode == 429) {
      throw RateLimitedException(int.tryParse(response.headers['retry-after'] ?? ''));
    }
    // Channels without a web preview redirect from /s/<name> to /<name>.
    if (response.isRedirect || response.statusCode == 302 || response.statusCode == 301 || response.statusCode == 404) {
      throw ChannelUnavailableException(username);
    }
    if (response.statusCode != 200) throw HttpStatusException(response.statusCode, url);
    final page = TelegramParser.parse(response.body, username);
    if (page.title == null && page.messages.isEmpty && before == null && after == null) {
      throw ChannelUnavailableException(username);
    }
    return page;
  }

  /// Downloads [url] to [target] (channel pictures).
  Future<void> download(String url, File target) async {
    final response = await _http.get(Uri.parse(url), headers: {'User-Agent': userAgent});
    if (response.statusCode != 200) throw HttpStatusException(response.statusCode, Uri.parse(url));
    await target.parent.create(recursive: true);
    final tmp = File('${target.path}.tmp');
    await tmp.writeAsBytes(response.bodyBytes);
    await tmp.rename(target.path);
  }
}
