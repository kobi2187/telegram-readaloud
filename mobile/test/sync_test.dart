import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:tg_read_aloud/data/channel_repository.dart';
import 'package:tg_read_aloud/data/database.dart';
import 'package:tg_read_aloud/data/models.dart';
import 'package:tg_read_aloud/data/settings.dart';
import 'package:tg_read_aloud/sync/sync_engine.dart';
import 'package:tg_read_aloud/telegram/telegram_client.dart';
import 'package:tg_read_aloud/tts/audio_renderer.dart';
import 'package:tg_read_aloud/tts/azure_tts.dart';
import 'package:tg_read_aloud/tts/gemini_tts.dart';
import 'package:tg_read_aloud/web/web_client.dart';

import 'helpers.dart';

/// Serves t.me/s pages for in-memory channels, with t.me's paging links.
class FakeTelegram {
  final channels = <String, (String, Map<int, int>)>{};

  http.Response handle(Uri url) {
    final segments = url.pathSegments;
    if (segments.length != 2 || segments[0] != 's') return http.Response('', 404);
    final name = segments[1];
    final ch = channels[name];
    if (ch == null) return http.Response('', 302, headers: {'location': '/$name'});
    final ids = ch.$2.keys.toList()..sort();
    final before = int.tryParse(url.queryParameters['before'] ?? '');
    final after = int.tryParse(url.queryParameters['after'] ?? '');
    List<int> selected;
    if (before != null) {
      final older = ids.where((i) => i < before).toList();
      selected = older.sublist(max(0, older.length - 20));
    } else if (after != null) {
      selected = ids.where((i) => i > after).take(20).toList();
    } else {
      selected = ids.sublist(max(0, ids.length - 20));
    }
    final prev = selected.isNotEmpty && selected.first > ids.first ? selected.first : null;
    final next = selected.isNotEmpty && selected.last < ids.last ? selected.last : null;
    final b = StringBuffer('<html><head>');
    if (prev != null) b.write('<link rel="prev" href="/s/$name?before=$prev">');
    if (next != null) b.write('<link rel="next" href="/s/$name?after=$next">');
    b.write('</head><body><div class="tgme_channel_info_header_title"><span>${ch.$1}</span></div>');
    for (final id in selected) {
      final date = DateTime.fromMillisecondsSinceEpoch(ch.$2[id]!, isUtc: true).toIso8601String();
      b.write('<div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="$name/$id"><div class="tgme_widget_message_bubble">'
          '<div class="tgme_widget_message_text js-message_text">Post number $id</div><div class="tgme_widget_message_footer"><span class="tgme_widget_message_meta">'
          '<a class="tgme_widget_message_date"><time datetime="$date" class="time"></time></a></span></div></div></div></div>');
    }
    b.write('</body></html>');
    return http.Response.bytes(utf8.encode(b.toString()), 200, headers: {'content-type': 'text/html; charset=utf-8'});
  }
}

