import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:workmanager/workmanager.dart';

import '../app_graph.dart';
import '../data/settings.dart';

/// Syncing while the app is closed: Android's WorkManager (with the network constraint from the
/// settings), iOS background app refresh (whose timing iOS decides, and which gives a task about
/// half a minute). The identifier matches BGTaskSchedulerPermittedIdentifiers in Info.plist.
abstract final class BackgroundSync {
  static const task = 'io.github.kobi2187.readaloud.sync';

  /// (Re)schedules the periodic sync for [s]; none when automatic sync is off.
  static Future<void> schedule(AppSettings s) async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    try {
      await Workmanager().initialize(backgroundSyncMain);
      if (s.syncIntervalMinutes <= 0) {
        await Workmanager().cancelByUniqueName(task);
        return;
      }
      await Workmanager().registerPeriodicTask(
        task,
        task,
        frequency: Duration(minutes: max(15, s.syncIntervalMinutes)),
        constraints: Constraints(
          networkType: s.allowMetered ? NetworkType.connected : NetworkType.unmetered,
          requiresBatteryNotLow: true,
        ),
        existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
      );
    } catch (e) {
      debugPrint('Background sync not scheduled: $e');
    }
  }
}

/// The background task's entry point (runs in its own isolate, without the UI).
@pragma('vm:entry-point')
void backgroundSyncMain() {
  Workmanager().executeTask((task, _) async {
    WidgetsFlutterBinding.ensureInitialized();
    final graph = await AppGraph.core();
    final deadline = DateTime.now().add(Platform.isIOS ? const Duration(seconds: 25) : const Duration(minutes: 8));
    try {
      await graph.sync.sync(deadline: deadline);
      return true;
    } catch (e) {
      debugPrint('Background sync failed: $e');
      return false;
    } finally {
      await graph.db.close();
      graph.httpClient.close();
    }
  });
}
