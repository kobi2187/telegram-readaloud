import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:unorm_dart/unorm_dart.dart' as unorm;

import 'npy.dart';
import 'ort.dart';

/// A voice that speaks stressed Hebrew IPA (the renikud/Phonikud convention).
abstract class IpaEngine {
  int get sampleRate;

  /// 16-bit mono speech at [sampleRate], peak-normalized; empty if nothing could be spoken.
  Int16List speak(String ipa);

  void close();
}

/// Which model files make up a voice; every engine shares the renikud phonemizer.
sealed class VoiceModel {
  const VoiceModel();
  IpaEngine open();
}

/// A Piper (VITS) voice; [speaker] names one voice of a multi-speaker model.
class PiperModel extends VoiceModel {
  const PiperModel(this.model, this.config, [this.speaker]);
  final String model;
  final String config;
  final String? speaker;

  @override
  IpaEngine open() => PiperVoice(model, File(config).readAsStringSync(), speaker: speaker);
}

/// A Kokoro model with its style table ([voices] `.npz`) and phoneme vocabulary ([config]).
class KokoroModel extends VoiceModel {
  const KokoroModel(this.model, this.voices, this.config, this.voice);
  final String model;
  final String voices;
  final String config;
  final String voice;

  @override
  IpaEngine open() => KokoroVoice(model, voices, config, voice);
}

/// A BlueTTS bundle directory and one of its voice style files.
class BlueModel extends VoiceModel {
  const BlueModel(this.dir, this.style);
  final String dir;
  final String style;

  @override
  IpaEngine open() => BlueVoice(dir, style);
}

/// Scales [audio] so its loudest sample reaches [peak], as 16-bit samples.
Int16List peakNormalize(Float32List audio, {double peak = 0.95}) {
  var max = 0.0;
  for (final v in audio) {
    if (v.isFinite && v.abs() > max) max = v.abs();
  }
  final gain = max > 1e-8 ? peak / max : 0.0;
  final out = Int16List(audio.length);
  for (var k = 0; k < audio.length; k++) {
    final v = audio[k].isFinite ? audio[k] : 0.0;
    out[k] = ((v * gain).clamp(-1.0, 1.0) * 32767).toInt();
  }
  return out;
}

List<String> _codePoints(String s) => s.runes.map(String.fromCharCode).toList();

/// A Piper (VITS) voice exported to ONNX, driven by IPA phonemes.
class PiperVoice implements IpaEngine {
  PiperVoice(String modelPath, String configJson, {String? speaker}) : _model = OnnxModel.open(modelPath) {
    final cfg = jsonDecode(configJson) as Map<String, dynamic>;
    _idMap = (cfg['phoneme_id_map'] as Map<String, dynamic>).map((k, v) => MapEntry(k, [for (final x in v as List) (x as num).toInt()]));
    sampleRate = ((cfg['audio'] as Map)['sample_rate'] as num).toInt();
    final speakers = (cfg['speaker_id_map'] as Map<String, dynamic>?) ?? const {};
    _speakerId = speaker == null ? 0 : ((speakers[speaker] as num?)?.toInt() ?? 0);
    final inference = cfg['inference'] as Map<String, dynamic>?;
    _noiseScale = (inference?['noise_scale'] as num?)?.toDouble() ?? 0.667;
    _lengthScale = (inference?['length_scale'] as num?)?.toDouble() ?? 1.0;
    _noiseW = (inference?['noise_w'] as num?)?.toDouble() ?? 0.8;
  }

  final OnnxModel _model;
  late final Map<String, List<int>> _idMap;
  late final int _speakerId;
  late final double _noiseScale;
  late final double _lengthScale;
  late final double _noiseW;
  @override
  late final int sampleRate;

  /// Piper's id layout: BOS, PAD, then each phoneme followed by PAD, then EOS; unknown symbols are dropped.
  Int64List phonemeIds(String ipa) {
    final ids = <int>[..._idMap['^']!, ..._idMap['_']!];
    for (final symbol in _codePoints(ipa)) {
      final id = _idMap[symbol];
      if (id != null) {
        ids
          ..addAll(id)
          ..addAll(_idMap['_']!);
      }
    }
    ids.addAll(_idMap[r'$']!);
    return Int64List.fromList(ids);
  }

