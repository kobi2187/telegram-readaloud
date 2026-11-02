import 'dart:io';

import '../telegram/channel_input.dart';
import '../telegram/telegram_client.dart';
import '../tts/audio_renderer.dart';
import '../web/article_extractor.dart';
import '../web/web_client.dart';
import 'database.dart';
import 'models.dart';

sealed class AddResult {
  const AddResult();
}

class Added extends AddResult {
  const Added(this.username, this.title, {required this.verified});
  final String username;
  final String title;
  final bool verified;
}

enum AddProblem implements AddResult { invalidName, alreadyExists, noPreview, noFeed, siteRefused, offline }

sealed class SaveResult {
  const SaveResult();
}

class Saved extends SaveResult {
  const Saved(this.title);

  /// Null when the article will be fetched at the next sync.
  final String? title;
}

class AlreadySaved extends SaveResult {
  const AlreadySaved();
}

/// Channel operations used by the UI.
class ChannelRepository {
  ChannelRepository(this.db, this.telegram, this.renderer, this.web);

  final AppDatabase db;
  final TelegramClient telegram;
  final AudioRenderer renderer;
  final WebClient web;

  /// A stable, file-name-safe id for a website channel.
  static String websiteId(String feedUrl) {
    // FNV-1a, stable across runs (Dart's String.hashCode is not).
    var h = 0x811c9dc5;
    for (final c in feedUrl.toLowerCase().replaceAll(RegExp(r'/+$'), '').codeUnits) {
      h ^= c;
      h = (h * 0x01000193) & 0xffffffff;
    }
    return 'web_${h.toRadixString(16)}';
  }

  /// Adds a Telegram channel ("@name", t.me link) or a website (an address with a dot). Telegram
  /// channels can be added offline (checked at the next sync); websites need a connection.
  Future<AddResult> add(String input, int startDate) async {
    // Telegram usernames cannot contain a dot, so anything else with one is a website.
    final username = ChannelInput.parse(input);
    if (username == null) {
      return input.contains('.') || input.trim().startsWith('http') ? _addWebsite(input, startDate) : AddProblem.invalidName;
    }
    if (await db.channel(username) != null) return AddProblem.alreadyExists;
    var title = username;
    var verified = false;
    try {
      final page = await telegram.fetchPage(username);
      title = page.title ?? username;
      verified = true;
    } on ChannelUnavailableException {
      return AddProblem.noPreview;
    } on IOException {
      // Offline: keep it and let the next sync fill in the details.
    }
    await db.insertChannel(Channel(
      username: username,
      title: title,
      startDate: startDate,
      sortOrder: await db.nextSortOrder(),
      addedAt: DateTime.now().millisecondsSinceEpoch,
    ));
    return Added(username, title, verified: verified);
  }

  Future<AddResult> _addWebsite(String input, int startDate) async {
    DiscoveredFeed found;
    try {
      found = await web.discover(input);
    } on NoFeedException {
      return AddProblem.noFeed;
    } on SiteRefusedException {
      return AddProblem.siteRefused;
    } catch (_) {
      return AddProblem.offline;
    }
    final id = websiteId(found.url);
    if (await db.channel(id) != null) return AddProblem.alreadyExists;
    final title = (found.title?.trim().isNotEmpty ?? false) ? found.title!.trim() : (WebClient.normalize(input)?.host ?? input);
    await db.insertChannel(Channel(
      username: id,
      title: title,
      startDate: startDate,
      sortOrder: await db.nextSortOrder(),
      addedAt: DateTime.now().millisecondsSinceEpoch,
      kind: ChannelKind.feed,
      url: found.url,
    ));
    return Added(id, title, verified: true);
  }

  /// Saves a shared link to the "saved" channel and reads the article now if online; offline, the
  /// next sync fetches it. [savedTitle] names the channel the first time.
  Future<SaveResult> saveLink(String url, String savedTitle) async {
    if (await db.channel(ChannelKind.savedId) == null) {
      await db.insertChannel(Channel(
        username: ChannelKind.savedId,
        title: savedTitle,
        startDate: 0,
        sortOrder: await db.firstSortOrder(),
        addedAt: DateTime.now().millisecondsSinceEpoch,
        kind: ChannelKind.saved,
      ));
    }
    if ((await db.guids(ChannelKind.savedId)).contains(url)) return const AlreadySaved();
    final id = await db.maxId(ChannelKind.savedId) + 1;
    await db.insertMessages([
      Message(channel: ChannelKind.savedId, id: id, date: DateTime.now().millisecondsSinceEpoch, text: url, speech: '', link: url, guid: url),
    ]);
    try {
      final article = await web.fetchArticle(url);
      await db.setArticle(ChannelKind.savedId, id, ArticleText.display(article.title, article), ArticleText.speech(article.title, article));
      return Saved(article.title);
    } catch (_) {
      return const Saved(null);
    }
  }

  Future<void> remove(String username) async {
    final c = await db.channel(username);
    final avatar = c?.avatarPath;
    if (avatar != null) {
      try {
        await File(avatar).delete();
      } catch (_) {}
    }
    await db.deleteChannel(username);
    await renderer.deleteChannelAudio(username);
  }

  /// Changes where reading starts, and restarts the channel from that date.
  Future<void> setStartDate(String username, int startDate) async {
    final c = await db.channel(username);
    if (c == null) return;
    await db.updateChannel(c.copyWith(startDate: startDate, startId: null, cursorId: 0, cursorPositionMs: 0));
    await db.markAllUnplayed(username);
  }

  Future<void> markAllHeard(String username) async {
    final c = await db.channel(username);
    if (c == null) return;
    final end = [c.fetchedTo, c.latestId, await db.maxId(username)].reduce((a, b) => a > b ? a : b) + 1;
    await db.setCursor(username, end, 0);
    await db.markPlayedBefore(username, end);
  }

  Future<void> restart(String username) async {
    await db.setCursor(username, 0, 0);
    await db.markAllUnplayed(username);
  }

  Future<void> setEnabled(String username, bool enabled) async {
    final c = await db.channel(username);
    if (c != null) await db.updateChannel(c.copyWith(enabled: enabled));
  }

  Future<void> move(String username, int delta) async {
    final list = await db.allChannels();
    final i = list.indexWhere((c) => c.username == username);
    final j = i + delta;
    if (i < 0 || j < 0 || j >= list.length) return;
    final item = list.removeAt(i);
    list.insert(j, item);
    for (var k = 0; k < list.length; k++) {
      await db.setSortOrder(list[k].username, k);
    }
  }
}
