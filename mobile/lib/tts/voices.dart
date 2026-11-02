import '../text/language_segmenter.dart';

class NeuralVoice {
  const NeuralVoice(this.id, this.label, {required this.male});
  final String id;
  final String label;
  final bool male;
}

/// Curated Microsoft neural voices (Azure Speech). Multilingual voices can also read Hebrew.
class Voices {
  Voices._();

  static const hebrew = [
    NeuralVoice('he-IL-AvriNeural', 'Avri', male: true),
    NeuralVoice('he-IL-HilaNeural', 'Hila', male: false),
    NeuralVoice('en-US-AndrewMultilingualNeural', 'Andrew (multilingual)', male: true),
    NeuralVoice('en-US-AvaMultilingualNeural', 'Ava (multilingual)', male: false),
    NeuralVoice('en-US-EmmaMultilingualNeural', 'Emma (multilingual)', male: false),
    NeuralVoice('en-US-BrianMultilingualNeural', 'Brian (multilingual)', male: true),
  ];

  static const english = [
    NeuralVoice('en-US-AndrewNeural', 'Andrew (US)', male: true),
    NeuralVoice('en-US-AvaNeural', 'Ava (US)', male: false),
    NeuralVoice('en-US-EmmaNeural', 'Emma (US)', male: false),
    NeuralVoice('en-US-BrianNeural', 'Brian (US)', male: true),
    NeuralVoice('en-US-AriaNeural', 'Aria (US, news)', male: false),
    NeuralVoice('en-US-GuyNeural', 'Guy (US, news)', male: true),
    NeuralVoice('en-US-JennyNeural', 'Jenny (US)', male: false),
    NeuralVoice('en-US-ChristopherNeural', 'Christopher (US, news)', male: true),
    NeuralVoice('en-GB-SoniaNeural', 'Sonia (UK)', male: false),
    NeuralVoice('en-GB-RyanNeural', 'Ryan (UK)', male: true),
    NeuralVoice('en-US-AndrewMultilingualNeural', 'Andrew (multilingual)', male: true),
    NeuralVoice('en-US-AvaMultilingualNeural', 'Ava (multilingual)', male: false),
  ];

  static const defaultHebrew = 'he-IL-AvriNeural';
  static const defaultEnglish = 'en-US-AndrewNeural';
  static const _arabic = 'ar-SA-HamedNeural';
  static const _russian = 'ru-RU-DmitryNeural';

  static String neuralFor(Lang lang, String hebrewVoice, String englishVoice) => switch (lang) {
        Lang.he => hebrewVoice,
        Lang.en => englishVoice,
        Lang.ar => _arabic,
        Lang.ru => _russian,
      };

  static String voiceKey(String hebrewVoice, String englishVoice) => '$hebrewVoice|$englishVoice|$_arabic|$_russian';
}
