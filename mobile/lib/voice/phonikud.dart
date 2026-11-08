import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:unorm_dart/unorm_dart.dart' as unorm;

import 'ort.dart';

/// Hebrew text to IPA the way the original Phonikud project does it: a model adds nikud plus
/// phonetic marks (stress, vocal shva, prefixes), then rules read the marked text.
///
/// Ports of phonikud-onnx 1.0.6 (the diacritizer) and phonikud 0.4.1 (the rule-based phonemizer),
/// https://github.com/thewh1teagle/phonikud, MIT. The model is Phonikud's enhancement of Dicta's
/// dictabert-large-char-menaked (CC BY 4.0).
class PhonikudG2p {
  PhonikudG2p(String modelPath, String tokenizerPath)
      : _diacritizer = PhonikudDiacritizer(modelPath, tokenizerPath);

  final PhonikudDiacritizer _diacritizer;

  String phonemize(String text) => PhonikudPhonemizer.phonemize(_diacritizer.addDiacritics(text));

  void close() => _diacritizer.close();
}

/// Adds nikud and Phonikud's phonetic marks to unvocalized Hebrew.
class PhonikudDiacritizer {
  PhonikudDiacritizer(String modelPath, String tokenizerPath) : _model = OnnxModel.open(modelPath) {
    final tok = jsonDecode(File(tokenizerPath).readAsStringSync()) as Map<String, dynamic>;
    _vocab = ((tok['model'] as Map)['vocab'] as Map<String, dynamic>).map((k, v) => MapEntry(k, (v as num).toInt()));
  }

  final OnnxModel _model;
  late final Map<String, int> _vocab;

  static const _nikudClasses = [
    '', '<MAT_LECT>', 'ּ', 'ְ', 'ֱ', 'ֲ', 'ֳ', 'ִ', 'ֵ', 'ֶ', 'ַ', 'ָ',
    'ֹ', 'ֺ', 'ֻ', 'ְּ', 'ֱּ', 'ֲּ', 'ֳּ', 'ִּ',
    'ֵּ', 'ֶּ', 'ַּ', 'ָּ', 'ֹּ', 'ֺּ', 'ֻּ',
    'ׇ', 'ׇּ',
  ];
  static const _shinClasses = ['ׁ', 'ׂ'];
  static const stressChar = '֫';
  static const vocalShvaChar = 'ֽ';
  static const prefixChar = '|';
  static const maxChunk = 2046;

  static final _nikud = RegExp('[֐-ׇ|]');

  static bool _isHebrewLetter(int c) => c >= 0x05D0 && c <= 0x05EA;

  /// The tokenizer's normalizer turns runs of characters outside these ranges into one [UNK].
  static bool _inRange(int c) =>
      (c >= 0x0590 && c <= 0x05FF) ||
      c <= 0x007F ||
      (c >= 0x200C && c <= 0x203F) ||
      (c >= 0x20A0 && c <= 0x20BF) ||
      (c >= 0x2200 && c <= 0x22FF) ||
      (c >= 0x2150 && c <= 0x218B) ||
      (c >= 0xFB00 && c <= 0xFB4F);

  /// The model's tokens for [chars]: (id, first char, end char). One token per character, as the
  /// character-level tokenizer does; a run of unsupported characters is one [UNK].
  List<(int, int, int)> tokens(List<int> chars) {
    final out = <(int, int, int)>[];
    var i = 0;
    while (i < chars.length) {
      if (!_inRange(chars[i])) {
        var j = i;
        while (j < chars.length && !_inRange(chars[j])) {
          j++;
        }
        out.add((0, i, j));
        i = j;
        continue;
      }
      // NFKC, lowercase, StripAccents (drops combining marks, without decomposing first), as the
      // tokenizer's normalizer; a character NFKC expands ("ﬁ") becomes several tokens.
      final s = unorm.nfkc(String.fromCharCode(chars[i])).toLowerCase().replaceAll(RegExp(r'\p{Mn}', unicode: true), '');
      for (final c in s.runes) {
        out.add((_vocab[String.fromCharCode(c)] ?? 0, i, i + 1));
      }
      i++;
    }
    return out;
  }

  String addDiacritics(String text) {
    final sb = StringBuffer();
    for (final chunk in _chunks(text)) {
      sb.write(_predict(chunk));
    }
    return sb.toString();
  }

