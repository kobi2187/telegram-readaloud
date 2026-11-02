import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../data/database.dart';
import '../data/models.dart';
import '../data/settings.dart';
import '../telegram/channel_history.dart';
import '../telegram/telegram_client.dart';
import '../telegram/telegram_parser.dart';
import '../tts/audio_renderer.dart';
import '../web/article_extractor.dart';
import '../web/web_client.dart';

enum SyncPhase { idle, text, audio }

@immutable
class SyncState {
  const SyncState({this.running = false, this.phase = SyncPhase.idle, this.channel, this.audioDone = 0, this.audioTotal = 0, this.lastFinishedAt = 0, this.offline = false});
  final bool running;
  final SyncPhase phase;
  final String? channel;
  final int audioDone;
  final int audioTotal;
  final int lastFinishedAt;
  final bool offline;

  SyncState copyWith({bool? running, SyncPhase? phase, Object? channel = _absent, int? audioDone, int? audioTotal, int? lastFinishedAt, bool? offline}) => SyncState(
        running: running ?? this.running,
        phase: phase ?? this.phase,
        channel: identical(channel, _absent) ? this.channel : channel as String?,
        audioDone: audioDone ?? this.audioDone,
        audioTotal: audioTotal ?? this.audioTotal,
        lastFinishedAt: lastFinishedAt ?? this.lastFinishedAt,
        offline: offline ?? this.offline,
      );
  static const _absent = Object();
}

/// Downloads posts and their online-voice audio. Built for sporadic connectivity: every page and
/// every audio file is committed as soon as it arrives, so an interrupted sync resumes where it
/// stopped, and nothing here is required for playback, which works fully offline.
class SyncEngine {
  SyncEngine({
    required this.db,
    required this.telegram,
    required this.renderer,
    required this.settings,
    required this.isOnline,
    required this.avatarDir,
    required this.web,
    Duration politeDelay = const Duration(milliseconds: 250),
  }) : _history = ChannelHistory(telegram.fetchPage, politeDelay: politeDelay);

  final AppDatabase db;
  final TelegramClient telegram;
  final AudioRenderer renderer;
  final ValueListenable<AppSettings> settings;
  final Future<bool> Function(bool allowMetered) isOnline;
  final Directory avatarDir;
  final WebClient web;
  final ChannelHistory _history;

  final state = ValueNotifier(const SyncState());
  bool _busy = false;

  static const errorNoPreview = 'no_preview';
  static const errorRateLimit = 'rate_limited';
  static const errorNetwork = 'network';
  static const errorSiteRefused = 'site_refused';
  static const _batch = 10;
  static const _maxConsecutiveFailures = 4;
  static const _keepPlayedAudio = 3;
  static const _maxArticlesPerSync = 40;
  static const _avatarMaxAge = Duration(days: 7);

  /// Runs one sync pass; false if another pass is already running. [deadline] bounds the run.
  Future<bool> sync({DateTime? deadline, String? only}) async {
    if (_busy) return false;
    _busy = true;
    bool passed() => deadline != null && DateTime.now().isAfter(deadline);
    try {
      final cfg = settings.value;
      if (!await isOnline(cfg.allowMetered)) {
        state.value = state.value.copyWith(offline: true);
        return true;
      }
      state.value = SyncState(running: true, phase: SyncPhase.text, lastFinishedAt: state.value.lastFinishedAt);
      final list = only != null ? [await db.channel(only)].whereType<Channel>().toList() : await db.enabledChannels();
      list.sort((a, b) => (a.username == cfg.currentChannel ? 0 : 1).compareTo(b.username == cfg.currentChannel ? 0 : 1));
      await _syncText(list, passed);
      if (renderer.cloudReady(cfg) && !passed()) await _prefetchAudio(cfg, passed);
      await _cleanup(cfg);
      return true;
    } finally {
      state.value = SyncState(lastFinishedAt: DateTime.now().millisecondsSinceEpoch, offline: !await isOnline(true));
      _busy = false;
    }
  }