  @override
  Int16List speak(String ipa) {
    final ids = phonemeIds(ipa);
    if (ids.length <= 3) return Int16List(0);
    final inputs = {
      'input': Tensor.i64(ids, [1, ids.length]),
      'input_lengths': Tensor.i64(Int64List.fromList([ids.length]), [1]),
      'scales': Tensor.f32(Float32List.fromList([_noiseScale, _lengthScale, _noiseW]), [3]),
      if (_model.inputNames.contains('sid')) 'sid': Tensor.i64(Int64List.fromList([_speakerId]), [1]),
    };
    return peakNormalize(_model.run(inputs).first.data);
  }

  @override
  void close() => _model.close();
}

/// A Kokoro (StyleTTS2-style) voice driven by IPA, as kokoro-onnx runs it: token ids padded with
/// 0 on both ends, and a style vector chosen by the number of phonemes.
class KokoroVoice implements IpaEngine {
  KokoroVoice(String modelPath, String voicesPath, String configPath, String voice) : _model = OnnxModel.open(modelPath) {
    final cfg = jsonDecode(File(configPath).readAsStringSync()) as Map<String, dynamic>;
    _vocab = (cfg['vocab'] as Map<String, dynamic>).map((k, v) => MapEntry(k, (v as num).toInt()));
    final styles = Npy.readArchive(File(voicesPath))[voice];
    if (styles == null) throw StateError('No Kokoro voice $voice');
    _styles = styles;
    _styleDim = styles.shape.last;
    _styleRows = styles.data.length ~/ _styleDim;
    _tokensInput = _model.inputNames.contains('input_ids') ? 'input_ids' : 'tokens';
  }

  static const _maxTokens = 510;
  final OnnxModel _model;
  late final Map<String, int> _vocab;
  late final NpyArray _styles;
  late final int _styleDim;
  late final int _styleRows;
  late final String _tokensInput;

  @override
  int get sampleRate => 24000;

  @override
  Int16List speak(String ipa) {
    final tokens = [for (final c in _codePoints(ipa)) ?_vocab[c]];
    if (tokens.isEmpty) return Int16List(0);
    final parts = <Float32List>[];
    for (var i = 0; i < tokens.length; i += _maxTokens) {
      parts.add(_infer(tokens.sublist(i, min(i + _maxTokens, tokens.length))));
    }
    final all = Float32List(parts.fold(0, (a, p) => a + p.length));
    var pos = 0;
    for (final p in parts) {
      all.setAll(pos, p);
      pos += p.length;
    }
    return peakNormalize(all);
  }

  Float32List _infer(List<int> tokens) {
    final ids = Int64List(tokens.length + 2)..setAll(1, tokens);
    final row = min(tokens.length, _styleRows) - 1;
    final style = Float32List.sublistView(_styles.data, row * _styleDim, (row + 1) * _styleDim);
    return _model.run({
      _tokensInput: Tensor.i64(ids, [1, ids.length]),
      'style': Tensor.f32(Float32List.fromList(style), [1, _styleDim]),
      'speed': Tensor.f32(Float32List.fromList([1]), [1]),
    }).first.data;
  }

  @override
  void close() => _model.close();
}

