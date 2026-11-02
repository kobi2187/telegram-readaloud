import 'telegram_parser.dart';

/// Anything that serves t.me/s pages; the real one is TelegramClient.
typedef PageSource = Future<TgPage> Function(String username, {int? before, int? after});

/// Navigation over a channel's history using only the web preview's paging: finds the first post
/// at a date (binary search over post ids, which grow with time) and pages forward from an id.
class ChannelHistory {
  ChannelHistory(this._pages, {this.politeDelay = const Duration(milliseconds: 250)});

  final PageSource _pages;
  final Duration politeDelay;

  static const maxProbes = 60;
  static const maxGapHops = 5;
  static const maxPages = 250;
  static const emptyStretchStep = 20;

  /// The id of the first post published at or after [startDate], or one past the newest post when
  /// there is none yet. [latest] is the channel's newest page.
  Future<int> locateStart(String username, int startDate, TgPage latest, {bool Function()? keepGoing}) async {
    final onPage = latest.messages;
    if (onPage.isEmpty) return (latest.maxId ?? 0) + 1;
    if (onPage.first.dateMillis < startDate) {
      return onPage.where((m) => m.dateMillis >= startDate).firstOrNull?.id ?? (onPage.last.id + 1);
    }
    var lo = 0; // every post with id <= lo is older than the start date
    var hi = onPage.first.id; // hi is at or after the start date
    var probes = 0;
    while (hi - lo > 1 && probes < maxProbes && (keepGoing?.call() ?? true)) {
      final mid = lo + (hi - lo) ~/ 2;
      var probe = mid;
      var ms = <TgMessage>[];
      for (var hop = 0; hop < maxGapHops; hop++) {
        final page = await _pages(username, before: probe + 1);
        probes++;
        await Future<void>.delayed(politeDelay);
        ms = page.messages.where((m) => m.id <= mid).toList();
        if (ms.isNotEmpty) break;
        // An empty window means a stretch of deleted posts: look further back.
        final prev = page.prevBefore;
        probe = (prev != null && prev - 1 < probe) ? prev - 1 : probe - emptyStretchStep;
        if (probe <= lo) break;
      }
      if (ms.isEmpty || ms.last.dateMillis < startDate) {
        lo = mid;
      } else if (ms.first.dateMillis >= startDate) {
        hi = ms.first.id;
      } else {
        return ms.firstWhere((m) => m.dateMillis >= startDate).id;
      }
    }
    return hi;
  }

  /// Pages forward from [afterId] until [stopAt], handing every page to [onPage] and reporting
  /// the highest id covered so far to [onProgress]. Returns the highest id covered.
  Future<int> forward(
    String username,
    int afterId,
    int stopAt, {
    int maxPageCount = maxPages,
    bool Function()? keepGoing,
    required Future<void> Function(TgPage) onPage,
    Future<void> Function(int)? onProgress,
  }) async {
    var cursor = afterId;
    var count = 0;
    while (cursor < stopAt && count < maxPageCount && (keepGoing?.call() ?? true)) {
      final page = await _pages(username, after: cursor);
      count++;
      await onPage(page);
      final maxId = page.maxId;
      final next = page.nextAfter;
      if (maxId != null && maxId > cursor) {
        cursor = maxId;
      } else if (next != null && next > cursor) {
        cursor = next;
      } else {
        cursor += emptyStretchStep; // a run of deleted posts
      }
      await onProgress?.call(cursor);
      await Future<void>.delayed(politeDelay);
    }
    return cursor;
  }
}
