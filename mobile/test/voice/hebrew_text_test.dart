import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/voice/hebrew_text.dart';
import 'package:tg_read_aloud/voice/voice_catalog.dart';

/// The same cases as the Android app's HebrewTextTest.
void main() {
  String n(int x) => HebrewText.number(x);
  String norm(String s) => HebrewText.normalize(s);

  test('cardinals', () {
    expect(n(0), 'אפס');
    expect(n(7), 'שבע');
    expect(n(12), 'שתים עשרה');
    expect(n(20), 'עשרים');
    expect(n(62), 'שישים ושתיים');
    expect(n(100), 'מאה');
    expect(n(121), 'מאה עשרים ואחת');
    expect(n(205), 'מאתיים וחמש');
    expect(n(1000), 'אלף');
    expect(n(1500), 'אלף וחמש מאות');
    expect(n(2026), 'אלפיים עשרים ושש');
    expect(n(3000), 'שלושת אלפים');
    expect(n(21000), 'עשרים ואחד אלף');
    expect(n(125000), 'מאה עשרים וחמישה אלף');
    expect(n(1500000), 'מיליון וחמש מאות אלף');
    expect(n(2000000), 'שני מיליון');
    expect(n(3000000000), 'שלושה מיליארד');
  });

  test('percentages, currencies and decimals', () {
    expect(norm('שיעור ההצבעה: 62%'), 'שיעור ההצבעה: שישים ושתיים אחוז');
    expect(norm('51.8%'), 'חמישים ואחת נקודה שמונה אחוז');
    expect(norm('עלה ב-3.5 מיליון'), 'עלה בשלוש נקודה חמש מיליון');
    expect(norm('100 ₪'), 'מאה שקלים');
    expect(norm(r'$50'), 'חמישים דולר');
    expect(norm('1₪'), 'שקל אחד');
    expect(norm('1,200'), 'אלף ומאתיים');
  });

  test('times, dates, ranges and prefixes', () {
    expect(norm('בשעה 22:00'), 'בשעה עשרים ושתיים');
    expect(norm('19:28'), 'תשע עשרה עשרים ושמונה');
    expect(norm('28/10'), 'עשרים ושמונה לעשירי');
    expect(norm('28.10.2026'), 'עשרים ושמונה לעשירי אלפיים עשרים ושש');
    expect(norm('10-12 מעלות'), 'עשר עד שתים עשרה מעלות');
    expect(norm('ב-2026'), 'באלפיים עשרים ושש');
    expect(norm('ה־60'), 'השישים');
    expect(norm('טלפון 03'), 'טלפון אפס שלוש');
  });

  test('splits sentences and paragraphs', () {
    expect(HebrewText.sentences('שלום. מה נשמע?\n\nפסקה חדשה'), [('שלום.', false), ('מה נשמע?', true), ('פסקה חדשה', true)]);
    final long = ('מילה ' * 100).trim();
    for (final (chunk, _) in HebrewText.sentences(long)) {
      expect(chunk.length, lessThanOrEqualTo(280));
    }
    // Commas are preferred break points for long sentences.
    final commas = List.filled(40, 'אחת שתיים שלוש,').join(' ');
    expect(HebrewText.sentences(commas).first.$1, endsWith(','));
  });

  test('the first utterance is short, so playback starts quickly', () {
    final post = '${List.filled(12, 'ראש הממשלה נפגש היום,').join(' ')} ובסוף הלך הביתה.';
    final parts = HebrewText.sentences(post);
    expect(parts.first.$1.length, lessThanOrEqualTo(120));
    expect(parts.skip(1).every((p) => p.$1.length <= 220), isTrue);
    expect(parts.last.$2, isTrue, reason: 'the paragraph still ends after the last part');
    expect(parts.map((p) => p.$1).join(' ').replaceAll(' ', ''), post.replaceAll(' ', ''));
    expect(HebrewText.sentences('קצר.'), [('קצר.', true)]);
  });

  test('the default voice is the recommended Kokoro', () {
    expect(VoiceCatalog.voices.first.id, VoiceCatalog.recommendedId);
  });
}