  static List<String> _chunks(String text) {
    if (text.runes.length <= maxChunk) return [text];
    final out = <String>[];
    var buf = '';
    for (final part in text.split(RegExp(r'(?<=[.\n])'))) {
      if ((buf + part).runes.length <= maxChunk) {
        buf += part;
      } else {
        if (buf.isNotEmpty) out.add(buf);
        buf = part;
      }
    }
    if (buf.isNotEmpty) out.add(buf);
    return [
      for (final c in out)
        if (c.runes.length <= maxChunk)
          c
        else
          for (var k = 0; k < c.runes.length; k += maxChunk)
            String.fromCharCodes(c.runes.skip(k).take(maxChunk)),
    ];
  }

  String _predict(String sentence) {
    final chars = sentence.replaceAll(_nikud, '').runes.toList();
    if (chars.isEmpty) return '';
    final tokens = this.tokens(chars);
    final ids = Int64List(tokens.length + 2);
    ids[0] = 1; // [CLS]
    for (var k = 0; k < tokens.length; k++) {
      ids[k + 1] = tokens[k].$1;
    }
    ids[ids.length - 1] = 2; // [SEP]
    final shape = [1, ids.length];
    final outputs = _model.run({
      'input_ids': Tensor.i64(ids, shape),
      'attention_mask': Tensor.i64(Int64List(ids.length)..fillRange(0, ids.length, 1), shape),
      'token_type_ids': Tensor.i64(Int64List(ids.length), shape),
    });
    final names = _model.outputNames;
    Float32List out(String name) => outputs[names.indexOf(name)].data;
    final nikud = out('nikud_logits');
    final shin = out('shin_logits');
    final extra = out('additional_logits');
    final nikudWidth = nikud.length ~/ ids.length;
    final shinWidth = shin.length ~/ ids.length;
    final extraWidth = extra.length ~/ ids.length;
    int argmax(Float32List data, int row, int width) {
      var best = 0;
      for (var k = 1; k < width; k++) {
        if (data[row * width + k] > data[row * width + best]) best = k;
      }
      return best;
    }

    final sb = StringBuffer();
    var prev = 0;
    for (var t = 0; t < tokens.length; t++) {
      final (_, start, end) = tokens[t];
      final row = t + 1; // after [CLS]
      if (start > prev) sb.write(String.fromCharCodes(chars.sublist(prev, start)));
      if (end - start != 1) continue;
      final c = chars[start];
      prev = end;
      final ch = String.fromCharCode(c);
      if (!_isHebrewLetter(c)) {
        sb.write(ch);
        continue;
      }
      var n = _nikudClasses[argmax(nikud, row, nikudWidth)];
      final shinMark = ch == 'ש' ? _shinClasses[argmax(shin, row, shinWidth)] : '';
      if (n == '<MAT_LECT>') {
        if ('אוי'.contains(ch)) {
          sb.write(ch);
          continue;
        }
        n = '';
      }
      final stress = extra[row * extraWidth] > 0 ? stressChar : '';
      final shva = extra[row * extraWidth + 1] > 0 ? vocalShvaChar : '';
      final prefix = extra[row * extraWidth + 2] > 0 ? prefixChar : '';
      sb.write('$ch$shinMark$n$stress$shva$prefix');
    }
    sb.write(String.fromCharCodes(chars.sublist(prev)));
    return sb.toString();
  }

  void close() => _model.close();
}

/// A letter with its diacritics; [diac] leaves out Phonikud's phonetic marks.
class _Letter {
  _Letter(this.char, String diac) : allDiac = _sorted(diac);
  final String char;
  String allDiac;
  String get diac => allDiac.split('').where((c) => !PhonikudPhonemizer._enhanced.contains(c)).join();

  static String _sorted(String diac) => String.fromCharCodes(diac.runes.toList()..sort());

  @override
  String toString() => char + allDiac;
}

/// Phonikud's rules: vocalized Hebrew (with its phonetic marks) to IPA.
abstract final class PhonikudPhonemizer {
  static const _vocalShva = 'ֽ';
  static const _hatama = '֫';
  static const _prefix = '|';
  static const _nikudHaser = '֯';
  static const _enhanced = {_hatama, _prefix, _vocalShva};
  static const stress = 'ˈ';

  static const _shva = 'ְ';
  static const _sin = 'ׂ';
  static const _patah = 'ַ';
  static const _kamatz = 'ָ';
  static const _hatafKamatz = 'ֳ';
  static const _dagesh = 'ּ';
  static const _holam = 'ֹ';
  static const _hirik = 'ִ';
  static const _kubuts = 'ֻ';
  static const _tsere = 'ֵ';
  static const _segol = 'ֶ';

