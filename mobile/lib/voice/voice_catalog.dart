import 'dart:io';

import 'engines.dart';
import 'hebrew_neural_voice.dart';

/// A file of a downloadable voice, pinned to a revision and checked by size and SHA-256.
class ModelFile {
  const ModelFile(this.name, this.url, this.size, this.sha256);
  final String name;
  final String url;
  final int size;
  final String sha256;
}

/// A downloadable offline Hebrew voice. Files with the same [ModelFile.name] are shared between
/// voices (the renikud phonemizer, the Blue engine) and downloaded once.
class VoicePack {
  const VoicePack({
    required this.id,
    required this.name,
    required this.male,
    required this.heavy,
    required this.license,
    required this.files,
    required this.model,
  });

  final String id;
  final String name;
  final bool male;

  /// Natural-sounding but several times slower to render than the fast voices.
  final bool heavy;
  final String license;
  final List<ModelFile> files;

  /// The engine's files inside a models directory.
  final VoiceModel Function(String dir) model;

  int get totalBytes => files.fold(0, (a, f) => a + f.size);
}

/// The offline voices on offer, all speaking renikud IPA. The first installed one is the default,
/// so Kokoro (the most natural) comes first. The same files, revisions and checksums
/// as the Android app's VoiceCatalog.
abstract final class VoiceCatalog {
  static const g2pFile = 'renikud.onnx';

  static const _hf = 'https://huggingface.co';
  static const _renikudUrl = '$_hf/renikud/renikud/resolve/a59c632c8cbac0f8c63f298cad77ef3a3a2547c9';
  static const _noaUrl = '$_hf/thewh1teagle/piper-he-noa/resolve/23f8e06909e9a1e562a50f99df63e3785451379a';
  static const _phonikudUrl = '$_hf/Phonikud/phonikud-tts-checkpoints/resolve/ff64ba389a3833c49a85c58fc51a7060a6984b87';
  static const _kokoroUrl = '$_hf/thewh1teagle/kokoro-hebrew-nc/resolve/07e2334fc252d7965cb8dbd503bf7565bad71228';
  static const _blueUrl = '$_hf/notmax123/BlueTTS2.5-onnx/resolve/95feb5d0cbfc8e5a95bffb067d03537cbf35be3e';
  static const _blueDir = 'blue25';

  static const _renikud = ModelFile(g2pFile, '$_renikudUrl/model.onnx', 20620594, '8b881a3a8f00283d86c6d1feea44d37e09c1ea6609a4de7d820d937e7f3dbbca');
  static const _phonikudConfig = ModelFile(
    'phonikud-piper.config.json', '$_phonikudUrl/model.config.json', 7073,
    '7f790dce7e26969a535ecda2715cc7da9c5261269cbf11c213d107b611458679',
  );
  static const _blueEngine = [
    ModelFile('$_blueDir/${BlueVoice.durationFile}', '$_blueUrl/${BlueVoice.durationFile}', 1508749, '3d0347cfc09234b78a20d5fa50b5c402d338fb99825f40ada8c70301edbb60ea'),
    ModelFile('$_blueDir/${BlueVoice.textEncoderFile}', '$_blueUrl/${BlueVoice.textEncoderFile}', 27419694, 'f971aa25315b93efff1850b39fe293aa93ae85df69fabf5e5022318cc632973c'),
    ModelFile('$_blueDir/${BlueVoice.vectorFieldFile}', '$_blueUrl/${BlueVoice.vectorFieldFile}', 132475789, '50557b34acfafa4b34f21dae263faa74d3f3169f114ff927599a1eff110d0b6e'),
    ModelFile('$_blueDir/${BlueVoice.vocoderFile}', '$_blueUrl/${BlueVoice.vocoderFile}', 101411919, 'b0709df316ff44cc263c04fbf9d17371b16897071a48b79d3634d5f379fb3067'),
    ModelFile('$_blueDir/${BlueVoice.configFile}', '$_blueUrl/${BlueVoice.configFile}', 9260, '862d44194197de4873eccc8bfdab1835d15f98e469a90489df6da8f92a48c7ea'),
    ModelFile('$_blueDir/${BlueVoice.vocabFile}', '$_blueUrl/${BlueVoice.vocabFile}', 3157, '13fba5e735222fcf30d9c062d898982a1ba86d481f11560fbbdf6e0af79417b3'),
    ModelFile('$_blueDir/${BlueVoice.uncondFile}', '$_blueUrl/${BlueVoice.uncondFile}', 52732, '1c71c2b2836fd97f4c56eb21e258227650bbe0dcd906f64e2bba58e6ac09e76b'),
    ModelFile('$_blueDir/${BlueVoice.statsFile}', '$_blueUrl/${BlueVoice.statsFile}', 1920, '20d44fae010d46b859ee6238609780eb7bd5bf82320bad17b826a5dcfa4ed658'),
  ];

