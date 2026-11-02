import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

/// 16-bit mono samples, the common form for joining audio from different engines.
class Pcm {
  Pcm(this.samples, this.sampleRate);
  final Int16List samples;
  final int sampleRate;

  int get durationMs => samples.length * 1000 ~/ sampleRate;

  Pcm resampled(int rate) => rate == sampleRate ? this : Pcm(Resampler.resample(samples, sampleRate, rate), rate);

  static Pcm silence(int rate, int ms) => Pcm(Int16List(rate * ms ~/ 1000), rate);
}

/// A WAV file's format and PCM bytes.
class WavClip {
  WavClip(this.sampleRate, this.channels, this.bitsPerSample, this.pcm);
  final int sampleRate;
  final int channels;
  final int bitsPerSample;
  final Uint8List pcm;

  /// 32-bit float samples (iOS writes speech this way).
  bool isFloat = false;

  /// As 16-bit mono, mixing stereo down.
  Pcm toPcm() {
    final data = ByteData.sublistView(pcm);
    final bytesPer = bitsPerSample ~/ 8;
    if (!(bitsPerSample == 16 || (bitsPerSample == 32 && isFloat) || bitsPerSample == 32)) {
      throw FormatException('Unsupported WAV: $bitsPerSample-bit');
    }
    final frames = pcm.length ~/ (bytesPer * channels);
    final out = Int16List(frames);
    for (var i = 0; i < frames; i++) {
      var sum = 0.0;
      for (var c = 0; c < channels; c++) {
        final at = (i * channels + c) * bytesPer;
        sum += bitsPerSample == 16
            ? data.getInt16(at, Endian.little).toDouble()
            : isFloat
                ? data.getFloat32(at, Endian.little) * 32767
                : data.getInt32(at, Endian.little) / 65536;
      }
      out[i] = (sum / channels).clamp(-32768, 32767).toInt();
    }
    return Pcm(out, sampleRate);
  }
}

/// Minimal PCM WAV reader/writer.
class Wav {
  Wav._();

  static WavClip parse(Uint8List bytes) {
    String tag(int at) => String.fromCharCodes(bytes.sublist(at, at + 4));
    if (bytes.length < 12 || tag(0) != 'RIFF' || tag(8) != 'WAVE') throw const FormatException('Not a WAV file');
    final bb = ByteData.sublistView(bytes);
    var pos = 12;
    int? rate, channels, bits;
    var format = 1;
    while (pos + 8 <= bytes.length) {
      final id = tag(pos);
      final size = bb.getUint32(pos + 4, Endian.little);
      final body = pos + 8;
      if (id == 'fmt ') {
        format = bb.getUint16(body, Endian.little);
        if (format == 0xFFFE && size >= 26) format = bb.getUint16(body + 24, Endian.little); // extensible: sub-format
        if (format != 1 && format != 3) throw FormatException('Unsupported WAV encoding $format');
        channels = bb.getUint16(body + 2, Endian.little);
        rate = bb.getUint32(body + 4, Endian.little);
        bits = bb.getUint16(body + 14, Endian.little);
      } else if (id == 'data') {
        if (rate == null) throw const FormatException('WAV data before fmt');
        // Streaming writers may leave the size as 0 or 0xFFFFFFFF; take the rest then.
        final end = (size == 0 || body + size > bytes.length) ? bytes.length : body + size;
        return WavClip(rate, channels!, bits!, Uint8List.sublistView(bytes, body, end))..isFloat = format == 3;
      }
      pos = body + size + (size & 1);
    }
    throw const FormatException('WAV without data chunk');
  }

  static Uint8List header(int sampleRate, int dataBytes, {int channels = 1, int bits = 16, bool unknownLength = false}) {
    final h = ByteData(44);
    void tag(int at, String s) {
      for (var i = 0; i < 4; i++) {
        h.setUint8(at + i, s.codeUnitAt(i));
      }
    }

    tag(0, 'RIFF');
    h.setUint32(4, unknownLength ? 0xFFFFFFFF : 36 + dataBytes, Endian.little);
    tag(8, 'WAVE');
    tag(12, 'fmt ');
    h.setUint32(16, 16, Endian.little);
    h.setUint16(20, 1, Endian.little);
    h.setUint16(22, channels, Endian.little);
    h.setUint32(24, sampleRate, Endian.little);
    h.setUint32(28, sampleRate * channels * bits ~/ 8, Endian.little);
    h.setUint16(32, channels * bits ~/ 8, Endian.little);
    h.setUint16(34, bits, Endian.little);
    tag(36, 'data');
    h.setUint32(40, unknownLength ? 0xFFFFFFFF : dataBytes, Endian.little);
    return h.buffer.asUint8List();
  }

  static Uint8List bytesOf(Int16List samples) {
    final out = ByteData(samples.length * 2);
    for (var i = 0; i < samples.length; i++) {
      out.setInt16(i * 2, samples[i], Endian.little);
    }
    return out.buffer.asUint8List();
  }

  /// Joins clips with [pauseMs] between them and [trailingMs] at the end, as a WAV file at the
  /// highest of their sample rates.
  static Uint8List join(List<Pcm> clips, {int pauseMs = 250, int trailingMs = 0}) {
    final rate = clips.map((c) => c.sampleRate).reduce(max);
    final b = BytesBuilder(copy: false);
    final parts = <Uint8List>[];
    for (var i = 0; i < clips.length; i++) {
      if (i > 0) parts.add(Uint8List(rate * pauseMs ~/ 1000 * 2));
      parts.add(bytesOf(clips[i].resampled(rate).samples));
    }
    parts.add(Uint8List(rate * trailingMs ~/ 1000 * 2));
    final dataBytes = parts.fold<int>(0, (n, p) => n + p.length);
    b.add(header(rate, dataBytes));
    parts.forEach(b.add);
    return b.takeBytes();
  }

  static Future<void> write(File file, Uint8List bytes) async {
    await file.parent.create(recursive: true);
    final tmp = File('${file.path}.tmp');
    await tmp.writeAsBytes(bytes, flush: true);
    await tmp.rename(file.path);
  }
}

/// Windowed-sinc resampling for 16-bit mono speech (e.g. 22.05 kHz voice into a 24 kHz file).
class Resampler {
  Resampler._();
  static const _halfTaps = 16;

  static Int16List resample(Int16List input, int fromRate, int toRate) {
    if (fromRate == toRate || input.isEmpty) return Int16List.fromList(input);
    final ratio = toRate / fromRate;
    final outLength = (input.length * ratio).floor();
    final cutoff = min(1.0, ratio) * 0.95; // low-pass at the lower Nyquist frequency
    final halfWidth = _halfTaps / min(1.0, ratio);
    final out = Int16List(outLength);
    for (var n = 0; n < outLength; n++) {
      final center = n / ratio;
      final first = max(0, (center - halfWidth).floor() + 1);
      final last = min(input.length - 1, (center + halfWidth).floor());
      var acc = 0.0, norm = 0.0;
      for (var k = first; k <= last; k++) {
        final x = k - center;
        final w = 0.5 * (1 + cos(pi * x / halfWidth));
        final sinc = x == 0 ? 1.0 : sin(pi * cutoff * x) / (pi * cutoff * x);
        final h = cutoff * sinc * w;
        acc += input[k] * h;
        norm += h;
      }
      out[n] = (norm != 0 ? acc / norm : 0).clamp(-32768, 32767).toInt();
    }
    return out;
  }
}
