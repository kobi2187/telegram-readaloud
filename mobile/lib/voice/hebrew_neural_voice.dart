import 'dart:typed_data';

import 'engines.dart';
import 'hebrew_g2p.dart';
import 'hebrew_text.dart';
import 'phonikud.dart';

/// Hebrew text to stressed IPA: renikud (built into every voice) or the Phonikud pipeline.
abstract class HebrewPhonemizer {
  String phonemize(String text);
  void close();
}

class _Renikud implements HebrewPhonemizer {
  _Renikud(String file) : _g2p = HebrewG2p(file);
  final HebrewG2p _g2p;
  @override
  String phonemize(String text) => _g2p.phonemize(text);
  @override
  void close() => _g2p.close();
}

class _Phonikud implements HebrewPhonemizer {
  _Phonikud(String model, String tokenizer) : _g2p = PhonikudG2p(model, tokenizer);
  final PhonikudG2p _g2p;
  @override
  String phonemize(String text) => _g2p.phonemize(text);
  @override
  void close() => _g2p.close();
}

/// How the offline voices are told to pronounce Hebrew.
class Pronunciation {
  const Pronunciation({this.phonikud = false, this.stress = true});

  /// The Phonikud pipeline (nikud model + rules) instead of renikud.
  final bool phonikud;

  /// Mark the stressed syllables in the IPA.
  final bool stress;

  /// Part of the audio's identity: posts are voiced again when it changes.
  String get key => '${phonikud ? '|phonikud' : ''}${stress ? '' : '|nostress'}';

  static Pronunciation fromKey(String key) => Pronunciation(phonikud: key.contains('|phonikud'), stress: !key.contains('|nostress'));
}

/// Offline neural Hebrew speech: Hebrew text, then spelled-out numbers, then IPA (renikud, or the
/// Phonikud pipeline), then a neural voice ([VoiceModel]). renikud and Phonikud are by
/// thewh1teagle et al.; each voice has its own license. Synchronous: run it on a background isolate.
class HebrewNeuralVoice {
  HebrewNeuralVoice(String g2pFile, VoiceModel model, {({String model, String tokenizer})? phonikud})
      : _g2p = phonikud != null ? _Phonikud(phonikud.model, phonikud.tokenizer) : _Renikud(g2pFile) {
    try {
      _engine = model.open();
    } catch (_) {
      _g2p.close();
      rethrow;
    }
  }

  /// Leave the stress marks out of the IPA (to hear the voice without them).
  bool stress = true;

  static const sentencePauseMs = 250;
  static const paragraphPauseMs = 450;

  final HebrewPhonemizer _g2p;
  late final IpaEngine _engine;

  /// The IPA of the last text, for diagnostics and tests.
  String lastIpa = '';

  int get sampleRate => _engine.sampleRate;

  /// The utterances [text] is spoken in, each with whether a paragraph ends after it.
  static List<(String, bool)> plan(String text) => HebrewText.sentences(HebrewText.normalize(text));

  /// One utterance from [plan], followed by its pause; empty if nothing could be spoken.
  Int16List speakSentence(String sentence, bool paragraphEnd) {
    var ipa = _g2p.phonemize(sentence);
    if (!stress) ipa = ipa.replaceAll('ˈ', '');
    lastIpa = lastIpa.isEmpty ? ipa : '$lastIpa\n$ipa';
    final pcm = _engine.speak(ipa);
    if (pcm.isEmpty) return pcm;
    final pause = sampleRate * (paragraphEnd ? paragraphPauseMs : sentencePauseMs) ~/ 1000;
    return Int16List(pcm.length + pause)..setAll(0, pcm);
  }

  /// Renders [text] sentence by sentence, handing each sentence (with its following pause) to
  /// [emit] as soon as it is ready. [emit] returns false to stop early.
  void synthesize(String text, bool Function(Int16List pcm) emit) {
    lastIpa = '';
    for (final (sentence, paragraphEnd) in plan(text)) {
      final pcm = speakSentence(sentence, paragraphEnd);
      if (pcm.isNotEmpty && !emit(pcm)) return;
    }
  }

  /// The whole of [text] as one clip.
  Int16List synthesizeAll(String text) {
    final parts = <Int16List>[];
    synthesize(text, (p) {
      parts.add(p);
      return true;
    });
    final out = Int16List(parts.fold(0, (a, p) => a + p.length));
    var pos = 0;
    for (final p in parts) {
      out.setAll(pos, p);
      pos += p.length;
    }
    return out;
  }

  void close() {
    _g2p.close();
    _engine.close();
  }
}
