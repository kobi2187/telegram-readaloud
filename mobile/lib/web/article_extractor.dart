import 'dart:convert';

import 'package:html/dom.dart';

import '../text/speech_text.dart';
import 'html_text.dart';

class Article {
  const Article(this.title, this.text, {required this.summaryOnly});
  final String title;

  /// Paragraphs separated by blank lines.
  final String text;

  /// Only a teaser was available (paywall, or the page could not be read).
  final bool summaryOnly;

  Article copyWith({String? text}) => Article(title, text ?? this.text, summaryOnly: summaryOnly);
}

/// Pulls the readable article out of a news page: the publisher's own structured data
/// (schema.org `articleBody`) when present, otherwise the block of the page with the most
/// paragraph text, the way browser reader modes work.
class ArticleExtractor {
  ArticleExtractor._();

  static const minArticle = 300;
  static const paywallFullText = 1500;

  static Article extract(Document doc) {
    final ld = _jsonLdArticles(doc);
    String? meta(String selector) => doc.querySelector(selector)?.attributes['content']?.trim().nonEmpty;
    final title = ld.map((o) => _string(o, 'headline')).whereType<String>().firstOrNull ??
        meta('meta[property="og:title"]') ??
        doc.querySelector('h1')?.text.trim().nonEmpty ??
        doc.querySelector('title')?.text.trim() ??
        '';
    final cleanTitle = title.replaceAll(RegExp(r'\s+[|–—-]\s+[^|–—-]{1,30}$'), '').trim();
    final paywalled = ld.any((o) {
      final v = o['isAccessibleForFree'];
      return v == false || v == 'false' || v == 'False';
    });
    final description = ld.map((o) => _string(o, 'description')).whereType<String>().firstOrNull ??
        meta('meta[property="og:description"]') ??
        meta('meta[name="description"]') ??
        '';

    final bodies = ld.map((o) => _string(o, 'articleBody')).whereType<String>().toList()..sort((a, b) => b.length.compareTo(a.length));
    final fromLd = bodies.isEmpty ? '' : _paragraphs(bodies.first);
    final fromPage = (fromLd.length >= minArticle && !paywalled) ? '' : readerText(doc);
    final body = fromLd.length >= fromPage.length ? fromLd : fromPage;
    String longer(String a, String b) => a.length >= b.length ? a : b;

    if (paywalled && fromPage.length >= paywallFullText && fromPage.split('\n\n').length >= 5) {
      // Metered paywalls still serve the whole article to every visitor; hard ones only a teaser.
      return Article(cleanTitle, fromPage, summaryOnly: false);
    }
    if (paywalled) return Article(cleanTitle, longer(description, fromLd).trim(), summaryOnly: true);
    if (body.length >= minArticle) return Article(cleanTitle, body, summaryOnly: false);
    return Article(cleanTitle, longer(body, description).trim(), summaryOnly: true);
  }

  static List<Map<String, dynamic>> _jsonLdArticles(Document doc) {
    final out = <Map<String, dynamic>>[];
    void visit(dynamic e) {
      if (e is List) {
        e.forEach(visit);
      } else if (e is Map<String, dynamic>) {
        final type = e['@type'];
        final types = type is List ? type.whereType<String>().toList() : (type is String ? [type] : <String>[]);
        if (types.any((t) => t.endsWith('Article') || t == 'BlogPosting' || t == 'Report')) out.add(e);
        if (e['@graph'] != null) visit(e['@graph']);
      }
    }

    for (final script in doc.querySelectorAll('script[type="application/ld+json"]')) {
      try {
        visit(jsonDecode(script.text));
      } catch (_) {
        // Broken JSON-LD is common; skip it.
      }
    }
    return out;
  }

  static String? _string(Map<String, dynamic> o, String key) {
    final v = o[key];
    if (v is! String) return null;
    final s = HtmlText.plain(v);
    return s.trim().isEmpty ? null : s;
  }