  static final _hePattern = RegExp('[ְ-ת$_vocalShva$_hatama|$_nikudHaser\'"]+');
  static final _letters = RegExp(r"(\p{L})([\p{M}'|]*)", unicode: true);
  static final _marks = RegExp(r'(\p{L})(\p{M}+)', unicode: true);

  static const _geresh = {'ג': 'dʒ', 'ז': 'ʒ', 'ת': 'ta', 'צ': 'tʃ', 'ץ': 'tʃ'};
  static const _consonants = {
    'א': 'ʔ', 'ב': 'v', 'ג': 'g', 'ד': 'd', 'ה': 'h', 'ו': 'v', 'ז': 'z', 'ח': 'x', 'ט': 't', 'י': 'j', 'ך': 'x',
    'כ': 'x', 'ל': 'l', 'ם': 'm', 'מ': 'm', 'ן': 'n', 'נ': 'n', 'ס': 's', 'ע': 'ʔ', 'פ': 'f', 'ף': 'f', 'ץ': 'ts',
    'צ': 'ts', 'ק': 'k', 'ר': 'r', 'ש': 'ʃ', 'ת': 't',
    'בּ': 'b', 'כּ': 'k', 'פּ': 'p', 'שׁ': 'ʃ', 'שׂ': 's', "'": '',
  };
  static const _vowels = {
    'ִ': 'i', 'ֱ': 'e', 'ֵ': 'e', 'ֶ': 'e', 'ֲ': 'a', 'ַ': 'a', 'ׇ': 'o',
    'ֹ': 'o', 'ֺ': 'o', 'ֻ': 'u', 'ֳ': 'o', 'ָ': 'a', _hatama: stress, _vocalShva: 'e',
  };
  static const _modern = {'x': 'χ', 'r': 'ʁ', 'g': 'ɡ'};

  /// Single characters that may appear in the output (Phonikud's phoneme set).
  static final Set<String> _phonemeChars = {
    for (final p in [..._vowels.values, ..._consonants.values, ..._geresh.values, ..._modern.values, 'w'])
      if (p.length == 1) p,
  };
  static const _punctuation = {'.', ',', '!', '?', ' '};

  /// NFD, diacritics sorted after each letter, Hebrew geresh/gershayim and makaf unified.
  static String normalize(String text) {
    var s = unorm.nfd(text);
    s = s.replaceAllMapped(_marks, (m) => m[1]! + _Letter._sorted(m[2]!));
    return s.replaceAll('״', '"').replaceAll('׳', "'").replaceAll('־', '-');
  }

  /// IPA for [vocalized] text; [keepStress] false leaves out the stress marks.
  static String phonemize(String vocalized, {bool keepStress = true}) {
    var text = normalize(vocalized);
    text = text.replaceAllMapped(_hePattern, (m) {
      if (m.start > 0 && text[m.start - 1] == '[') return m[0]!;
      var word = m[0]!;
      if (!word.contains(_hatama)) word = _addMilraHatama(word);
      final letters = _sortHatama(_getLetters(word));
      var phonemes = _postNormalize(_phonemizeHebrew(letters).join());
      for (final e in _modern.entries) {
        phonemes = phonemes.replaceAll(e.key, e.value);
      }
      return phonemes;
    });
    if (!keepStress) text = text.replaceAll(stress, '');
    return _postClean(text);
  }

  static List<_Letter> _getLetters(String word) => [for (final m in _letters.allMatches(word)) _Letter(m[1]!, m[2]!)];

  static List<_Letter> _sortHatama(List<_Letter> letters) {
    for (var i = 0; i < letters.length - 1; i++) {
      final d = letters[i].allDiac;
      if (d.contains(_hatama) && d.contains(_nikudHaser)) {
        letters[i].allDiac = d.replaceFirst(_hatama, '');
        letters[i + 1].allDiac += _hatama;
      }
    }
    return letters;
  }

  static String _postNormalize(String phonemes) => phonemes.split(' ').map((w) {
        var word = w.replaceAll(RegExp(r'ʔ$'), '');
        word = word.replaceAll(RegExp(r'h$'), '');
        word = word.replaceAll(RegExp(r'ˈh$'), '');
        return word.replaceAll(RegExp(r'ij$'), 'i');
      }).join(' ');

