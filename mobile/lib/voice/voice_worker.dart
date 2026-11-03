import 'dart:async';
import 'dart:collection';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';

import '../tts/audio.dart';
import 'hebrew_neural_voice.dart';
import 'ort.dart';
import 'voice_catalog.dart';

/// Runs offline voices somewhere they cannot block the UI.
abstract class VoiceRuntime {
  /// [text] spoken by [voiceId] (files in [dir]), one sentence per event. Cancelling the
  /// subscription stops the rendering after the current sentence.
  Stream<Pcm> speak(String dir, String voiceId, String text);

  /// Loads [voiceId], checks it speaks, and measures how fast it renders here: seconds of work per
  /// second of audio. Throws when the voice cannot run on this device.
  Future<double> probe(String dir, String voiceId);

  /// Frees [voiceId] if it is loaded.
  Future<void> unload(String voiceId);
}

/// The voices run on one background isolate, which keeps one voice loaded at a time.
class VoiceWorker implements VoiceRuntime {
  VoiceWorker({this.threads});

  /// ONNX Runtime threads per model; by default up to four.
  final int? threads;

  static const testPhrase = 'שלום.';
  static const speedPhrase = 'ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים, ודן איתו במצב בצפון.';

  Future<SendPort>? _worker;
  var _nextId = 0;
  final _pending = <int, void Function(Object message)>{};

  Future<SendPort> _start() => _worker ??= () async {
        final fromWorker = ReceivePort();
        final exit = ReceivePort();
        final ready = Completer<SendPort>();
        fromWorker.listen((m) {
          if (m is SendPort) {
            ready.complete(m);
          } else if (m is List) {
            _pending[m[1] as int]?.call(m);
          }
        });
        exit.listen((_) {
          // The isolate died: fail everything still waiting and start afresh next time.
          _worker = null;
          for (final f in _pending.values.toList()) {
            f(['error', -1, 'The voice engine stopped']);
          }
          _pending.clear();
          fromWorker.close();
          exit.close();
        });
        await Isolate.spawn(_main, (fromWorker.sendPort, threads ?? min(4, Platform.numberOfProcessors)),
            onExit: exit.sendPort, errorsAreFatal: false, debugName: 'voices');
        return ready.future;
      }();

  @override
  Stream<Pcm> speak(String dir, String voiceId, String text) {
    final id = _nextId++;
    late final StreamController<Pcm> controller;
    controller = StreamController<Pcm>(
      onListen: () async {
        final port = await _start();
        _pending[id] = (m) {
          final list = m as List;
          switch (list[0]) {
            case 'chunk':
              final bytes = (list[2] as TransferableTypedData).materialize();
              controller.add(Pcm(bytes.asInt16List(), list[3] as int));
            case 'done':
              _pending.remove(id);
              controller.close();
            case 'error':
              _pending.remove(id);
              controller.addError(VoiceUnavailableException(list[2] as String));
              controller.close();
          }
        };
        port.send(['speak', id, dir, voiceId, text]);
      },
      onCancel: () async {
        if (_pending.remove(id) != null) (await _start()).send(['cancel', id]);
      },
    );
    return controller.stream;
  }

  @override
  Future<double> probe(String dir, String voiceId) async {
    final id = _nextId++;
    final result = Completer<double>();
    _pending[id] = (m) {
      final list = m as List;
      _pending.remove(id);
      if (list[0] == 'probe') {
        result.complete(list[2] as double);
      } else {
        result.completeError(VoiceUnavailableException(list[2] as String));
      }
    };
    (await _start()).send(['probe', id, dir, voiceId]);
    return result.future;
  }

  @override
  Future<void> unload(String voiceId) async {
    if (_worker == null) return;
    final id = _nextId++;
    final done = Completer<void>();
    _pending[id] = (_) {
      _pending.remove(id);
      done.complete();
    };
    (await _start()).send(['unload', id, voiceId]);
    return done.future;
  }

  // --- The worker isolate ------------------------------------------------------------------

  static void _main((SendPort, int) args) {
    final (toMain, threads) = args;
    OnnxModel.defaultThreads = threads;
    final inbox = ReceivePort();
    toMain.send(inbox.sendPort);
    final queue = Queue<List>();
    final cancelled = <int>{};
    var busy = false;
    HebrewNeuralVoice? voice;
    String? loadedId;

    void close() {
      voice?.close();
      voice = null;
      loadedId = null;
    }

    /// The voice [id] from [dir]; [fresh] reloads it from its files (to test a new download).
    HebrewNeuralVoice load(String dir, String id, {bool fresh = false}) {
      final key = '$dir|$id';
      if (!fresh && loadedId == key && voice != null) return voice!;
      close();
      final pack = VoiceCatalog.byId(id) ?? (throw StateError('Unknown voice $id'));
      voice = VoiceCatalog.open(dir, pack);
      loadedId = key;
      return voice!;
    }

    Future<void> handle(List m) async {
      final id = m[1] as int;
      switch (m[0]) {
        case 'speak':
          try {
            final v = load(m[2] as String, m[3] as String);
            v.lastIpa = '';
            for (final (sentence, paragraphEnd) in HebrewNeuralVoice.plan(m[4] as String)) {
              await Future<void>.delayed(Duration.zero); // let a cancel arrive between sentences
              if (cancelled.contains(id)) break;
              final pcm = v.speakSentence(sentence, paragraphEnd);
              if (pcm.isEmpty) continue;
              toMain.send(['chunk', id, TransferableTypedData.fromList([pcm]), v.sampleRate]);
            }
            toMain.send(['done', id]);
          } catch (e) {
            toMain.send(['error', id, '$e']);
          } finally {
            cancelled.remove(id);
          }
        case 'probe':
          try {
            final v = load(m[2] as String, m[3] as String, fresh: true);
            if (v.synthesizeAll(testPhrase).isEmpty) throw StateError('The voice produced no audio');
            final watch = Stopwatch()..start();
            final pcm = v.synthesizeAll(speedPhrase);
            final seconds = pcm.length / v.sampleRate;
            if (seconds <= 0) throw StateError('The voice produced no audio');
            toMain.send(['probe', id, watch.elapsedMicroseconds / 1e6 / seconds]);
          } catch (e) {
            close();
            toMain.send(['error', id, '$e']);
          }
        case 'unload':
          if (loadedId?.endsWith('|${m[2]}') ?? false) close();
          toMain.send(['unloaded', id]);
      }
    }

    Future<void> pump() async {
      if (busy) return;
      busy = true;
      while (queue.isNotEmpty) {
        final m = queue.removeFirst();
        if (m[0] == 'speak' && cancelled.remove(m[1])) {
          toMain.send(['done', m[1]]);
          continue;
        }
        await handle(m);
      }
      busy = false;
    }

    inbox.listen((m) {
      final list = m as List;
      if (list[0] == 'cancel') {
        cancelled.add(list[1] as int);
      } else {
        queue.add(list);
        unawaited(pump());
      }
    });
  }
}

/// An offline voice could not be loaded or failed while speaking.
class VoiceUnavailableException implements Exception {
  VoiceUnavailableException(this.message);
  final String message;
  @override
  String toString() => 'VoiceUnavailableException: $message';
}
