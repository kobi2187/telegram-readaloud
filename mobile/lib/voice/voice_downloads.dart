import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';

import 'offline_voices.dart';

/// Where a voice's download stands, for the settings screen.
sealed class DownloadStatus {
  const DownloadStatus();
}

class NotDownloading extends DownloadStatus {
  const NotDownloading();
}

class Downloading extends DownloadStatus {
  const Downloading(this.done, this.total);
  final int done;
  final int total;
}

/// Waiting for a network: [wifiOnly] when only mobile data is available and it was not allowed.
class WaitingForNetwork extends DownloadStatus {
  const WaitingForNetwork({required this.wifiOnly});
  final bool wifiOnly;
}

enum FailReason { failed, noSpace, unsupported }

class DownloadFailed extends DownloadStatus {
  const DownloadFailed(this.reason, {this.neededBytes = 0});
  final FailReason reason;
  final int neededBytes;
}

/// Starts voice downloads when the network allows it (Wi-Fi, or mobile data when asked), waits
/// for a connection when there is none, and picks up again when one comes back.
class VoiceDownloads extends ChangeNotifier {
  VoiceDownloads(this.voices, {Connectivity? connectivity}) : _connectivity = connectivity ?? Connectivity() {
    voices.state.addListener(notifyListeners);
  }

  final OfflineVoices voices;
  final Connectivity _connectivity;
  final _status = <String, DownloadStatus>{};
  final _mobileAllowed = <String>{};
  StreamSubscription<List<ConnectivityResult>>? _watch;

  DownloadStatus statusOf(String id) {
    final progress = voices.state.value.downloads[id];
    if (progress != null) return Downloading(progress.$1, progress.$2);
    return _status[id] ?? const NotDownloading();
  }

  /// Downloads [id] now, or as soon as the network allows. [mobileData] allows a metered network.
  Future<void> download(String id, {bool mobileData = false}) async {
    if (mobileData) _mobileAllowed.add(id);
    _status[id] = const WaitingForNetwork(wifiOnly: false);
    notifyListeners();
    await _tryStart(id);
  }

  void cancel(String id) {
    _status.remove(id);
    _mobileAllowed.remove(id);
    voices.stopDownload(id);
    _maybeStopWatching();
    notifyListeners();
  }

  Future<void> _tryStart(String id) async {
    final net = await _connectivity.checkConnectivity();
    final unmetered = net.any((r) => r == ConnectivityResult.wifi || r == ConnectivityResult.ethernet);
    final any = net.any((r) => r != ConnectivityResult.none);
    if (!any || (!unmetered && !_mobileAllowed.contains(id))) {
      _status[id] = WaitingForNetwork(wifiOnly: any);
      _startWatching();
      notifyListeners();
      return;
    }
    _status.remove(id);
    try {
      await voices.download(id);
      _mobileAllowed.remove(id);
    } on DownloadStoppedException {
      // Cancelled; nothing to show.
    } on NotEnoughSpaceException {
      _status[id] = DownloadFailed(FailReason.noSpace, neededBytes: voices.remainingBytes(id) + 50 * 1024 * 1024);
    } on UnsupportedVoiceException {
      _status[id] = const DownloadFailed(FailReason.unsupported);
    } catch (e) {
      debugPrint('Voice download $id failed: $e');
      final now = await _connectivity.checkConnectivity();
      if (now.every((r) => r == ConnectivityResult.none)) {
        // The connection went away: carry on when it is back.
        _status[id] = const WaitingForNetwork(wifiOnly: false);
        _startWatching();
      } else {
        _status[id] = const DownloadFailed(FailReason.failed);
      }
    }
    _maybeStopWatching();
    notifyListeners();
  }

  void _startWatching() {
    _watch ??= _connectivity.onConnectivityChanged.listen((_) {
      for (final id in [
        for (final e in _status.entries)
          if (e.value is WaitingForNetwork) e.key,
      ]) {
        unawaited(_tryStart(id));
      }
    });
  }

  void _maybeStopWatching() {
    if (_status.values.any((s) => s is WaitingForNetwork)) return;
    unawaited(_watch?.cancel());
    _watch = null;
  }

  @override
  void dispose() {
    voices.state.removeListener(notifyListeners);
    unawaited(_watch?.cancel());
    super.dispose();
  }
}