  static String _postClean(String text) {
    final sb = StringBuffer();
    for (final c in text.runes.map(String.fromCharCode)) {
      if (c == '-') {
        sb.write(' ');
      } else if (_phonemeChars.contains(c) || _punctuation.contains(c)) {
        sb.write(c);
      }
    }
    return sb.toString();
  }

  // --- Syllables and default (final-syllable) stress ------------------------------------

  static final Set<String> _vowelDiacs = {
    for (var c = 0x05B1; c < 0x05BC; c++) String.fromCharCode(c),
    'ׇ',
    'ֽ',
  };

  static bool _hasVowelDiacs(String s) => s == 'וּ' || s.split('').any(_vowelDiacs.contains);

  static List<String> _syllables(String word) {
    final letters = _getLetters(word);
    final syllables = <String>[];
    var cur = '';
    var vowelState = false;
    var i = 0;
    while (i < letters.length) {
      final letter = letters[i];
      final hasVowel = _hasVowelDiacs(letter.toString()) || (i == 0 && letter.allDiac.contains(_shva));
      final vav1 = i + 2 < letters.length && letters[i + 2].char == 'ו';
      final vav2 = i + 3 < letters.length && letters[i + 3].char == 'ו';
      if (hasVowel) {
        if (vowelState) {
          syllables.add(cur);
          cur = letter.toString();
        } else {
          cur += letter.toString();
        }
        vowelState = true;
      } else {
        cur += letter.toString();
      }
      i++;
      if (vav1 && vav2) {
        if (cur.isNotEmpty) {
          syllables.add(cur + letters[i].toString());
          cur = '';
        }
        cur = letters[i + 1].toString() + letters[i + 2].toString();
        i += 3;
        vowelState = true;
      } else if (vav1 && letters[i + 1].diac.isNotEmpty) {
        if (cur.isNotEmpty) {
          syllables.add(cur);
          cur = '';
        }
        vowelState = false;
      }
    }
    if (cur.isNotEmpty) syllables.add(cur);
    return syllables;
  }

  static String _addMilraHatama(String word) {
    final syllables = _syllables(word);
    if (syllables.isEmpty) return word;
    final letters = _getLetters(syllables.last);
    if (letters.isEmpty) return word;
    letters[0].allDiac += _hatama;
    syllables[syllables.length - 1] = letters.join();
    return syllables.join();
  }

  // --- Letters to phonemes ----------------------------------------------------------------

  static List<String> _phonemizeHebrew(List<_Letter> letters) {
    final out = <String>[];
    var i = 0;
    while (i < letters.length) {
      final (phonemes, skip) = _letterToPhonemes(letters[i], i > 0 ? letters[i - 1] : null, i + 1 < letters.length ? letters[i + 1] : null);
      out.addAll(phonemes);
      i += skip + 1;
    }
    return out;
  }

  static bool _handleYud(_Letter cur, _Letter? prev, _Letter? next) =>
      next != null &&
      cur.diac.isEmpty &&
      prev != null &&
      prev.char + prev.diac != 'אֵ' &&
      !(next.char == 'ו' && next.diac.isNotEmpty && !next.diac.contains('ְ'));

  static (List<String>, bool, bool, int) _handleVav(_Letter cur, _Letter? prev, _Letter? next) {
    if (prev != null && prev.diac.contains(_shva) && cur.diac.contains(_holam)) return (['vo'], true, true, 0);
    if (next != null && next.char == 'ו') {
      final diac = cur.diac + next.diac;
      if (diac.contains(_holam)) return (['vo'], true, true, 1);
      if (cur.diac == next.diac) return (['vu'], true, true, 1);
      if (cur.diac.contains(_hirik)) return (['vi'], true, true, 0);
      if (cur.diac.contains(_shva) && next.diac.isEmpty) return (['v'], true, true, 0);
      if (cur.diac.contains(_kamatz) || cur.diac.contains(_patah)) return (['va'], true, true, 0);
      if (cur.diac.contains(_tsere) || cur.diac.contains(_segol)) return (['ve'], true, true, 0);
      return (<String>[], false, false, 0);
    }
    if (cur.diac.contains(_patah) || cur.diac.contains(_kamatz)) return (['va'], true, true, 0);
    if (cur.diac.contains(_tsere) || cur.diac.contains(_segol)) return (['ve'], true, true, 0);
    if (cur.diac.contains(_holam)) return (['o'], true, true, 0);
    if (cur.diac.contains(_kubuts) || cur.diac.contains(_dagesh)) return (['u'], true, true, 0);
    if (cur.diac.contains(_shva) && prev == null) return (['ve'], true, true, 0);
    if (cur.diac.contains(_hirik)) return (['vi'], true, true, 0);
    if (next != null && cur.diac.isEmpty) return (<String>[], true, true, 0);
    return (['v'], true, true, 0);
  }

