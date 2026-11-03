import 'dart:convert';
import 'dart:typed_data';

import 'package:unorm_dart/unorm_dart.dart' as unorm;

import 'ort.dart';

/// Hebrew grapheme-to-phoneme: unvocalized Hebrew text to stressed IPA.
///
/// Port of renikud-onnx 0.1.1 (https://github.com/thewh1teagle/renikud, CC BY 4.0), as in the
/// Android app. The model predicts a consonant, a vowel and stress for every letter; vocabularies
/// and per-letter constraints are stored in the model's metadata. Other characters pass through.
class HebrewG2p {
  HebrewG2p(String modelPath) : _model = OnnxModel.open(modelPath) {
    Map<String, dynamic> obj(String key) => jsonDecode(_meta(key)) as Map<String, dynamic>;
    _vocab = obj('vocab').map((k, v) => MapEntry(k, (v as num).toInt()));
    _consonants = obj('consonant_vocab').map((k, v) => MapEntry(int.parse(k), v as String));
    _vowels = obj('vowel_vocab').map((k, v) => MapEntry(int.parse(k), v as String));
    _constraints = obj('letter_consonant_constraints').map((k, v) => MapEntry(k, [for (final x in v as List) (x as num).toInt()]));
    final geresh = _model.metadata('geresh_map');
    _gereshMap = geresh == null ? const {} : (jsonDecode(geresh) as Map<String, dynamic>).map((k, v) => MapEntry(k, v as String));
    _clsId = int.parse(_meta('cls_token_id'));
    _sepId = int.parse(_meta('sep_token_id'));
    _unkId = _vocab['[UNK]'] ?? 0;
  }

  final OnnxModel _model;
  late final Map<String, int> _vocab;
  late final Map<int, String> _consonants;
  late final Map<int, String> _vowels;
  late final Map<String, List<int>> _constraints;
  late final Map<String, String> _gereshMap;
  late final int _clsId;
  late final int _sepId;
  late final int _unkId;

  static const _stress = 'ˈ';
  static const _none = '∅';

  String _meta(String key) => _model.metadata(key) ?? (throw StateError('renikud metadata "$key" missing'));

  String phonemize(String text) {
    // Code points of the decomposed text; index i is token i + 1 (after CLS).
    final chars = unorm.nfd(normalizeGraphemes(text)).runes.map(String.fromCharCode).toList();
    if (chars.isEmpty) return '';
    final ids = Int64List(chars.length + 2);
    ids[0] = _clsId;
    for (var i = 0; i < chars.length; i++) {
      ids[i + 1] = _vocab[chars[i]] ?? _unkId;
    }
    ids[ids.length - 1] = _sepId;
    final mask = Int64List(ids.length)..fillRange(0, ids.length, 1);
    final shape = [1, ids.length];
    final out = _model.run({'input_ids': Tensor.i64(ids, shape), 'attention_mask': Tensor.i64(mask, shape)});
    final names = _model.outputNames;
    Float32List logits(String name) => out[names.indexOf(name)].data;
    final consonantLogits = _Rows(logits('consonant_logits'), ids.length);
    final vowelLogits = _Rows(logits('vowel_logits'), ids.length);
    final stressLogits = _Rows(logits('stress_logits'), ids.length);

    final stressed = _stressedTokens(chars, stressLogits);
    final sb = StringBuffer();
    for (var i = 0; i < chars.length; i++) {
      final char = chars[i];
      final tok = i + 1;
      if (!_isHebrewLetter(char)) {
        if (char != "'" && char != '"') sb.write(char);
        continue;
      }
      var cid = consonantLogits.argmax(tok);
      final allowed = _constraints[char];
      if (allowed != null && !allowed.contains(cid)) {
        cid = allowed.reduce((a, b) => consonantLogits.at(tok, b) > consonantLogits.at(tok, a) ? b : a);
      }
      var consonant = _consonants[cid] ?? _none;
      // Geresh: ג׳ צ׳ ז׳ are read as dʒ tʃ ʒ.
      final next = i + 1 < chars.length ? chars[i + 1] : null;
      if (next == "'" && _gereshMap.containsKey(char)) consonant = _gereshMap[char]!;
      final vowel = _vowels[vowelLogits.argmax(tok)] ?? _none;
      final stress = stressed.contains(tok);
      final wordFinal = next == null || !_isLetter(next);
      if (char == 'ח' && wordFinal && vowel == 'a') {
        // Furtive patah: final ח with "a" is read "aχ".
        if (stress) sb.write(_stress);
        sb.write('aχ');
      } else {
        if (consonant != _none) sb.write(consonant);
        if (stress) sb.write(_stress);
        if (vowel != _none) sb.write(vowel);
      }
    }
    return sb.toString();
  }

  /// For each whitespace-separated word, the single token most likely to carry stress.
  Set<int> _stressedTokens(List<String> chars, _Rows stressLogits) {
    final result = <int>{};
    var i = 0;
    while (i < chars.length) {
      if (chars[i].trim().isEmpty) {
        i++;
        continue;
      }
      var best = -1;
      while (i < chars.length && chars[i].trim().isNotEmpty) {
        final tok = i + 1;
        if (best == -1 || stressLogits.at(tok, 1) > stressLogits.at(best, 1)) best = tok;
        i++;
      }
      if (best != -1) result.add(best);
    }
    return result;
  }

  void close() => _model.close();

  static bool _isHebrewLetter(String c) => c.length == 1 && c.codeUnitAt(0) >= 0x05D0 && c.codeUnitAt(0) <= 0x05EA;
  static final _letter = RegExp(r'^\p{L}', unicode: true);
  static bool _isLetter(String c) => _letter.hasMatch(c);

  /// Unifies geresh and gershayim variants, as renikud does.
  static String normalizeGraphemes(String text) =>
      text.replaceAll(RegExp('[׳\'`´]'), "'").replaceAll(RegExp('[״"“”]'), '"');
}

/// A [tokens × classes] logits matrix stored flat.
class _Rows {
  _Rows(this.data, int tokens) : width = data.length ~/ tokens;
  final Float32List data;
  final int width;

  double at(int row, int col) => data[row * width + col];

  int argmax(int row) {
    var best = 0;
    for (var k = 1; k < width; k++) {
      if (data[row * width + k] > data[row * width + best]) best = k;
    }
    return best;
  }
}
