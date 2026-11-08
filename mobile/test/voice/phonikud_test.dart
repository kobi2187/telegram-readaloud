import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/voice/phonikud.dart';

/// Checked against the reference Python packages (phonikud-onnx 1.0.6, phonikud 0.4.1, and the
/// HF tokenizer), with the same model files: test/fixtures/phonikud/*.json were produced by them.
void main() {
  final dir = Platform.environment['VOICE_MODELS_DIR'] ?? '';
  final model = '$dir/phonikud-1.0.int8.onnx';
  final tokenizer = '$dir/phonikud-tokenizer.json';
  final skip = File(model).existsSync() && File(tokenizer).existsSync() ? null : 'Phonikud files missing (VOICE_MODELS_DIR)';
  final reference = (jsonDecode(File('test/fixtures/phonikud/reference.json').readAsStringSync()) as List).cast<Map<String, dynamic>>();

  test('the rules turn the reference nikud into the reference IPA', () {
    for (final r in reference) {
      expect(PhonikudPhonemizer.phonemize(r['vocalized'] as String), r['ipa'], reason: r['text'] as String);
      expect(PhonikudPhonemizer.phonemize(r['vocalized'] as String, keepStress: false), r['ipa_nostress']);
    }
  });

  test('tokens match the reference tokenizer', () {
    final d = PhonikudDiacritizer(model, tokenizer);
    try {
      for (final c in (jsonDecode(File('test/fixtures/phonikud/tokens.json').readAsStringSync()) as List).cast<Map<String, dynamic>>()) {
        final ids = [for (final t in d.tokens((c['text'] as String).runes.toList())) t.$1];
        expect(ids, c['ids'], reason: c['text'] as String);
      }
    } finally {
      d.close();
    }
  }, skip: skip);

  test('the model adds the reference nikud, and the pipeline gives the reference IPA', () {
    final g2p = PhonikudG2p(model, tokenizer);
    try {
      for (final r in reference) {
        expect(g2p.phonemize(r['text'] as String), r['ipa'], reason: r['text'] as String);
      }
    } finally {
      g2p.close();
    }
  }, skip: skip, timeout: const Timeout(Duration(minutes: 3)));
}
