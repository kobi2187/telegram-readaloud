import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/telegram/channel_history.dart';
import 'package:tg_read_aloud/telegram/channel_input.dart';
import 'package:tg_read_aloud/telegram/telegram_parser.dart';

String fixture(String name) => File('test/fixtures/telegram/$name').readAsStringSync();

/// A simulated channel. [windowed] mimics t.me answering with a window of ~20 ids (which can be
/// empty after mass deletions) instead of the 20 nearest existing posts.
class FakeChannel {
  FakeChannel(this.posts, this.windowed) : ids = posts.keys.toList()..sort();
  final Map<int, int> posts;
  final bool windowed;
  final List<int> ids;
  int requests = 0;

  TgMessage _msg(int id) => TgMessage(id: id, dateMillis: posts[id]!, text: 'p$id', speech: 'p$id', media: null, isService: false, edited: false, forwardedFrom: null);

  TgPage _page(List<int> selected) {
    final minId = selected.isEmpty ? null : selected.reduce(min);
    final maxId = selected.isEmpty ? null : selected.reduce(max);
    return TgPage(
      username: 'c', title: 'C', description: null, avatarUrl: null,
      messages: selected.map(_msg).toList(),
      prevBefore: minId != null && ids.first < minId ? minId : null,
      nextAfter: maxId != null && ids.last > maxId ? maxId : null,
    );
  }

  Future<TgPage> fetch(String username, {int? before, int? after}) async {
    requests++;
    List<int> selected;
    if (before != null && windowed) {
      selected = ids.where((i) => i >= before - 20 && i < before).toList();
    } else if (before != null) {
      final older = ids.where((i) => i < before).toList();
      selected = older.sublist(max(0, older.length - 20));
    } else if (after != null && windowed) {
      selected = ids.where((i) => i > after && i <= after + 20).toList();
    } else if (after != null) {
      selected = ids.where((i) => i > after).take(20).toList();
    } else {
      selected = ids.sublist(max(0, ids.length - 20));
    }
    return _page(selected);
  }
}

FakeChannel channel(int count, int seed, double deleteRate, bool windowed, {int? bigGapAt}) {
  final rnd = Random(seed);
  final posts = <int, int>{};
  var t = 1700000000000;
  for (var id = 1; id <= count; id++) {
    t += 1 + rnd.nextInt(3600000);
    final inGap = bigGapAt != null && id >= bigGapAt && id < bigGapAt + 75;
    if (!inGap && rnd.nextDouble() >= deleteRate) posts[id] = t;
  }
  return FakeChannel(posts, windowed);
}

int expectedStart(FakeChannel ch, int start) =>
    (ch.ids.where((id) => ch.posts[id]! >= start).firstOrNull) ?? (ch.ids.last + 1);

Future<void> check(FakeChannel ch, int start) async {
  final history = ChannelHistory(ch.fetch, politeDelay: Duration.zero);
  final latest = await ch.fetch('c');
  expect(await history.locateStart('c', start, latest), expectedStart(ch, start), reason: 'start=$start');
}

