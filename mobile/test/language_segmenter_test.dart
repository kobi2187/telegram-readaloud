import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/text/language_segmenter.dart';

void main() {
  test('splits sentences by language', () {
    final s = LanguageSegmenter.segment('שלום עולם. Hello world. עוד משפט.');
    expect(s.map((e) => e.lang), [Lang.he, Lang.en, Lang.he]);
    expect(s[1].text, 'Hello world.');
  });

  test('keeps brand names inside a Hebrew sentence', () {
    final s = LanguageSegmenter.segment('הודעה מ-Google היום על מוצר חדש.');
    expect(s.length, 1);
    expect(s[0].lang, Lang.he);
  });

  test('merges neighbours and attaches neutral text', () {
    final s = LanguageSegmenter.segment('1.\nחדשות היום.\nעוד חדשות.\n12:30');
    expect(s.length, 1);
    expect(s[0].lang, Lang.he);
    expect(s[0].text, '1.\nחדשות היום.\nעוד חדשות.\n12:30');
  });

  test('detects other scripts', () {
    expect(LanguageSegmenter.detect('الحمد لله'), Lang.ar);
    expect(LanguageSegmenter.detect('Привет мир'), Lang.ru);
    expect(LanguageSegmenter.detect('12:30 !!'), isNull);
  });

  test('numbers only use the fallback', () {
    expect(LanguageSegmenter.segment('12:30', fallback: Lang.he), [const Segment(Lang.he, '12:30')]);
  });
}
