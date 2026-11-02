import 'dart:io';

import 'package:html/parser.dart' as html;
import 'package:http/http.dart' as http;

import 'article_extractor.dart';
import 'charsets.dart';
import 'feed_parser.dart';

class SiteRefusedException implements IOException {
  SiteRefusedException(this.url, this.code);
  final String url;
  final int code;
  @override
  String toString() => '$url refused the request (HTTP $code)';
}

class NoFeedException implements IOException {
  NoFeedException(this.url);
  final String url;
  @override
  String toString() => 'No news feed found at $url';
}

class DiscoveredFeed {
  DiscoveredFeed(this.url, this.title, this.feed);
  final String url;
  final String? title;
  final Feed feed;
}

/// Fetches news feeds and article pages from ordinary websites.
class WebClient {
  WebClient(this._http);

  final http.Client _http;

  static const userAgent =
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';

  Future<(Uri, List<int>, String?)> _get(Uri url) async {
    final request = http.Request('GET', url)
      ..headers['User-Agent'] = userAgent
      ..headers['Accept-Language'] = 'he,en;q=0.8';
    final streamed = await _http.send(request).timeout(const Duration(seconds: 30));
    final response = await http.Response.fromStream(streamed);
    if (response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 429) {
      throw SiteRefusedException(url.toString(), response.statusCode);
    }
    if (response.statusCode != 200) throw HttpException('HTTP ${response.statusCode} for $url');
    final finalUrl = response.request?.url ?? url;
    final charset = RegExp(r'charset=([\w-]+)', caseSensitive: false).firstMatch(response.headers['content-type'] ?? '')?[1];
    return (finalUrl, response.bodyBytes, charset);
  }

  Future<Feed> fetchFeed(String url) async {
    final u = Uri.tryParse(url);
    if (u == null || !u.hasScheme) throw NoFeedException(url);
    final (_, bytes, _) = await _get(u);
    if (!FeedParser.looksLikeFeed(bytes)) throw NoFeedException(url);
    return FeedParser.parse(bytes);
  }

  /// Finds the news feed behind what the user typed: a feed URL, or a site whose page links its
  /// feed (`<link rel="alternate" type="application/rss+xml">`), or a feed at a common path.
  Future<DiscoveredFeed> discover(String input) async {
    final url = normalize(input);
    if (url == null) throw NoFeedException(input);
    final (finalUrl, bytes, charset) = await _get(url);
    if (FeedParser.looksLikeFeed(bytes)) {
      final feed = FeedParser.parse(bytes);
      return DiscoveredFeed(finalUrl.toString(), feed.title, feed);
    }
    final doc = html.parse(Charsets.decodeDocument(bytes, headerCharset: charset));
    final linked = doc
        .querySelectorAll('link[rel="alternate"]')
        .where((l) => RegExp(r'(rss|atom)\+xml').hasMatch(l.attributes['type'] ?? ''))
        .map((l) => l.attributes['href'])
        .whereType<String>()
        .where((h) => h.isNotEmpty)
        .map((h) => finalUrl.resolve(h).toString());
    final guesses = ['feed', 'rss', 'rss.xml', 'feed.xml', 'atom.xml', 'index.xml'].map((p) => finalUrl.resolve('/$p').toString());
    final pageTitle = doc.querySelector('title')?.text.trim();
    for (final candidate in {...linked, ...guesses}) {
      try {
        final feed = await fetchFeed(candidate);
        if (feed.items.isEmpty) continue;
        return DiscoveredFeed(candidate, feed.title ?? ((pageTitle?.isNotEmpty ?? false) ? pageTitle : null), feed);
      } catch (_) {
        continue;
      }
    }
    throw NoFeedException(input);
  }

  Future<Article> fetchArticle(String url) async {
    final u = Uri.tryParse(url);
    if (u == null || !u.hasScheme) throw HttpException('Bad link $url');
    final (_, bytes, charset) = await _get(u);
    return ArticleExtractor.extract(html.parse(Charsets.decodeDocument(bytes, headerCharset: charset)));
  }

  /// "example.com", "www.example.com/news" or a full URL, as an https URL.
  static Uri? normalize(String input) {
    final t = input.trim().split(' ').first;
    if (t.isEmpty || (!t.contains('.') && !t.startsWith('http'))) return null;
    return Uri.tryParse(t.startsWith('http://') || t.startsWith('https://') ? t : 'https://$t');
  }

  /// The first web link in shared text ("Look at this https://…").
  static String? firstUrl(String text) {
    final m = RegExp(r'''https?://[^\s<>"']+''').firstMatch(text);
    return m?[0]?.replaceAll(RegExp(r'[.,)\]»"]+$'), '');
  }
}