void main() {
  late AppDatabase db;
  late SyncEngine engine;
  late ChannelRepository repo;
  late RoutingClient http_;
  final telegram = FakeTelegram();
  const day = 24 * 3600 * 1000;
  const t0 = 1780000000000;
  final site = <String, http.Response Function()>{};

  setUp(() async {
    db = await memoryDb();
    telegram.channels.clear();
    site.clear();
    http_ = RoutingClient((request, body) async {
      final url = request.url;
      if (url.host == 't.me') return telegram.handle(url);
      final handler = site[url.path];
      return handler == null ? http.Response('', 404) : handler();
    });
    final settings = settingsOf(const AppSettings(cloudVoice: CloudVoice.none));
    final dir = tempDir();
    final renderer = AudioRenderer(
      root: dir, db: db, settings: settings, gemini: GeminiTts(http_), azure: AzureTts(http_), device: FakeDevice(), isOnline: (_) async => true,
    );
    final client = TelegramClient(http_);
    final web = WebClient(http_);
    engine = SyncEngine(db: db, telegram: client, renderer: renderer, settings: settings, isOnline: (_) async => true, avatarDir: dir, web: web, politeDelay: Duration.zero);
    repo = ChannelRepository(db, client, renderer, web);
  });

  tearDown(() => db.close());

  void busyChannel(String name) {
    final rnd = Random(42);
    final posts = <int, int>{};
    for (var id = 1; id <= 600; id++) {
      if (rnd.nextDouble() > 0.1) posts[id] = t0 + id * (day ~/ 10);
    }
    telegram.channels[name] = ('Busy $name', posts);
  }

  List<int> expectedIds(String name, int start) => (telegram.channels[name]!.$2.entries.where((e) => e.value >= start).map((e) => e.key).toList()..sort());
  Future<List<int>> storedIds(String name, int start) async => (await db.channelMessages(name, start)).map((m) => m.id).toList();

  group('telegram', () {
    test('first sync downloads exactly from the start date', () async {
      busyChannel('news');
      const start = t0 + 40 * day;
      expect(await repo.add('news', start), isA<Added>());
      await engine.sync(only: 'news');
      expect(await storedIds('news', start), expectedIds('news', start));
      final ch = (await db.channel('news'))!;
      expect(ch.title, 'Busy news');
      expect(ch.startId, expectedIds('news', start).first);
      expect(ch.fetchedTo, (telegram.channels['news']!.$2.keys.toList()..sort()).last);
      expect(ch.lastError, isNull);
    });

    test('later syncs only fetch what is new', () async {
      busyChannel('news');
      const start = t0 + 50 * day;
      await repo.add('news', start);
      await engine.sync(only: 'news');
      final posts = telegram.channels['news']!.$2;
      for (var id = 601; id <= 610; id++) {
        posts[id] = t0 + id * (day ~/ 10);
      }
      http_.requests.clear();
      await engine.sync(only: 'news');
      expect(await storedIds('news', start), expectedIds('news', start));
      expect(http_.requests.length, lessThanOrEqualTo(4));
    });

    test('moving the start earlier fills the gap', () async {
      busyChannel('news');
      await repo.add('news', t0 + 50 * day);
      await engine.sync(only: 'news');
      const earlier = t0 + 20 * day;
      await repo.setStartDate('news', earlier);
      await engine.sync(only: 'news');
      expect(await storedIds('news', earlier), expectedIds('news', earlier));
    });

    test('a channel without preview is reported', () async {
      expect(await repo.add('t.me/hidden', 0), AddProblem.noPreview);
      expect(await repo.add('not a channel!', 0), AddProblem.invalidName);
    });
  });

  group('websites', () {
    const para = 'This is a long enough paragraph of the article body to be counted as real text.';
    String rss(List<(String, String, int)> items) => '<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>Example News</title>${items.map((i) {
          final date = HttpDate.format(DateTime.fromMillisecondsSinceEpoch(t0 + i.$3 * 60000, isUtc: true));
          return '<item><title>${i.$2}</title><link>https://news.example.com/${i.$1}</link><guid>${i.$1}</guid><pubDate>$date</pubDate><description>Summary of ${i.$1}.</description></item>';
        }).join()}</channel></rss>';
    final items = <(String, String, int)>[];

    setUp(() {
      items
        ..clear()
        ..addAll([('old', "Yesterday's story", -24 * 60), ('a1', 'ראש הממשלה נפגש עם שר הביטחון', 10), ('a2', 'Markets open higher', 20), ('a3', 'Premium analysis', 30)]);
      site['/'] = () => http.Response('<html><head><title>Example</title><link rel="alternate" type="application/rss+xml" href="/news.rss"></head><body>Home</body></html>', 200);
      site['/news.rss'] = () => http.Response.bytes(utf8.encode(rss(items)), 200, headers: {'content-type': 'application/rss+xml'});
      site['/a1'] = () => http.Response.bytes(utf8.encode('<html><head><script type="application/ld+json">{"@type":"NewsArticle","headline":"x","articleBody":"${'$para ' * 3}\\n${'$para ' * 3}"}</script></head><body></body></html>'), 200);
      site['/a2'] = site['/a4'] = () => http.Response('<html><body><div class=story>${List.generate(5, (i) => '<p>$para ${i + 1}</p>').join()}</div></body></html>', 200);
      site['/a3'] = () => http.Response('', 403);
    });

    test('a website becomes a channel of articles', () async {
      final added = await repo.add('https://news.example.com/', t0) as Added;
      expect(added.title, 'Example News');
      final ch = (await db.channel(added.username))!;
      expect(ch.kind, ChannelKind.feed);
      expect(ch.url, 'https://news.example.com/news.rss');
      expect(await repo.add('https://news.example.com/news.rss', t0), AddProblem.alreadyExists);

      await engine.sync(only: added.username);
      final posts = await db.queue(added.username, 0, 0, 100);
      expect(posts.map((p) => p.guid), ['a1', 'a2', 'a3']);
      expect(posts.map((p) => p.id), [1, 2, 3]);
      expect(posts[0].speech, startsWith('ראש הממשלה נפגש עם שר הביטחון.'));
      expect(posts[0].speech, contains(para));
      expect(posts[1].text, startsWith('Markets open higher\n\n$para 1'));
      expect(posts[2].speech, endsWith('Summary of a3.\nThis is only a summary.'));

      http_.requests.clear();
      items.add(('a4', 'Evening update', 40));
      await engine.sync(only: added.username);
      expect((await db.queue(added.username, 0, 0, 100)).map((p) => p.guid), ['a1', 'a2', 'a3', 'a4']);
      expect(http_.requests, ['/news.rss', '/a4']);
    });

    test('shared links are saved, and fetched later when offline', () async {
      var up = false;
      site['/shared'] = () => up
          ? http.Response('<html><head><meta property="og:title" content="A shared story | Site"></head><body><article>${List.filled(4, '<p>$para</p>').join()}</article></body></html>', 200)
          : http.Response('', 503);
      const url = 'https://news.example.com/shared';
      expect((await repo.saveLink(url, 'Saved for listening') as Saved).title, isNull);
      expect(await repo.saveLink(url, 'Saved for listening'), isA<AlreadySaved>());
      expect((await db.channel(ChannelKind.savedId))!.kind, ChannelKind.saved);
      expect(await db.queue(ChannelKind.savedId, 0, 0, 10), isEmpty);
      up = true;
      await engine.sync(only: ChannelKind.savedId);
      final post = (await db.queue(ChannelKind.savedId, 0, 0, 10)).single;
      expect(post.text, startsWith('A shared story\n\n$para'));
      expect(post.link, url);
    });
  });
}