/// A BlueTTS voice (flow matching, 44.1 kHz), following the reference blue-onnx pipeline: predict
/// the duration, encode the phonemes, denoise a random latent in a few guided steps, then vocode.
class BlueVoice implements IpaEngine {
  BlueVoice(this.dir, String stylePath, {Random? random}) : _random = random ?? Random() {
    _duration = OnnxModel.open('$dir/$durationFile');
    _textEncoder = OnnxModel.open('$dir/$textEncoderFile');
    _vectorField = OnnxModel.open('$dir/$vectorFieldFile');
    _vocoder = OnnxModel.open('$dir/$vocoderFile');
    final cfg = jsonDecode(File('$dir/$configFile').readAsStringSync()) as Map<String, dynamic>;
    final ae = cfg['ae'] as Map<String, dynamic>;
    final ttlCfg = cfg['ttl'] as Map<String, dynamic>;
    sampleRate = (ae['sample_rate'] as num).toInt();
    _latentDim = (ttlCfg['latent_dim'] as num).toInt();
    _compress = (ttlCfg['chunk_compress_factor'] as num).toInt();
    _frame = (ae['base_chunk_size'] as num).toInt() * _compress;

    final vocab = jsonDecode(File('$dir/$vocabFile').readAsStringSync()) as Map<String, dynamic>;
    _padId = (vocab['pad_id'] as num?)?.toInt() ?? 0;
    _charIds = (vocab['char_to_id'] as Map<String, dynamic>).map((k, v) => MapEntry(k, (v as num).toInt()));

    final style = jsonDecode(File(stylePath).readAsStringSync()) as Map<String, dynamic>;
    _ttl = _styleTensor(style['style_ttl'] as Map<String, dynamic>);
    _dp = _styleTensor(style['style_dp'] as Map<String, dynamic>);

    final uncond = Npy.readArchive(File('$dir/$uncondFile'));
    _uText = uncond['u_text']!;
    _uRef = uncond['u_ref']!;

    final stats = File('$dir/$statsFile');
    if (stats.existsSync()) {
      final arrays = Npy.readArchive(stats);
      _mean = arrays['mean']?.data;
      _std = arrays['std']?.data;
      final scale = arrays['normalizer_scale']?.data;
      _normalizerScale = scale != null && scale.isNotEmpty && scale.first != 0 ? scale.first : 1.0;
    }
    // Bundles whose vocoder takes the raw latent ship de-normalization stats; others take the
    // compressed latent as is. Try the de-normalized layout once, on a tiny input.
    _vocoderTakesLatent = _mean != null && _std != null && _probeVocoder();
  }

  static const durationFile = 'duration_predictor_style.onnx';
  static const textEncoderFile = 'text_encoder.onnx';
  static const vectorFieldFile = 'vector_estimator.onnx';
  static const vocoderFile = 'vocoder.onnx';
  static const configFile = 'tts.json';
  static const vocabFile = 'vocab.json';
  static const uncondFile = 'uncond.npz';
  static const statsFile = 'stats.npz';
  static const _steps = 5;
  static const _cfgScale = 4.0;

  final String dir;
  final Random _random;
  late final OnnxModel _duration;
  late final OnnxModel _textEncoder;
  late final OnnxModel _vectorField;
  late final OnnxModel _vocoder;
  late final int _latentDim;
  late final int _compress;
  late final int _frame;
  late final int _padId;
  late final Map<String, int> _charIds;
  late final NpyArray _ttl;
  late final NpyArray _dp;
  late final NpyArray _uText;
  late final NpyArray _uRef;
  Float32List? _mean;
  Float32List? _std;
  double _normalizerScale = 1;
  late final bool _vocoderTakesLatent;
  @override
  late final int sampleRate;

  bool _probeVocoder() {
    try {
      _vocoder.run({'latent': Tensor.f32(Float32List(_latentDim * _compress * 2), [1, _latentDim, _compress * 2])});
      return true;
    } catch (_) {
      return false;
    }
  }

  static NpyArray _styleTensor(Map<String, dynamic> o) {
    final dims = [for (final d in o['dims'] as List) (d as num).toInt()];
    final values = <double>[];
    void flatten(Object? e) {
      if (e is List) {
        e.forEach(flatten);
      } else if (e is num) {
        values.add(e.toDouble());
      }
    }

    flatten(o['data']);
    return NpyArray(dims, Float32List.fromList(values));
  }

  static Tensor _t(NpyArray a) => Tensor.f32(a.data, a.shape);

