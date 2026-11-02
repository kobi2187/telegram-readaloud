import 'dart:async';

import 'package:flutter/foundation.dart';

import '../app_graph.dart';
import '../data/models.dart';

/// What the screens show: the channel list, refreshed whenever the data changes, and periodic sync.
class AppState extends ChangeNotifier {
  AppState(this.graph) {
    _sub = graph.db.changes.listen((_) => _scheduleReload());
    graph.settings.addListener(_reschedule);
    _reload();
    _reschedule();
    syncNow();
  }

  final AppGraph graph;
  late final StreamSubscription<void> _sub;
  List<ChannelSummary> channels = const [];
  Timer? _reloadTimer;
  Timer? _syncTimer;

  void _scheduleReload() {
    _reloadTimer?.cancel();
    _reloadTimer = Timer(const Duration(milliseconds: 150), _reload);
  }

  Future<void> _reload() async {
    channels = await graph.db.summaries();
    notifyListeners();
  }

  /// Syncs every few minutes while the app runs (in the background too, while it plays).
  void _reschedule() {
    _syncTimer?.cancel();
    final minutes = graph.settings.value.syncIntervalMinutes;
    if (minutes > 0) _syncTimer = Timer.periodic(Duration(minutes: minutes), (_) => syncNow());
  }

  void syncNow({String? only}) => unawaited(graph.sync.sync(only: only));

  @override
  void dispose() {
    _sub.cancel();
    _reloadTimer?.cancel();
    _syncTimer?.cancel();
    super.dispose();
  }
}
