import 'package:flutter/services.dart';

/// The music stream's volume on Android (see VolumeChannel.kt).
class AndroidVolume {
  static const _channel = MethodChannel('readaloud/volume');
  static const _changes = EventChannel('readaloud/volume_changes');

  Future<({int min, int max, int current})> state() async => _read(await _channel.invokeMapMethod<String, int>('state'));

  /// One step up (+1) or down (-1), showing the system volume panel.
  Future<({int min, int max, int current})> adjust(int direction) async => _read(await _channel.invokeMapMethod<String, int>('adjust', direction));

  Future<({int min, int max, int current})> set(int volume, {bool showUi = false}) async =>
      _read(await _channel.invokeMapMethod<String, int>('set', {'volume': volume, 'showUi': showUi}));

  /// (previous, current) for every change of the music stream's volume.
  Stream<(int, int)> get changes => _changes.receiveBroadcastStream().map((e) {
        final l = e as List;
        return (l[0] as int, l[1] as int);
      });

  static ({int min, int max, int current}) _read(Map<String, int>? m) => (min: m?['min'] ?? 0, max: m?['max'] ?? 15, current: m?['current'] ?? 0);
}
