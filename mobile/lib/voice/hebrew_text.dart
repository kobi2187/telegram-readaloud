/// Spells out what the offline Hebrew voices cannot read: numbers, percentages, currencies, times
/// and dates become Hebrew words. Numbers use the feminine counting forms, as they are read aloud
/// in Israeli Hebrew ("שישים ושתיים אחוז").
abstract final class HebrewText {
  static const _onesF = ['אפס', 'אחת', 'שתיים', 'שלוש', 'ארבע', 'חמש', 'שש', 'שבע', 'שמונה', 'תשע'];
  static const _teensF = [
    'עשר', 'אחת עשרה', 'שתים עשרה', 'שלוש עשרה', 'ארבע עשרה',
    'חמש עשרה', 'שש עשרה', 'שבע עשרה', 'שמונה עשרה', 'תשע עשרה',
  ];
  static const _onesM = ['אפס', 'אחד', 'שניים', 'שלושה', 'ארבעה', 'חמישה', 'שישה', 'שבעה', 'שמונה', 'תשעה'];
  static const _teensM = [
    'עשרה', 'אחד עשר', 'שנים עשר', 'שלושה עשר', 'ארבעה עשר',
    'חמישה עשר', 'שישה עשר', 'שבעה עשר', 'שמונה עשר', 'תשעה עשר',
  ];
  static const _tens = ['', '', 'עשרים', 'שלושים', 'ארבעים', 'חמישים', 'שישים', 'שבעים', 'שמונים', 'תשעים'];
  static const _hundreds = [
    '', 'מאה', 'מאתיים', 'שלוש מאות', 'ארבע מאות', 'חמש מאות', 'שש מאות', 'שבע מאות', 'שמונה מאות', 'תשע מאות',
  ];
  static const _thousandsCount = [
    '', 'אלף', 'אלפיים', 'שלושת אלפים', 'ארבעת אלפים', 'חמשת אלפים',
    'ששת אלפים', 'שבעת אלפים', 'שמונת אלפים', 'תשעת אלפים', 'עשרת אלפים',
  ];
  static const _monthsOrdinal = [
    '', 'לראשון', 'לשני', 'לשלישי', 'לרביעי', 'לחמישי', 'לשישי',
    'לשביעי', 'לשמיני', 'לתשיעי', 'לעשירי', 'לאחד עשר', 'לשנים עשר',
  ];

  /// Words for a non-negative number; masculine forms when counting a noun such as אלף.
  static String number(int n, {bool masculine = false}) {
    if (n == 0) return _onesF[0];
    final parts = <String>[];
    _addParts(n, masculine, parts);
    if (parts.length > 1) parts[parts.length - 1] = 'ו${parts.last}';
    return parts.join(' ');
  }

  static void _addParts(int n, bool masculine, List<String> parts) {
    var rest = n;
    for (final (scale, name) in const [(1000000000, 'מיליארד'), (1000000, 'מיליון')]) {
      final count = rest ~/ scale;
      if (count > 0) {
        parts.add(switch (count) {
          1 => name,
          2 => 'שני $name',
          _ => '${number(count, masculine: true)} $name',
        });
        rest %= scale;
      }
    }
    final thousands = rest ~/ 1000;
    if (thousands > 0) {
      parts.add(thousands <= 10 ? _thousandsCount[thousands] : '${number(thousands, masculine: true)} אלף');
      rest %= 1000;
    }
    final h = rest ~/ 100;
    if (h > 0) parts.add(_hundreds[h]);
    final below = rest % 100;
    if (below == 0) return;
    if (below < 10) {
      parts.add((masculine ? _onesM : _onesF)[below]);
    } else if (below < 20) {
      parts.add((masculine ? _teensM : _teensF)[below - 10]);
    } else {
      parts.add(_tens[below ~/ 10]);
      if (below % 10 != 0) parts.add((masculine ? _onesM : _onesF)[below % 10]);
    }
  }

  static String _digits(String s) => s.split('').map((c) => _onesF[c.codeUnitAt(0) - 48]).join(' ');

  /// A digit string as words: long codes and leading zeros are read digit by digit.
  static String _integer(String raw) {
    final s = raw.replaceAll(',', '');
    return s.length > 12 || (s.length > 1 && s.startsWith('0')) ? _digits(s) : number(int.parse(s));
  }

  static String _decimal(String intPart, String frac) {
    final fracWords = frac.length <= 2 && !frac.startsWith('0') ? number(int.parse(frac)) : _digits(frac);
    return '${_integer(intPart)} נקודה $fracWords';
  }