  static (List<String>, int) _letterToPhonemes(_Letter cur, _Letter? prev, _Letter? next) {
    final phonemes = <String>[];
    var skipDiacritics = false;
    var skipConsonants = false;
    var skipOffset = 0;

    if (cur.allDiac.contains(_nikudHaser)) {
      skipConsonants = true;
      skipDiacritics = true;
    } else if (cur.char == 'א' && cur.diac.isEmpty && prev != null) {
      if (next != null && next.char != 'ו') skipConsonants = true;
    } else if (cur.char == 'י' && _handleYud(cur, prev, next)) {
      skipConsonants = true;
    } else if (cur.char == 'ש' && cur.diac.contains(_sin)) {
      if (next != null && next.char == 'ש' && next.diac.isEmpty && RegExp('[ַָ]').hasMatch(cur.diac)) {
        phonemes.add('sa');
        skipConsonants = true;
        skipDiacritics = true;
        skipOffset += 1;
      } else {
        phonemes.add('s');
        skipConsonants = true;
      }
    } else if (cur.char == 'ש' && cur.diac.isEmpty && prev != null && prev.diac.contains(_sin)) {
      phonemes.add('s');
      skipConsonants = true;
    } else if (next == null && cur.char == 'ח' && cur.diac.contains(_patah)) {
      phonemes.add('ax');
      skipDiacritics = true;
      skipConsonants = true;
    } else if (next == null && cur.char == 'ה' && cur.diac.contains(_patah)) {
      phonemes.add('ah');
      skipDiacritics = true;
      skipConsonants = true;
    } else if (next == null && cur.char == 'ע' && cur.diac.contains(_patah)) {
      phonemes.add('a');
      skipDiacritics = true;
      skipConsonants = true;
    }

    if (cur.diac.contains("'") && _geresh.containsKey(cur.char)) {
      phonemes.add(_geresh[cur.char]!);
      skipConsonants = true;
      if (cur.char == 'ת') skipDiacritics = true;
    } else if (cur.diac.contains(_dagesh) && _consonants.containsKey(cur.char + _dagesh)) {
      phonemes.add(_consonants[cur.char + _dagesh]!);
      skipConsonants = true;
    } else if (cur.char == 'ו' && !cur.allDiac.contains(_nikudHaser)) {
      final (vav, vavSkipConsonants, vavSkipDiacritics, vavOffset) = _handleVav(cur, prev, next);
      phonemes.addAll(vav);
      skipConsonants = vavSkipConsonants;
      skipDiacritics = vavSkipDiacritics;
      skipOffset += vavOffset;
    }

    if (!skipConsonants) phonemes.add(_consonants[cur.char] ?? '');

    if (cur.diac.contains(_kamatz) && next != null && next.diac.contains(_hatafKamatz)) {
      phonemes.add('o');
      skipDiacritics = true;
    }

    if (!skipDiacritics) {
      phonemes.addAll([for (final n in cur.allDiac.split('')) _vowels[n] ?? '']);
    } else if (cur.allDiac.contains(_hatama)) {
      phonemes.add(stress);
    }
    final sorted = _sortStress(phonemes);
    return ([for (final p in sorted) if (p.isNotEmpty && p.split('').every(_phonemeChars.contains)) p], skipOffset);
  }

  /// Moves the stress mark right before the first vowel (where TTS models expect it).
  static List<String> _sortStress(List<String> phonemes) {
    final joined = phonemes.join();
    if (!joined.contains(stress) || !RegExp('[aeiou]').hasMatch(joined)) return phonemes;
    final out = [for (final p in phonemes) if (p != stress) p];
    for (var i = 0; i < out.length; i++) {
      final j = out[i].indexOf(RegExp('[aeiou]'));
      if (j >= 0) {
        out[i] = '${out[i].substring(0, j)}$stress${out[i].substring(j)}';
        return out;
      }
    }
    return out;
  }
}
