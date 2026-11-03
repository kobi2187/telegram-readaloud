import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Shrinks voiced posts for storage: a WAV becomes AAC in an .m4a file (7–14 times smaller).
abstract class AudioCompressor {
  /// The compressed copy of [wav] (which is then deleted), or null to keep the WAV.
  Future<File?> compress(File wav);
}

/// Keeps the WAV (tests, and platforms without an encoder).
class NoCompression implements AudioCompressor {
  const NoCompression();
  @override
  Future<File?> compress(File wav) async => null;
}

/// The phone's own AAC encoder (AudioChannel.kt on Android, AppDelegate.swift on iOS).
class PlatformAacCompressor implements AudioCompressor {
  static const _channel = MethodChannel('readaloud/audio');

  @override
  Future<File?> compress(File wav) async {
    final path = wav.path;
    final out = File('${path.endsWith('.wav') ? path.substring(0, path.length - 4) : path}.m4a');
    try {
      final ok = await _channel.invokeMethod<bool>('encodeM4a', {'wav': path, 'out': out.path});
      if (ok != true || !await out.exists() || await out.length() == 0) return null;
      await wav.delete();
      return out;
    } on Exception catch (e) {
      debugPrint('Audio not compressed: $e');
      return null;
    }
  }
}
