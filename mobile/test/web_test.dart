import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:html/parser.dart' as html;
import 'package:tg_read_aloud/web/article_extractor.dart';
import 'package:tg_read_aloud/web/charsets.dart';
import 'package:tg_read_aloud/web/feed_parser.dart';

int utc(String iso) => DateTime.parse(iso).millisecondsSinceEpoch;

void main() {
  group('feeds', () {
    const rss = '''<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
<title>חדשות ynet</title><link>https://www.ynet.co.il</link>
<item><title><![CDATA[ראש הממשלה נפגש עם שר הביטחון]]></title>
<link>https://www.ynet.co.il/news/article/abc</link><guid isPermaLink="false">abc-1</guid>
<pubDate>Sun, 01 Nov 2026 10:00:00 +0200</pubDate>
<description><![CDATA[<div><img src="x.jpg"/>הפגישה נמשכה <b>שעתיים</b>.</div>]]></description></item>
<item><title>Second &amp; last</title><link>https://www.ynet.co.il/news/article/def</link>
<pubDate>Sun, 1 Nov 2026 08:30:00 GMT</pubDate>
<description>&lt;p&gt;Escaped &lt;i&gt;HTML&lt;/i&gt; summary&lt;/p&gt;</description>
<content:encoded><![CDATA[<p>Paragraph one.</p><p>Paragraph two.</p>]]></content:encoded></item>
</channel></rss>''';

    test('reads RSS', () {
      final feed = FeedParser.parse(utf8.encode(rss));
      expect(feed.title, 'חדשות ynet');
      expect(feed.items.length, 2);
      final a = feed.items[0];
      expect(a.guid, 'abc-1');
      expect(a.link, 'https://www.ynet.co.il/news/article/abc');
      expect(a.title, 'ראש הממשלה נפגש עם שר הביטחון');
      expect(a.date, utc('2026-11-01T08:00:00Z'));
      expect(a.summary, 'הפגישה נמשכה שעתיים.');
      final b = feed.items[1];
      expect(b.guid, b.link);
      expect(b.title, 'Second & last');
      expect(b.summary, 'Escaped HTML summary');
      expect(b.content, 'Paragraph one.\n\nParagraph two.');
    });

    test('reads Atom', () {
      const atom = '''<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom">
<title>Example blog</title><link rel="alternate" href="https://example.com/"/>
<entry><title type="html">Hello &lt;em&gt;world&lt;/em&gt;</title><link rel="alternate" href="https://example.com/hello"/>
<id>tag:example.com,2026:1</id><published>2026-10-31T21:15:00+02:00</published><summary>Short.</summary>
<content type="html">&lt;p&gt;Long text.&lt;/p&gt;</content></entry></feed>''';
      final feed = FeedParser.parse(utf8.encode(atom));
      expect(feed.title, 'Example blog');
      expect(feed.link, 'https://example.com/');
      final e = feed.items.single;
      expect(e.guid, 'tag:example.com,2026:1');
      expect(e.link, 'https://example.com/hello');
      expect(e.title, 'Hello world');
      expect(e.date, utc('2026-10-31T19:15:00Z'));
      expect(e.content, 'Long text.');
    });

    test('honours a windows-1255 declaration', () {
      // "שלום" and "כותרת" in windows-1255.
      final hebrew = (String s) => s.runes.map((c) => c - 0x05D0 + 0xE0).toList();
      final bytes = [
        ...latin1.encode('<?xml version="1.0" encoding="windows-1255"?><rss><channel><title>'),
        ...hebrew('שלום'),
        ...latin1.encode('</title><item><title>'),
        ...hebrew('כותרת'),
        ...latin1.encode('</title><link>https://x.co.il/1</link></item></channel></rss>'),
      ];
      final feed = FeedParser.parse(bytes);
      expect(feed.title, 'שלום');
      expect(feed.items.single.title, 'כותרת');
      expect(FeedParser.looksLikeFeed(bytes), isTrue);
      expect(FeedParser.looksLikeFeed(utf8.encode('<!DOCTYPE html><html><body>')), isFalse);
      expect(Charsets.decode([0xF9, 0xEC, 0xE5, 0xED], 'windows-1255'), 'שלום');
    });

    test('survives HTML entities XML does not know', () {
      final feed = FeedParser.parseString('<rss><channel><title>A&nbsp;B</title><item><title>x &copy; y</title><link>https://e.com/1</link></item></channel></rss>');
      expect(feed.items.single.link, 'https://e.com/1');
    });

    test('parses common date formats', () {
      final expected = utc('2026-11-01T08:00:00Z');
      expect(FeedParser.parseDate('Sun, 01 Nov 2026 10:00:00 +0200'), expected);
      expect(FeedParser.parseDate('Sun, 1 Nov 2026 08:00:00 GMT'), expected);
      expect(FeedParser.parseDate('2026-11-01T08:00:00Z'), expected);
      expect(FeedParser.parseDate('2026-11-01T10:00:00+02:00'), expected);
      expect(FeedParser.parseDate('yesterday'), isNull);
    });
  });

  group('articles', () {
    const para = 'זוהי פסקה ארוכה מספיק כדי להיחשב חלק מגוף הכתבה, עם כמה מילים נוספות לאורך.';

    test("prefers the publisher's articleBody", () {
      final body = List.generate(6, (i) => '$para ${i + 1}').join(r'\n');
      final page = '''<html><head><title>Site | Title</title><script type="application/ld+json">{"@context":"https://schema.org","@graph":[
{"@type":"WebPage","name":"x"},{"@type":["NewsArticle"],"headline":"הכותרת","description":"תקציר","articleBody":"$body"}]}</script>
</head><body><nav>תפריט</nav><p>Unrelated footer text that is long enough to count as a paragraph.</p></body></html>''';
      final a = ArticleExtractor.extract(html.parse(page));
      expect(a.title, 'הכותרת');
      expect(a.summaryOnly, isFalse);
      expect(a.text.split('\n\n').length, 6);
      expect(a.text, startsWith(para));
    });

    test('reader mode finds the main text', () {
      final paragraphs = List.generate(5, (i) => '<p>$para ${i + 1}</p>').join();
      final page = '''<html><head><meta property="og:title" content="Story title"></head><body>
<header><p>Header text that is quite long and should never be part of the article.</p></header>
<div class="sidebar related"><p>Related story with a long enough teaser sentence to count.</p></div>
<div class="story"><h1>Story title</h1>$paragraphs<p>short</p></div>
<footer><p>Copyright notice that is long enough to look like a paragraph here.</p></footer></body></html>''';
      final a = ArticleExtractor.extract(html.parse(page));
      expect(a.title, 'Story title');
      expect(a.summaryOnly, isFalse);
      expect(a.text.split('\n\n').length, 5);
      expect(a.text, isNot(anyOf(contains('Header'), contains('Related'), contains('Copyright'))));
    });

    test('paywalled articles are summaries', () {
      final page = '''<html><head><meta property="og:description" content="Teaser of the story.">
<script type="application/ld+json">{"@type":"NewsArticle","headline":"Premium","isAccessibleForFree":false,"articleBody":"Only the first lines."}</script></head>
<body><div><p>${para * 3}</p><p>${para * 3}</p></div></body></html>''';
      final a = ArticleExtractor.extract(html.parse(page));
      expect(a.summaryOnly, isTrue);
      expect(a.text, 'Only the first lines.');
      final speech = ArticleText.speech(a.title, a);
      expect(speech, startsWith('Premium.'));
      expect(speech, endsWith('This is only a summary.'));
    });

    test('pages without text fall back to the description', () {
      final a = ArticleExtractor.extract(html.parse('<html><head><meta name="description" content="What the page is about."></head><body><div id="app"></div></body></html>'));
      expect(a.summaryOnly, isTrue);
      expect(a.text, 'What the page is about.');
    });

    test('real news pages (set NEWS_FIXTURES)', () {
      final dir = Directory(Platform.environment['NEWS_FIXTURES'] ?? '');
      if (!dir.existsSync()) return;
      for (final f in dir.listSync().whereType<File>().where((f) => f.path.endsWith('.html')).toList()..sort((a, b) => a.path.compareTo(b.path))) {
        final a = ArticleExtractor.extract(html.parse(Charsets.decodeDocument(f.readAsBytesSync())));
        // ignore: avoid_print
        print('${f.uri.pathSegments.last}: ${a.title.length > 50 ? a.title.substring(0, 50) : a.title} | ${a.text.length} chars | summaryOnly=${a.summaryOnly}');
        expect(a.text.length, greaterThan(100), reason: f.path);
      }
    });
  });
}