  @override
  Int16List speak(String ipa) {
    var text = unorm.nfkd(ipa).replaceAll(RegExp(r'\s+'), ' ').trim();
    if (text.isEmpty) return Int16List(0);
    if (!RegExp('[.!?;:,\'")\\]}…]\$').hasMatch(text)) text += '.';
    final ids = Int64List.fromList([for (final c in _codePoints(text)) _charIds[c] ?? _padId]);
    return peakNormalize(_infer(ids));
  }

  double _gaussian() {
    // Box–Muller.
    final u1 = 1.0 - _random.nextDouble();
    final u2 = _random.nextDouble();
    return sqrt(-2 * log(u1)) * cos(2 * pi * u2);
  }

  Float32List _infer(Int64List textIds) {
    final tLen = textIds.length;
    final ids = Tensor.i64(textIds, [1, tLen]);
    final textMask = Tensor.f32(Float32List(tLen)..fillRange(0, tLen, 1), [1, 1, tLen]);
    final styleTtl = _t(_ttl);
    final seconds = _duration.run({'text_ids': ids, 'style_dp': _t(_dp), 'text_mask': textMask}).first.data[0];
    final textEmb = _textEncoder.run({'text_ids': ids, 'style_ttl': styleTtl, 'text_mask': textMask}).first.data;
    final emb = Tensor.f32(textEmb, [1, textEmb.length ~/ tLen, tLen]);
    final tLat = (max(1, (seconds * sampleRate).toInt()) + _frame - 1) ~/ _frame;
    final channels = _latentDim * _compress;
    var xt = Float32List(channels * tLat);
    for (var k = 0; k < xt.length; k++) {
      xt[k] = _gaussian();
    }
    final latentMask = Tensor.f32(Float32List(tLat)..fillRange(0, tLat, 1), [1, 1, tLat]);
    final total = Tensor.f32(Float32List.fromList([_steps.toDouble()]), [1]);
    final uMask = Tensor.f32(Float32List.fromList([1]), [1, 1, 1]);
    for (var step = 0; step < _steps; step++) {
      final current = Tensor.f32(Float32List.fromList([step.toDouble()]), [1]);
      final noisy = Tensor.f32(xt, [1, channels, tLat]);
      Float32List field(Tensor text, Tensor style, Tensor mask) => _vectorField.run({
            'noisy_latent': noisy,
            'text_emb': text,
            'style_ttl': style,
            'text_mask': mask,
            'latent_mask': latentMask,
            'current_step': current,
            'total_step': total,
          }).first.data;
      final cond = field(emb, styleTtl, textMask);
      final uncond = field(_t(_uText), _t(_uRef), uMask);
      final next = Float32List(cond.length);
      for (var k = 0; k < cond.length; k++) {
        next[k] = uncond[k] + _cfgScale * (cond[k] - uncond[k]);
      }
      xt = next;
    }
    final Float32List latent;
    final int latentChannels;
    if (_vocoderTakesLatent) {
      latent = _decompress(xt, tLat);
      latentChannels = _latentDim;
    } else {
      latent = xt;
      latentChannels = channels;
    }
    final wav = _vocoder.run({'latent': Tensor.f32(latent, [1, latentChannels, latent.length ~/ latentChannels])}).first.data;
    return wav.length > 2 * _frame ? Float32List.sublistView(wav, _frame, wav.length - _frame) : wav;
  }

  /// De-normalizes and unfolds (ldim*f, T) channel-major into (ldim, T*f), as the vocoder expects.
  Float32List _decompress(Float32List xt, int t) {
    final m = _mean!;
    final s = _std!;
    final out = Float32List(xt.length);
    for (var l = 0; l < _latentDim; l++) {
      for (var j = 0; j < _compress; j++) {
        final ch = l * _compress + j;
        for (var k = 0; k < t; k++) {
          out[l * t * _compress + k * _compress + j] = (xt[ch * t + k] / _normalizerScale) * s[ch] + m[ch];
        }
      }
    }
    return out;
  }

  @override
  void close() {
    _duration.close();
    _textEncoder.close();
    _vectorField.close();
    _vocoder.close();
  }
}