  Future<void> _syncText(List<Channel> list, bool Function() passed) async {
    for (final ch in list) {
      if (passed()) break;
      state.value = state.value.copyWith(channel: ch.username);
      final now = DateTime.now().millisecondsSinceEpoch;
      try {
        switch (ch.kind) {
          case ChannelKind.feed:
            await _syncFeed(ch, passed);
          case ChannelKind.saved:
            await _fetchSavedLinks(ch, passed);
          default:
            await _syncChannel(ch, passed);
        }
        await db.setSyncResult(ch.username, null, now);
      } on ChannelUnavailableException {
        await db.setSyncResult(ch.username, errorNoPreview, now);
      } on SiteRefusedException {
        await db.setSyncResult(ch.username, errorSiteRefused, now);
      } on RateLimitedException {
        await db.setSyncResult(ch.username, errorRateLimit, now);
        break;
      } catch (e) {
        debugPrint('Sync of ${ch.username} failed: $e');
        await db.setSyncResult(ch.username, errorNetwork, now);
        if (!await isOnline(true)) break;
      }
    }
  }

  Future<void> _syncChannel(Channel original, bool Function() passed) async {
    final latest = await telegram.fetchPage(original.username);
    var c = original.copyWith(
      title: latest.title ?? original.title,
      description: latest.description ?? original.description,
      latestId: max(original.latestId, latest.maxId ?? 0),
    );
    c = await _refreshAvatar(c, latest.avatarUrl);
    await _store(c, latest.messages);
    await db.updateChannel(c);

    if (c.startId == null) {
      final startId = await _history.locateStart(c.username, c.startDate, latest, keepGoing: () => !passed());
      if (c.fetchedFrom == 0 || startId > c.fetchedTo + 1) {
        c = c.copyWith(startId: startId, fetchedFrom: startId, fetchedTo: startId - 1);
      } else if (startId >= c.fetchedFrom) {
        c = c.copyWith(startId: startId);
      } else {
        // The start moved earlier than what we have: fill the hole first.
        final reached = await _forward(c, startId - 1, c.fetchedFrom - 1, passed, null);
        c = reached >= c.fetchedFrom - 1
            ? c.copyWith(startId: startId, fetchedFrom: startId)
            : c.copyWith(startId: startId, fetchedFrom: startId, fetchedTo: reached);
      }
      await db.updateChannel(c);
    }

    final latestMin = latest.minId;
    if (latestMin != null && latestMin <= c.fetchedTo + 1) {
      c = c.copyWith(fetchedTo: max(c.fetchedTo, latest.maxId ?? c.fetchedTo));
    } else {
      var progress = c;
      final reached = await _forward(c, c.fetchedTo, c.latestId, passed, (at) async {
        progress = progress.copyWith(fetchedTo: at);
        await db.updateChannel(progress);
      });
      c = progress.copyWith(fetchedTo: max(progress.fetchedTo, reached));
    }
    await db.updateChannel(c);
  }

  Future<int> _forward(Channel c, int afterId, int stopAt, bool Function() passed, Future<void> Function(int)? onProgress) =>
      _history.forward(c.username, afterId, stopAt, keepGoing: () => !passed(), onPage: (page) => _store(c, page.messages), onProgress: onProgress);

  Future<void> _store(Channel c, List<TgMessage> page) => db.upsertFromWeb(page
      .where((m) => !m.isService && m.dateMillis >= c.startDate)
      .map((m) => Message(channel: c.username, id: m.id, date: m.dateMillis, text: m.text, speech: m.speech, media: m.media, edited: m.edited, forwardedFrom: m.forwardedFrom))
      .toList());

  Future<Channel> _refreshAvatar(Channel c, String? url) async {
    if (url == null) return c;
    final file = File('${avatarDir.path}/${c.username}.jpg');
    final stale = !await file.exists() || DateTime.now().difference(await file.lastModified()) > _avatarMaxAge;
    if (!stale && c.avatarPath == file.path) return c;
    try {
      await telegram.download(url, file);
      return c.copyWith(avatarUrl: url, avatarPath: file.path);
    } catch (_) {
      return c;
    }
  }