  static VoicePack _blue(String id, String name, bool male, String style, int size, String sha) {
    final styleFile = ModelFile('$_blueDir/voices/$style.json', '$_blueUrl/voices/$style.json', size, sha);
    return VoicePack(
      id: id, name: name, male: male, heavy: true, license: 'MIT',
      files: [_renikud, ..._blueEngine, styleFile],
      model: (dir) => BlueModel('$dir/$_blueDir', '$dir/${styleFile.name}'),
    );
  }

  static VoicePack _phonikud(String id, String name, String file, String sha) => VoicePack(
        id: id, name: name, male: true, heavy: false, license: 'CC BY-NC (non-commercial)',
        files: [_renikud, ModelFile('phonikud-$file', '$_phonikudUrl/$file', 63516050, sha), _phonikudConfig],
        model: (dir) => PiperModel('$dir/phonikud-$file', '$dir/${_phonikudConfig.name}'),
      );

  static final List<VoicePack> voices = [
    VoicePack(
      id: 'kokoro-shaul', name: 'Kokoro · Shaul', male: true, heavy: true, license: 'Non-commercial',
      files: const [
        _renikud,
        ModelFile('kokoro-he.onnx', '$_kokoroUrl/kokoro.onnx', 325505393, '6b9b16fbba87fec17a21cd3b11203286e1e657014dbab236703fdf0cf13a9a2f'),
        ModelFile('kokoro-he-voices.npz', '$_kokoroUrl/voices-hebrew.bin', 522510, '5efaa05741532c144a535677f02e02fd72c76fd8ec45840d6e3194b38194e9a7'),
        ModelFile('kokoro-he-config.json', '$_kokoroUrl/config.json', 2526, '042f62ac55870757c2945a3a8d4b7fb01a9f9f775c12b1ef09f41fef1efd24f5'),
      ],
      model: (dir) => KokoroModel('$dir/kokoro-he.onnx', '$dir/kokoro-he-voices.npz', '$dir/kokoro-he-config.json', 'he_shaul'),
    ),
    VoicePack(
      id: 'noa', name: 'Noa', male: false, heavy: false, license: 'CC BY 4.0',
      files: const [
        _renikud,
        ModelFile('he_IL-noa-medium.onnx.json', '$_noaUrl/he_IL-noa-medium.onnx.json', 5048, '109a7a2a0739061510f318d987e81c27ba81ca57c9303444298f712f71b1082c'),
        ModelFile('he_IL-noa-medium.onnx', '$_noaUrl/he_IL-noa-medium.onnx', 77056101, '83b18c0b8fad6ac00fcea5c5bdf3c1fdbec182f9eadad9f97bc423ab4e22be7d'),
      ],
      model: (dir) => PiperModel('$dir/he_IL-noa-medium.onnx', '$dir/he_IL-noa-medium.onnx.json', 'noa'),
    ),
    _phonikud('shaul', 'Shaul', 'shaul.onnx', '7bcbd364b0bc357df8ef6c7c873e48f22897df16ec6c9d8f9d7ef792f6ae03c9'),
    _phonikud('michael', 'Michael', 'michael.onnx', 'd2824d46ecd7ca8a206686d818d3178effe4661ce78bdb4e754eed32fe604320'),
    _blue('blue-female', 'Blue · female', false, 'female', 288787, '2d15c822bd38005e1f59f47eea07cc3540d4ad3fc52b85d46dce04e5145a7494'),
    _blue('blue-male', 'Blue · male', true, 'libri_male_8088', 288870, '3ad60cb68f8838dc00bacac968d55fd8469c1453283f27a210e15114671fb240'),
  ];

  /// The voice suggested to new users.
  static const recommendedId = 'kokoro-shaul';

  static VoicePack? byId(String? id) {
    for (final v in voices) {
      if (v.id == id) return v;
    }
    return null;
  }

  /// All files of [pack] are present in [dir] with the expected sizes.
  static bool isInstalled(String dir, VoicePack pack) => pack.files.every((f) {
        final file = File('$dir/${f.name}');
        return file.existsSync() && file.lengthSync() == f.size;
      });

  static HebrewNeuralVoice open(String dir, VoicePack pack) => HebrewNeuralVoice('$dir/$g2pFile', pack.model(dir));
}
