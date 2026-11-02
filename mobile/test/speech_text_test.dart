import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/text/speech_text.dart';

void main() {
  test('removesEmoji', () => expect(SpeechText.clean("Hello 🔥 world"), "Hello world."));
  test('keepsWordsApartWhenEmojiSeparatesThem', () => expect(SpeechText.clean("Done✅Next"), "Done Next."));
  test('keycapDigits', () => expect(SpeechText.clean("1️⃣3️⃣ years"), "13 years."));
  test('flagsAndZwjSequences', () => expect(SpeechText.clean("Team 👨‍👩‍👧‍👦🇮🇱"), "Team."));
  test('removesUrls', () => expect(SpeechText.clean("Read more: https://www.ynet.co.il/news/article/abc?x=1"), "Read more:"));
  test('removesBareDomains', () => expect(SpeechText.clean("Full story on ynet.co.il"), "Full story on."));
  test('hashtagsAndMentions', () => expect(SpeechText.clean("#breaking_news via @kann_news"), "breaking news via kann news."));
  test('addsSentenceEnds', () => expect(SpeechText.clean("Line one\nLine two!"), "Line one.\nLine two!"));
  test('dropsSeparatorLines', () => expect(SpeechText.clean("Title\n——————\nBody"), "Title.\nBody."));
  test('dropsBullets', () => expect(SpeechText.clean("• first\n▪️ second"), "first.\nsecond."));
  test('collapsesRepeatedPunctuation', () => expect(SpeechText.clean("Wow!!!!"), "Wow!"));
  test('hebrew', () => expect(SpeechText.clean("פסח הוא חג החירות - אבל לא של כולם 😥"), "פסח הוא חג החירות - אבל לא של כולם."));
  test('emptyWhenOnlySymbols', () => expect(SpeechText.clean("🇦🇫😈\n———"), ""));
  test('keepsParagraphBreaks', () => expect(SpeechText.clean("One\n\n\n\nTwo"), "One.\n\nTwo."));
}