  /// New articles of a website: each new feed item since the start date becomes a post, with the
  /// article text read from its page (or the feed's summary when the page cannot be read).
  Future<void> _syncFeed(Channel c, bool Function() passed) async {
    final url = c.url;
    if (url == null) return;
    final feed = await web.fetchFeed(url);
    final known = (await db.guids(c.username)).toSet();
    final now = DateTime.now().millisecondsSinceEpoch;
    final fresh = feed.items.where((i) => !known.contains(i.guid) && (i.date ?? now) >= c.startDate).toList()
      ..sort((a, b) => (a.date ?? now).compareTo(b.date ?? now));
    final batch = fresh.length > _maxArticlesPerSync ? fresh.sublist(fresh.length - _maxArticlesPerSync) : fresh;
    var nextId = await db.maxId(c.username) + 1;
    for (final item in batch) {
      if (passed()) break;
      Article? article = item.content.length >= 600 ? Article(item.title, item.content, summaryOnly: false) : null;
      if (article == null && item.link != null) {
        try {
          article = await web.fetchArticle(item.link!);
        } catch (e) {
          debugPrint('Article ${item.link} unreadable: $e');
        }
      }
      article ??= Article(item.title, item.summary, summaryOnly: true);
      final title = item.title.trim().isEmpty ? article.title : item.title;
      final body = article.summaryOnly && article.text.length < item.summary.length ? article.copyWith(text: item.summary) : article;
      await db.insertMessages([
        Message(
          channel: c.username,
          id: nextId++,
          date: item.date ?? now,
          text: ArticleText.display(title, body),
          speech: ArticleText.speech(title, body),
          link: item.link,
          guid: item.guid,
        ),
      ]);
    }
  }

  /// Links saved while offline get their article text.
  Future<void> _fetchSavedLinks(Channel c, bool Function() passed) async {
    for (final m in await db.pendingLinks(c.username)) {
      if (passed()) break;
      final link = m.link;
      if (link == null) continue;
      final article = await web.fetchArticle(link);
      await db.setArticle(c.username, m.id, ArticleText.display(article.title, article), ArticleText.speech(article.title, article));
    }
  }

  /// Downloads online-voice audio for the next posts of every channel, round-robin in small
  /// batches so each channel gets some coverage before the connection drops.
  Future<void> _prefetchAudio(AppSettings cfg, bool Function() passed) async {
    final list = await db.enabledChannels();
    list.sort((a, b) => (a.username == cfg.currentChannel ? 0 : 1).compareTo(b.username == cfg.currentChannel ? 0 : 1));
    final queues = <List<Message>>[];
    for (final ch in list) {
      final q = <Message>[];
      for (final m in await db.queue(ch.username, ch.cursorId, ch.startDate, cfg.prefetchCount)) {
        if (await renderer.needsNeural(m, cfg)) q.add(m);
      }
      queues.add(q);
    }
    final ordered = <Message>[];
    for (var round = 0;; round++) {
      var added = false;
      for (final q in queues) {
        final batch = q.skip(round * _batch).take(_batch);
        if (batch.isNotEmpty) {
          ordered.addAll(batch);
          added = true;
        }
      }
      if (!added) break;
    }
    state.value = state.value.copyWith(phase: SyncPhase.audio, audioDone: 0, audioTotal: ordered.length, channel: null);
    for (final ch in list) {
      try {
        await renderer.ensureClip('ch_${ch.username}', ch.title);
      } catch (_) {}
    }
    var failures = 0, done = 0;
    for (final m in ordered) {
      if (passed() || failures >= _maxConsecutiveFailures || !await isOnline(cfg.allowMetered)) break;
      switch (await renderer.prefetchNeural(m, cfg)) {
        case Prefetch.done:
          failures = 0;
          done++;
          state.value = state.value.copyWith(audioDone: done, channel: m.channel);
        case Prefetch.networkFailed:
          failures++;
        case Prefetch.cloudUnavailable:
          failures = _maxConsecutiveFailures; // bad key or quota: the phone's voices cover playback
      }
    }
  }

  Future<void> _cleanup(AppSettings cfg) async {
    if (cfg.deletePlayedAudio) {
      for (final ch in await db.allChannels()) {
        for (final m in await db.playedWithAudio(ch.username, ch.cursorId, _keepPlayedAudio)) {
          final path = m.audioPath;
          if (path != null) {
            try {
              await File(path).delete();
            } catch (_) {}
          }
          await db.setAudio(m.channel, m.id, m.audioState == AudioState.neuralFailed ? AudioState.neuralFailed : AudioState.none, null, null);
        }
      }
    }
    await renderer.deleteOrphans((await db.allAudioPaths()).toSet());
  }
}
