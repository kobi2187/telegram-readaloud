/// Turns a Telegram post into text that sounds good when spoken: no URLs, no emoji, no decorative
/// separators, and sentence punctuation at line ends so TTS engines pause.
class SpeechText {
  SpeechText._();

  static final _url = RegExp(r'\b(?:https?://|www\.)\S+|\b(?:t\.me|telegram\.me)/\S+', caseSensitive: false);
  static final _bareDomain = RegExp(
    r'\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|co\.il|org\.il|gov\.il|ac\.il|io|me|ly|news|tv|info)(?:/\S*)?',
    caseSensitive: false,
  );
  static final _hashtag = RegExp(r'#([\p{L}\p{N}_]+)', unicode: true);
  static final _mention = RegExp(r'(?<![\p{L}\p{N}])@([A-Za-z0-9_]{3,})', unicode: true);
  static final _separatorRun = RegExp(r'([\-=_*~.·•|/\\—–]\s*){3,}');
  static final _leadingBullet = RegExp(r'^\s*[-*•·>]+\s+', multiLine: true);
  static final _spaces = RegExp('[ \t  -​  　]+');
  static final _blankLines = RegExp(r'\n{3,}');
  static final _spaceBeforePunct = RegExp(r'\s+([,.;:!?])');
  static final _repeatedPunct = RegExp(r'([!?.])\1{2,}');
  static final _emptyParens = RegExp(r'\(\s*\)|\[\s*]');
  static final _letterOrDigit = RegExp(r'[\p{L}\p{N}]', unicode: true);
  static const _sentenceEnd = '.!?:;,…"\'”’)]»';

  static String clean(String raw) {
    if (raw.trim().isEmpty) return '';
    var s = raw.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    s = s.replaceAll(_url, ' ');
    s = s.replaceAll(_bareDomain, ' ');
    s = stripEmoji(s);
    s = s.replaceAllMapped(_hashtag, (m) => m[1]!.replaceAll('_', ' '));
    s = s.replaceAllMapped(_mention, (m) => m[1]!.replaceAll('_', ' '));
    s = s.replaceAll(_separatorRun, '\n');
    s = s.replaceAll(_leadingBullet, '');
    s = s.replaceAll(_emptyParens, ' ');
    s = s.replaceAll(_spaces, ' ');
    s = s.replaceAllMapped(_repeatedPunct, (m) => m[1]!);

    final out = StringBuffer();
    String text() => out.toString();
    for (final raw in s.split('\n')) {
      final line = raw.trim();
      if (line.isEmpty) {
        if (out.isNotEmpty && !text().endsWith('\n\n')) out.write('\n');
        continue;
      }
      if (!_letterOrDigit.hasMatch(line)) continue;
      if (out.isNotEmpty && !text().endsWith('\n')) out.write('\n');
      out.write(line);
      if (!_sentenceEnd.contains(line[line.length - 1])) out.write('.');
    }
    s = text();
    s = s.replaceAllMapped(_spaceBeforePunct, (m) => m[1]!);
    s = s.replaceAll(_blankLines, '\n\n');
    return s.trim();
  }

  /// Removes emoji, pictographs, flags, keycap and variation selectors, and box drawing.
  static String stripEmoji(String input) {
    final sb = StringBuffer();
    var last = '';
    for (final cp in input.runes) {
      if (!isEmojiLike(cp)) {
        final ch = String.fromCharCode(cp);
        sb.write(ch);
        last = ch;
      } else if (sb.isNotEmpty && last.trim().isNotEmpty && _isSpacingSymbol(cp)) {
        sb.write(' ');
        last = ' ';
      }
    }
    return sb.toString();
  }

  static bool _isSpacingSymbol(int cp) =>
      cp != 0x200D && !(cp >= 0xFE00 && cp <= 0xFE0F) && cp != 0x20E3 &&
      !(cp >= 0x1F3FB && cp <= 0x1F3FF) && !(cp >= 0xE0020 && cp <= 0xE007F);

  static bool isEmojiLike(int cp) =>
      (cp >= 0x1F000 && cp <= 0x1FAFF) ||
      (cp >= 0x2600 && cp <= 0x27BF) ||
      (cp >= 0x2B00 && cp <= 0x2BFF) ||
      (cp >= 0x2190 && cp <= 0x21FF) ||
      (cp >= 0x2300 && cp <= 0x23FF) ||
      (cp >= 0x2500 && cp <= 0x25FF) ||
      (cp >= 0xFE00 && cp <= 0xFE0F) ||
      (cp >= 0xE0020 && cp <= 0xE007F) ||
      cp == 0x200D || cp == 0x20E3 ||
      cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x2139 ||
      cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
}