  static String _paragraphs(String text) =>
      HtmlText.plain(text).split(RegExp(r'\n\s*\n|\n')).map((p) => p.trim()).where((p) => p.isNotEmpty).join('\n\n');

  static final _noise = RegExp(
    'comment|footer|header|menu|nav|related|share|social|sidebar|promo|advert|banner|newsletter|subscribe|recommend|outbrain|taboola|breadcrumb|tags|author-bio|cookie',
    caseSensitive: false,
  );

  /// Reader-mode heuristic: the container whose own paragraphs hold the most text. Blocks whose
  /// class names look like menus or promos are skipped, unless that leaves too little.
  static String readerText(Document doc) {
    final first = _readerText(doc, skipNoise: true);
    return first.length >= minArticle ? first : _readerText(doc, skipNoise: false);
  }

  static String _readerText(Document doc, {required bool skipNoise}) {
    final body = doc.body;
    if (body == null) return '';
    final clone = body.clone(true);
    for (final e in clone.querySelectorAll('script, style, noscript, nav, header, footer, aside, iframe, svg, figure, button')) {
      e.remove();
    }
    for (final e in clone.querySelectorAll('[aria-hidden="true"]')) {
      e.remove();
    }
    // ASP.NET sites wrap the whole page in a form; only small forms (search, newsletter) are noise.
    for (final f in clone.querySelectorAll('form').where((f) => f.text.length < 500).toList()) {
      f.remove();
    }
    if (skipNoise) {
      for (final el in clone.querySelectorAll('[class], [id]').toList()) {
        final key = '${el.className} ${el.id}';
        final tag = el.localName;
        if (tag != 'article' && tag != 'main' && tag != 'body' && _noise.hasMatch(key) && el.text.length < 2000) {
          el.remove();
        }
      }
    }
    final scores = <Element, int>{};
    for (final p in clone.querySelectorAll('p')) {
      final len = p.text.trim().length;
      if (len < 40) continue;
      final parent = p.parent;
      if (parent == null) continue;
      scores[parent] = (scores[parent] ?? 0) + len;
      final gp = parent.parent;
      if (gp != null) scores[gp] = (scores[gp] ?? 0) + len ~/ 2;
    }
    Element? best;
    if (scores.isNotEmpty) {
      best = scores.entries.reduce((a, b) => a.value >= b.value ? a : b).key;
    } else {
      best = clone.querySelector('article') ?? clone.querySelector('[itemprop="articleBody"]') ?? clone.querySelector('main');
    }
    if (best == null) return '';
    final seen = <String>{};
    return best
        .querySelectorAll('p, h2, h3, li')
        .map((e) => e.text.replaceAll(RegExp(r'\s+'), ' ').trim())
        .where((t) => t.length >= 25 || t.endsWith(':'))
        .where(seen.add)
        .join('\n\n');
  }
}

/// How an article is shown and spoken in a post.
class ArticleText {
  ArticleText._();

  static String display(String title, Article? article) {
    final parts = <String>[];
    for (final p in [title, article?.text ?? '']) {
      if (p.trim().isNotEmpty && !parts.contains(p)) parts.add(p);
    }
    return parts.join('\n\n');
  }

  static String speech(String title, Article? article) {
    final t = title.trim();
    final heading = (t.isEmpty || '.!?:'.contains(t[t.length - 1])) ? t : '$t.';
    final rawBody = article?.text ?? '';
    final body = rawBody.trim() != title.trim() ? rawBody : '';
    var note = '';
    if (article?.summaryOnly == true && body.trim().isNotEmpty) {
      note = RegExp('[א-ת]').hasMatch(title + body) ? 'זהו תקציר בלבד.' : 'This is only a summary.';
    }
    return SpeechText.clean([heading, body, note].where((s) => s.trim().isNotEmpty).join('\n\n'));
  }
}

extension on String {
  String? get nonEmpty => trim().isEmpty ? null : this;
}
