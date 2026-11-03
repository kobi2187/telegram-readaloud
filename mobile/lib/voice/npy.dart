import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';

/// A float32 array read from a NumPy file.
class NpyArray {
  NpyArray(this.shape, this.data);
  final List<int> shape;
  final Float32List data;
}

/// Minimal reader for little-endian float32 `.npy` arrays and `.npz` archives of them.
abstract final class Npy {
  static NpyArray read(Uint8List bytes) {
    if (bytes.length < 10 || bytes[0] != 0x93 || latin1.decode(bytes.sublist(1, 6)) != 'NUMPY') {
      throw const FormatException('Not a .npy file');
    }
    final view = ByteData.sublistView(bytes);
    final major = bytes[6];
    final headerLen = major == 1 ? view.getUint16(8, Endian.little) : view.getUint32(8, Endian.little);
    final start = major == 1 ? 10 : 12;
    final header = latin1.decode(bytes.sublist(start, start + headerLen));
    final descr = RegExp(r"'descr':\s*'([^']+)'").firstMatch(header)?.group(1);
    if (descr != '<f4') throw FormatException('Unsupported dtype $descr');
    if (RegExp(r"'fortran_order':\s*True").hasMatch(header)) throw const FormatException('Fortran order not supported');
    final shapeText = RegExp(r"'shape':\s*\(([^)]*)\)").firstMatch(header)?.group(1) ?? '';
    final shape = shapeText.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).map(int.parse).toList();
    final count = shape.fold<int>(1, (a, b) => a * b);
    final data = Float32List(count);
    final offset = start + headerLen;
    for (var i = 0; i < count; i++) {
      data[i] = view.getFloat32(offset + i * 4, Endian.little);
    }
    return NpyArray(shape, data);
  }

  /// All arrays in an `.npz` archive, by name without the `.npy` suffix.
  static Map<String, NpyArray> readArchive(File file) {
    final archive = ZipDecoder().decodeBytes(file.readAsBytesSync());
    return {
      for (final entry in archive.files)
        if (entry.isFile && entry.name.endsWith('.npy'))
          entry.name.substring(0, entry.name.length - 4): read(entry.content),
    };
  }
}