  static const _num = r'\d{1,3}(?:,\d{3})+|\d+';
  static final _time = RegExp(r'(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])');
  // Dates: d/m with an optional year, or d.m.yyyy ("3.5" alone is a decimal, not May 3rd).
  static final _date = RegExp(
    r'(?<![\d./])(0?[1-9]|[12]\d|3[01])(?:/(0?[1-9]|1[0-2])(?:/(\d{4}|\d{2}))?|\.(0?[1-9]|1[0-2])\.(\d{4}))(?![\d./])',
  );
  static final _percent = RegExp('(?:%\\s?($_num(?:\\.\\d+)?)|($_num(?:\\.\\d+)?)\\s?%)');
  static final _currencyBefore = RegExp('([₪\$€£])\\s?($_num(?:\\.\\d+)?)');
  static final _currencyAfter = RegExp('($_num(?:\\.\\d+)?)\\s?(₪|ש"ח|ש״ח|\\\$|€|£)');
  static final _range = RegExp(r'(?<![\d.,])([1-9]\d{0,3})\s?[-–]\s?([1-9]\d{0,3})(?![\d.,])');
  static final _decimalNumber = RegExp('(?<![\\d.,])($_num)\\.(\\d+)(?![\\d.])');
  static final _plainNumber = RegExp('(?<![\\d.,])($_num)(?!\\d)');
  static final _prefixedNumber = RegExp(r'(?<=[בהולמשכ])[-־](?=\d)');

  static String _amount(String text) {
    final dot = text.indexOf('.');
    return dot > 0 ? _decimal(text.substring(0, dot), text.substring(dot + 1)) : _integer(text);
  }

  static String _currencyName(String symbol, String words) => switch (symbol) {
        '₪' || 'ש"ח' || 'ש״ח' => words == 'אחת' ? 'שקל אחד' : '$words שקלים',
        r'$' => '$words דולר',
        '€' => '$words אירו',
        _ => '$words לירות שטרלינג',
      };

  static String normalize(String text) {
    var s = text;
    // "ב-2026", "ה־62": drop the hyphen so the prefix joins the spelled-out number.
    s = s.replaceAll(_prefixedNumber, '');
    s = s.replaceAllMapped(_time, (m) {
      final h = int.parse(m[1]!);
      final min = int.parse(m[2]!);
      return min == 0 ? number(h) : '${number(h)} ${number(min)}';
    });
    s = s.replaceAllMapped(_date, (m) {
      final day = number(int.parse(m[1]!));
      final month = _monthsOrdinal[int.parse(m[2] ?? m[4]!)];
      final y = m[3] ?? m[5];
      final year = y == null ? '' : ' ${number(y.length == 2 ? 2000 + int.parse(y) : int.parse(y))}';
      return '$day $month$year';
    });
    s = s.replaceAllMapped(_percent, (m) => '${_amount(m[1] ?? m[2]!)} אחוז');
    s = s.replaceAllMapped(_currencyBefore, (m) => _currencyName(m[1]!, _amount(m[2]!)));
    s = s.replaceAllMapped(_currencyAfter, (m) => _currencyName(m[2]!, _amount(m[1]!)));
    s = s.replaceAllMapped(_range, (m) => '${_integer(m[1]!)} עד ${_integer(m[2]!)}');
    s = s.replaceAllMapped(_decimalNumber, (m) => _decimal(m[1]!, m[2]!));
    s = s.replaceAllMapped(_plainNumber, (m) => _integer(m[1]!));
    s = s.replaceAll('&', ' ו').replaceAll('+', ' פלוס ');
    return s.replaceAll(RegExp(r'[ \t]{2,}'), ' ');
  }

  static final _letterOrDigit = RegExp(r'[\p{L}\p{N}]', unicode: true);

  /// Utterances short enough for the voice model, keeping the end punctuation; each is paired
  /// with whether a paragraph ends after it.
  static List<(String, bool)> sentences(String text, {int maxLength = 280}) {
    final out = <(String, bool)>[];
    for (final paragraph in text.split(RegExp(r'\n\s*\n'))) {
      final pieces = paragraph
          .split(RegExp(r'(?<=[.!?…:;])\s+|\n+'))
          .map((p) => p.trim())
          .where((p) => _letterOrDigit.hasMatch(p))
          .toList();
      for (var i = 0; i < pieces.length; i++) {
        final chunks = pieces[i].length <= maxLength ? [pieces[i]] : _splitLong(pieces[i], maxLength);
        for (var k = 0; k < chunks.length; k++) {
          out.add((chunks[k], i == pieces.length - 1 && k == chunks.length - 1));
        }
      }
    }
    return out;
  }

  static List<String> _splitLong(String s, int maxLength) {
    final out = <String>[];
    var rest = s;
    while (rest.length > maxLength) {
      var cut = _lastIndexOf(rest, ', ', maxLength);
      cut = cut > maxLength ~/ 3 ? cut + 1 : -1;
      if (cut <= 0) cut = _lastIndexOf(rest, ' ', maxLength);
      if (cut <= 0) cut = maxLength;
      out.add(rest.substring(0, cut).trim());
      rest = rest.substring(cut).trim();
    }
    if (rest.isNotEmpty) out.add(rest);
    return out;
  }

  /// Like Kotlin's lastIndexOf(s, startIndex): the last match starting at or before [start].
  static int _lastIndexOf(String s, String pattern, int start) =>
      s.lastIndexOf(pattern, start < s.length ? start : s.length - 1);
}
