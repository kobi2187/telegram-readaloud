/// Languages we pick a dedicated voice for. Detection is by script, which is reliable for these.
enum Lang {
  he('he-IL'),
  en('en-US'),
  ar('ar-SA'),
  ru('ru-RU');

  const Lang(this.tag);
  final String tag;
}

class Segment {
  const Segment(this.lang, this.text);
  final Lang lang;
  final String text;

  @override
  bool operator ==(Object other) => other is Segment && other.lang == lang && other.text == text;
  @override
  int get hashCode => Object.hash(lang, text);
  @override
  String toString() => 'Segment($lang, $text)';
}

/// Splits mixed-language text into runs that each go to a single voice. Splitting happens at
/// sentence and line boundaries only, so a Hebrew sentence with an English brand name stays in one
/// Hebrew run, while a whole English sentence in a Hebrew post is read by the English voice.
class LanguageSegmenter {
  LanguageSegmenter._();

  static final _sentenceSplit = RegExp(r'(?<=[.!?…:;])\s+|\n+');
  static final _latinLetter = RegExp(r'[A-Za-zÀ-ɏḀ-ỿ]');

  static List<Segment> segment(String text, {Lang fallback = Lang.en}) {
    final pieces = text.split(_sentenceSplit).map((p) => p.trim()).where((p) => p.isNotEmpty).toList();
    if (pieces.isEmpty) return const [];
    final defaultLang = dominant(text) ?? fallback;
    final segments = <Segment>[];
    var pending = '';
    for (final piece in pieces) {
      final lang = detect(piece);
      if (lang == null) {
        if (segments.isNotEmpty) {
          final last = segments.removeLast();
          segments.add(Segment(last.lang, _join(last.text, piece)));
        } else {
          pending = _join(pending, piece);
        }
        continue;
      }
      final withPending = pending.isNotEmpty ? _join(pending, piece) : piece;
      pending = '';
      if (segments.isNotEmpty && segments.last.lang == lang) {
        final last = segments.removeLast();
        segments.add(Segment(lang, _join(last.text, withPending)));
      } else {
        segments.add(Segment(lang, withPending));
      }
    }
    if (pending.isNotEmpty) segments.add(Segment(defaultLang, pending));
    return segments;
  }

  /// The language with the most letters in [text], or null when it has no letters at all.
  static Lang? dominant(String text) => detect(text);

  static Lang? detect(String text) {
    var he = 0, ar = 0, ru = 0, en = 0;
    for (final cp in text.runes) {
      if ((cp >= 0x0590 && cp <= 0x05FF) || (cp >= 0xFB1D && cp <= 0xFB4F)) {
        he++;
      } else if ((cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F) ||
          (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF)) {
        ar++;
      } else if (cp >= 0x0400 && cp <= 0x04FF) {
        ru++;
      } else if (cp < 0x10000 && _latinLetter.hasMatch(String.fromCharCode(cp))) {
        en++;
      }
    }
    final maxCount = [he, ar, ru, en].reduce((a, b) => a > b ? a : b);
    if (maxCount == 0) return null;
    if (maxCount == he) return Lang.he;
    if (maxCount == en) return Lang.en;
    if (maxCount == ar) return Lang.ar;
    return Lang.ru;
  }

  static String _join(String a, String b) => a.isEmpty ? b : (b.isEmpty ? a : '$a\n$b');
}
