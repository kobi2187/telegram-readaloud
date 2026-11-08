import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/tts/audio.dart';
import 'package:tg_read_aloud/voice/hebrew_g2p.dart';
import 'package:tg_read_aloud/voice/voice_catalog.dart';
import 'package:tg_read_aloud/voice/voice_worker.dart';

/// Runs the real models: set VOICE_MODELS_DIR to a folder laid out like the app's (catalog file
/// names), and make ONNX Runtime loadable (LD_LIBRARY_PATH on Linux). The expected IPA was produced
/// by the reference Python package renikud-onnx 0.1.1 with the same model, as in the Android test.
void main() {
  final dir = Platform.environment['VOICE_MODELS_DIR'] ?? '';
  final skip = File('$dir/${VoiceCatalog.g2pFile}').existsSync() ? null : 'set VOICE_MODELS_DIR';

  const reference = [
    ('ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.', 'ʁˈoʃ hamemʃalˈa nifɡˈaʃ habokˈeʁ ʔˈim sˈaʁ habitaχˈon bjeʁuʃalˈajim.'),
    ('התחזית: גשם מקומי בצפון ובמרכז, ירידה בטמפרטורות.', 'hataχazˈet: ɡˈeʃem mekomˈi batsafˈon vebameʁkˈaz, jeʁidˈa batempeʁatˈuʁot.'),
    ('הנשיא טראמפ פרסם הודעה על תמיכתו בהסכם החדש.', 'hanasˈi tʁˈamp piʁsˈem hodaʔˈa ʔˈal tmiχatˈo baheskˈem haχadˈaʃ.'),
    ('צה"ל תקף הלילה מטרות בדרום לבנון, דיווחו בארה"ב.', 'tsahal takˈaf halˈajla mataʁˈot bidʁˈom levanˈon, divχˈu beʔaʁhav.'),
    ("ג'ורג' נסע לצ'ילה בשבוע שעבר עם Google ו-Apple.", 'dʒˈoʁdʒe nasˈa letʃilˈa beʃavˈua ʃeʔavˈaʁ ʔˈim Google ve-Apple.'),
    ('מחר בבוקר יתקיים דיון חשוב בכנסת על התקציב.', 'maχˈaʁ babˈokeʁ jitkajˈem dijˈun χaʃˈuv baknˈeset ʔˈal hataktsˈiv.'),
  ];

  test('the phonemizer matches the reference implementation', () {
    final g2p = HebrewG2p('$dir/${VoiceCatalog.g2pFile}');
    try {
      for (final (text, ipa) in reference) {
        expect(g2p.phonemize(text), ipa, reason: text);
      }
    } finally {
      g2p.close();
    }
  }, skip: skip);

  const sentences = [
    'ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.',
    'חצי שעה לסגירת הקלפיות, ושיעור ההצבעה עומד על 62%.',
  ];

  for (final pack in VoiceCatalog.voices) {
    test('${pack.id} speaks', () {
      final voice = VoiceCatalog.open(dir, pack);
      try {
        for (final (i, text) in sentences.indexed) {
          final watch = Stopwatch()..start();
          final pcm = voice.synthesizeAll(text);
          final seconds = pcm.length / voice.sampleRate;
          final rms = sqrt(pcm.fold<double>(0, (a, s) => a + s * s) / max(1, pcm.length));
          // ignore: avoid_print
          print('${pack.id} $i: ${seconds.toStringAsFixed(1)} s, RTF ${(watch.elapsedMilliseconds / 1000 / seconds).toStringAsFixed(2)}, rms ${rms.round()}');
          expect(seconds, inInclusiveRange(text.length / 25, text.length / 6), reason: '${pack.id}: plausible length');
          expect(rms, greaterThan(1000), reason: '${pack.id}: not silence or noise floor');
          final out = Platform.environment['VOICE_OUT'];
          if (out != null) File('$out/dart_${pack.id}_$i.wav').writeAsBytesSync(Wav.join([Pcm(Int16List.fromList(pcm), voice.sampleRate)]));
        }
        expect(voice.lastIpa, contains('ˈ'));
      } finally {
        voice.close();
      }
    }, skip: skip ?? (VoiceCatalog.isInstalled(dir, pack) ? null : '${pack.id} files missing'), timeout: const Timeout(Duration(minutes: 5)));
  }

  test('the worker isolate streams sentences, stops when cancelled, and probes', () async {
    final worker = VoiceWorker(threads: 2);
    const text = 'ראש הממשלה נפגש הבוקר עם שר הביטחון. הוא דן איתו במצב בצפון. הפגישה נמשכה שעה.';
    final parts = await worker.speak(dir, 'noa', text).toList();
    expect(parts, hasLength(3));
    expect(parts.every((p) => p.sampleRate == 22050 && p.samples.length > 22050), isTrue);

    var got = 0;
    final sub = worker.speak(dir, 'noa', text).listen((_) => got++);
    while (got == 0) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    await sub.cancel();
    // A new request is served promptly after the cancelled one.
    final next = await worker.speak(dir, 'noa', 'שלום.').toList();
    expect(next, hasLength(1));
    expect(got, lessThan(3));

    final rtf = await worker.probe(dir, 'noa');
    expect(rtf, inExclusiveRange(0, 5));
    await expectLater(worker.probe('/nonexistent', 'noa'), throwsA(isA<VoiceUnavailableException>()));
    await expectLater(worker.speak('/nonexistent', 'shaul', 'שלום').toList(), throwsA(isA<VoiceUnavailableException>()));
    await worker.unload('noa');
  }, skip: skip ?? (VoiceCatalog.isInstalled(dir, VoiceCatalog.byId('noa')!) ? null : 'noa files missing'));

  test('a voice speaks through the Phonikud pipeline, with or without stress marks', () {
    final pack = VoiceCatalog.byId('noa')!;
    final voice = VoiceCatalog.open(dir, pack, phonikud: true);
    try {
      const text = 'ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.';
      final pcm = voice.synthesizeAll(text);
      expect(voice.lastIpa, 'ʁˈoʃ hamemʃalˈa nifɡˈaʃ habokˈeʁ ʔˈim sˈaʁ habitaχˈon biʁuʃalˈajim.');
      expect(pcm.length / voice.sampleRate, inInclusiveRange(text.length / 25, text.length / 6));
      voice.stress = false;
      voice.synthesizeAll(text);
      expect(voice.lastIpa, isNot(contains('ˈ')));
    } finally {
      voice.close();
    }
  },
      skip: skip ??
          (VoiceCatalog.isInstalled(dir, VoiceCatalog.byId('noa')!) && VoiceCatalog.isInstalled(dir, VoiceCatalog.phonikud)
              ? null
              : 'Noa or Phonikud files missing'));
}