void main() {
  group('parser', () {
    test('parses a channel page and its paging links', () {
      final page = TelegramParser.parse(fixture('kann_news_latest.html'), 'kann_news');
      expect(page.title, 'חדשות קאן');
      expect(page.avatarUrl, isNotNull);
      expect(page.messages.length, 19);
      expect(page.minId, 3769);
      expect(page.maxId, 3788);
      expect(page.prevBefore, 3769);
      expect(page.nextAfter, isNull);
    });

    test('keeps links on screen but not in speech', () {
      final m = TelegramParser.parse(fixture('kann_news_latest.html'), 'kann_news').messages.firstWhere((m) => m.id == 3786);
      expect(m.dateMillis, DateTime.parse('2026-03-24T15:26:58Z').millisecondsSinceEpoch);
      expect(m.text, startsWith('הדיל הסודי של טראמפ'));
      expect(m.text, contains('youtube.com'));
      expect(m.speech, startsWith('הדיל הסודי של טראמפ'));
      expect(m.speech, isNot(contains('youtube')));
      expect(m.speech, isNot(contains('http')));
    });

    test('a photo without caption has nothing to read', () {
      final m = TelegramParser.parse(fixture('kann_news_latest.html'), 'kann_news').messages.firstWhere((m) => m.id == 3788);
      expect(m.media, 'photo');
      expect(m.speech, '');
    });

    test('ignores the quoted reply and detects edits', () {
      final page = TelegramParser.parse(fixture('abualiexpress_latest.html'), 'abualiexpress');
      final m = page.messages.firstWhere((m) => m.id == 132689);
      expect(m.text, startsWith('חצי שעה לסגירת הקלפיות.'));
      expect(m.text, isNot(contains('הציר השיעי')));
      expect(m.edited, isTrue);
      expect(page.messages.firstWhere((m) => m.id == 132688).media, 'video');
    });

    test('strips emoji from speech', () {
      final page = TelegramParser.parse(fixture('durov_latest.html'), 'durov');
      final designers = page.messages.firstWhere((m) => m.id == 552);
      expect(designers.text, contains('✨'));
      expect(designers.speech, isNot(contains('✨')));
      expect(designers.speech, startsWith('Designers × AI'));
      expect(designers.media, 'video');
      expect(page.messages.firstWhere((m) => m.id == 541).speech, startsWith('13 Telegram turns 13 today'));
      expect(page.messages.firstWhere((m) => m.id == 536).speech, '');
    });

    test('marks service messages', () {
      final page = TelegramParser.parse(fixture('kann_news_before_20.html'), 'kann_news');
      final created = page.messages.firstWhere((m) => m.id == 1);
      expect(created.isService, isTrue);
      expect(created.speech, '');
      expect(page.nextAfter, 18);
      expect(page.messages.firstWhere((m) => m.id == 4).isService, isFalse);
    });

    test('a link-only post reads the preview title', () {
      const html = '''<div class="tgme_widget_message" data-post="x/5"><div class="tgme_widget_message_bubble">
<div class="tgme_widget_message_text js-message_text"><a href="https://a.co/b">https://a.co/b</a></div>
<a class="tgme_widget_message_link_preview"><div class="link_preview_title">Big news today</div></a>
<a class="tgme_widget_message_date"><time datetime="2026-01-01T10:00:00+00:00"></time></a></div></div>''';
      expect(TelegramParser.parse(html, 'x').messages.single.speech, 'Big news today.');
    });

    test('reads polls', () {
      const html = '''<div class="tgme_widget_message" data-post="x/7"><div class="tgme_widget_message_bubble">
<div class="tgme_widget_message_poll"><div class="tgme_widget_message_poll_question">Who will win?</div>
<div class="tgme_widget_message_poll_option_text">Alice</div><div class="tgme_widget_message_poll_option_text">Bob</div></div>
<a class="tgme_widget_message_date"><time datetime="2026-01-01T10:00:00+00:00"></time></a></div></div>''';
      final m = TelegramParser.parse(html, 'x').messages.single;
      expect(m.media, 'poll');
      expect(m.speech, 'Who will win?\nAlice.\nBob.');
    });
  });

  group('input', () {
    test('accepts common forms', () {
      expect(ChannelInput.parse('kann_news'), 'kann_news');
      expect(ChannelInput.parse('@Kann_News'), 'kann_news');
      expect(ChannelInput.parse('https://t.me/kann_news'), 'kann_news');
      expect(ChannelInput.parse('t.me/s/kann_news'), 'kann_news');
      expect(ChannelInput.parse('https://t.me/kann_news/3786'), 'kann_news');
      expect(ChannelInput.parse('Check this out: https://t.me/durov/552 cool'), 'durov');
    });

    test('rejects invalid input', () {
      expect(ChannelInput.parse('ab'), isNull);
      expect(ChannelInput.parse('https://t.me/joinchat/AAAA'), isNull);
      expect(ChannelInput.parse('hello world'), isNull);
      expect(ChannelInput.parse(''), isNull);
    });
  });

  group('history', () {
    test('finds the start date exactly for many channels', () async {
      for (var seed = 1; seed <= 15; seed++) {
        for (final windowed in [false, true]) {
          final ch = channel(3000, seed, 0.15, windowed);
          final times = ch.posts.values.toList()..sort();
          final rnd = Random(seed * 7);
          for (var i = 0; i < 4; i++) {
            await check(ch, times[rnd.nextInt(times.length)]);
          }
          await check(ch, times.first - 1);
          await check(ch, times.last + 1);
          await check(ch, times[times.length - 3]);
        }
      }
    });

    test('survives long stretches of deleted posts', () async {
      for (var seed = 1; seed <= 10; seed++) {
        final ch = channel(2000, seed, 0.05, true, bigGapAt: 900);
        final beforeGap = ch.posts[ch.ids.lastWhere((id) => id < 900)]!;
        final afterGap = ch.posts[ch.ids.firstWhere((id) => id >= 975)]!;
        await check(ch, beforeGap);
        await check(ch, afterGap);
        await check(ch, beforeGap + 1);
      }
    });

    test('uses few requests', () async {
      final ch = channel(200000, 3, 0.1, false);
      final times = ch.posts.values.toList()..sort();
      final latest = await ch.fetch('c');
      ch.requests = 0;
      await ChannelHistory(ch.fetch, politeDelay: Duration.zero).locateStart('c', times[times.length ~/ 3], latest);
      expect(ch.requests, lessThanOrEqualTo(20));
    });

    test('forward covers everything without gaps', () async {
      for (final windowed in [false, true]) {
        final ch = channel(1500, 11, 0.2, windowed, bigGapAt: 600);
        final seen = <int>{};
        final reached = await ChannelHistory(ch.fetch, politeDelay: Duration.zero)
            .forward('c', 99, ch.ids.last, onPage: (p) async => seen.addAll(p.messages.map((m) => m.id)));
        expect(seen, ch.ids.where((id) => id >= 100).toSet());
        expect(reached, greaterThanOrEqualTo(ch.ids.last));
      }
    });
  });
}
