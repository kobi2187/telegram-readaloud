import 'dart:async';
import 'dart:io';

import 'package:flutter_tts/flutter_tts.dart';

import '../text/language_segmenter.dart';
import 'audio.dart';

/// A speech engine on the phone that renders text to PCM.
abstract class SpeechEngine {
  Future<bool> isLanguageAvailable(Lang lang);
  Future<Pcm> synthesize(String text, Lang lang, Directory scratch);
}

/// The phone's own text-to-speech (Google Speech Services, iOS voices), rendered to files so posts
/// play offline like any other audio. One request at a time.
class DeviceTts implements SpeechEngine {
  DeviceTts() {
    _ready = _init();
  }

  final FlutterTts _tts = FlutterTts();
  late final Future<void> _ready;
  Future<void> _chain = Future.value();
  int _counter = 0;

  Future<void> _init() async {
    await _tts.awaitSynthCompletion(true);
    if (Platform.isIOS) {
      await _tts.setSharedInstance(true);
      await _tts.setIosAudioCategory(IosTextToSpeechAudioCategory.playback, [IosTextToSpeechAudioCategoryOptions.mixWithOthers]);
    }
  }

  Future<T> _serial<T>(Future<T> Function() task) {
    final result = _chain.then((_) => task());
    _chain = result.then((_) {}, onError: (_) {});
    return result;
  }

  @override
  Future<bool> isLanguageAvailable(Lang lang) => _serial(() async {
        await _ready;
        try {
          final v = await _tts.isLanguageAvailable(lang.tag);
          return v == true || v == 1;
        } catch (_) {
          return false;
        }
      });

  /// Renders [text] in [lang] to 16-bit mono PCM; throws when no voice can speak it.
  @override
  Future<Pcm> synthesize(String text, Lang lang, Directory scratch) => _serial(() async {
        await _ready;
        if (!await _isAvailable(lang)) throw TtsUnavailableException('No device voice for ${lang.tag}');
        await scratch.create(recursive: true);
        final file = File('${scratch.path}/tts_${DateTime.now().microsecondsSinceEpoch}_${_counter++}.wav');
        try {
          await _tts.setLanguage(lang.tag);
          await _tts.setSpeechRate(Platform.isIOS ? 0.5 : 1.0);
          final r = await _tts.synthesizeToFile(text, file.path, true).timeout(const Duration(minutes: 2));
          if (r != 1 && r != true) throw TtsUnavailableException('Device TTS refused the text');
          // Some engines report completion before the file is flushed.
          for (var i = 0; i < 50 && (!await file.exists() || await file.length() < 44); i++) {
            await Future<void>.delayed(const Duration(milliseconds: 100));
          }
          return Wav.parse(await file.readAsBytes()).toPcm();
        } finally {
          if (await file.exists()) await file.delete();
        }
      });

  Future<bool> _isAvailable(Lang lang) async {
    try {
      final v = await _tts.isLanguageAvailable(lang.tag);
      return v == true || v == 1;
    } catch (_) {
      return false;
    }
  }
}

class TtsUnavailableException implements IOException {
  TtsUnavailableException(this.message);
  final String message;
  @override
  String toString() => message;
}
