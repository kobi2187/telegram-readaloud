import 'dart:io';
import 'dart:typed_data';

import 'package:onnxruntime_v2/onnxruntime_v2.dart';

/// An input or output tensor: flat data (Float32List or Int64List) and its shape.
class Tensor {
  Tensor.f32(Float32List this.floats, this.shape) : ints = null;
  Tensor.i64(Int64List this.ints, this.shape) : floats = null;

  final Float32List? floats;
  final Int64List? ints;
  final List<int> shape;

  OrtValueTensor _toOrt() => ints != null
      ? OrtValueTensor.createTensorWithDataList([ints!], shape)
      : OrtValueTensor.createTensorWithDataList([floats!], shape);
}

/// A loaded ONNX model. Synchronous: create and run it on a background isolate.
class OnnxModel {
  OnnxModel._(this._session);

  /// Threads per model when [OnnxModel.open] is not told otherwise (0: the runtime's default).
  static int defaultThreads = 0;

  /// Opens [file]; [threads] limits the intra-op thread pool.
  factory OnnxModel.open(String file, {int? threads}) {
    OrtEnv.instance.init();
    if (!File(file).existsSync()) throw FileSystemException('Model missing', file);
    final options = OrtSessionOptions();
    final n = threads ?? defaultThreads;
    if (n > 0) options.setIntraOpNumThreads(n);
    try {
      return OnnxModel._(OrtSession.fromFile(File(file), options));
    } finally {
      options.release();
    }
  }

  final OrtSession _session;

  List<String> get inputNames => _session.inputNames;
  List<String> get outputNames => _session.outputNames;

  /// A value from the model's custom metadata, or null.
  String? metadata(String key) {
    try {
      final v = _session.getMetadatas(key);
      return v.isEmpty ? null : v;
    } catch (_) {
      return null;
    }
  }

  /// Runs the model; returns every output, flattened to float32 with its shape.
  List<({Float32List data, List<int> shape})> run(Map<String, Tensor> inputs) {
    final values = {for (final e in inputs.entries) e.key: e.value._toOrt()};
    final runOptions = OrtRunOptions();
    List<OrtValue?> outputs = const [];
    try {
      outputs = _session.run(runOptions, values);
      return [
        for (final o in outputs) _flatten(o?.value),
      ];
    } finally {
      for (final v in values.values) {
        v.release();
      }
      for (final o in outputs) {
        o?.release();
      }
      runOptions.release();
    }
  }

  void close() => _session.release();

  static ({Float32List data, List<int> shape}) _flatten(Object? value) {
    final shape = <int>[];
    Object? level = value;
    while (level is List && level.isNotEmpty && level.first is List) {
      shape.add(level.length);
      level = level.first;
    }
    if (level is List) shape.add(level.length);
    final count = shape.isEmpty ? 1 : shape.fold<int>(1, (a, b) => a * b);
    final out = Float32List(count);
    var pos = 0;
    void walk(Object? v) {
      if (v is List) {
        for (final x in v) {
          walk(x);
        }
      } else if (v is num) {
        out[pos++] = v.toDouble();
      }
    }

    walk(value);
    return (data: out, shape: shape);
  }
}
