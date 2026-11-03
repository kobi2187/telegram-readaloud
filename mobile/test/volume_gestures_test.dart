import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/playback/volume_gestures.dart';

/// Deterministic clock and scheduler.
class FakeTime {
  int now = 1000;
  final _tasks = <(int, void Function())>[];

  void Function() schedule(int delayMs, void Function() block) {
    final task = (now + delayMs, block);
    _tasks.add(task);
    return () => _tasks.remove(task);
  }

  void advance(int ms) {
    final target = now + ms;
    while (true) {
      final due = _tasks.where((t) => t.$1 <= target).toList()..sort((a, b) => a.$1.compareTo(b.$1));
      if (due.isEmpty) break;
      _tasks.remove(due.first);
      now = due.first.$1;
      due.first.$2();
    }
    now = target;
  }
}

/// The same cases as the Android app's VolumeGesturesTest.
void main() {
  late FakeTime time;
  late List<int> steps;
  late List<int> gestures;
  late VolumePressDetector presses;
  late List<(int, int)> restored;
  late VolumeChangeDetector changes;

  setUp(() {
    time = FakeTime();
    steps = [];
    gestures = [];
    presses = VolumePressDetector(onVolumeStep: steps.add, onDoublePress: gestures.add, scheduler: time.schedule, now: () => time.now);
    restored = [];
    changes = VolumeChangeDetector(onDoubleChange: (d, to) => restored.add((d, to)), scheduler: time.schedule, now: () => time.now);
  });

  test('a single press is a volume step after the window', () {
    presses.onPress(1);
    expect(steps, isEmpty);
    time.advance(450);
    expect(steps, [1]);
    expect(gestures, isEmpty);
  });

  test('a double press is a gesture without a volume change', () {
    presses.onPress(1);
    time.advance(220);
    presses.onPress(1);
    time.advance(1000);
    expect(gestures, [1]);
    expect(steps, isEmpty);
  });

  test('double press down', () {
    presses.onPress(-1);
    time.advance(150);
    presses.onPress(-1);
    expect(gestures, [-1]);
  });

  test('up then down are two steps', () {
    presses.onPress(1);
    time.advance(200);
    presses.onPress(-1);
    time.advance(1000);
    expect(steps, [1, -1]);
    expect(gestures, isEmpty);
  });

  test('slow presses are steps', () {
    presses.onPress(1);
    time.advance(600);
    presses.onPress(1);
    time.advance(600);
    expect(steps, [1, 1]);
    expect(gestures, isEmpty);
  });

  test('holding a key is just volume', () {
    // Auto-repeat: first event, then a 500 ms delay, then every 50 ms.
    presses.onPress(1);
    time.advance(500);
    for (var i = 0; i < 6; i++) {
      presses.onPress(1);
      time.advance(50);
    }
    time.advance(1000);
    expect(gestures, isEmpty);
    expect(steps, hasLength(7));
  });

  test('a triple tap is one gesture then a step', () {
    presses.onPress(1);
    time.advance(200);
    presses.onPress(1);
    time.advance(200);
    presses.onPress(1);
    time.advance(1000);
    expect(gestures, [1]);
    expect(steps, [1]);
  });

  // --- fallback: watching volume changes ---------------------------------------------------

  test('two quick steps are a gesture and restore the volume', () {
    changes.onChange(5, 6);
    time.advance(200);
    changes.onChange(6, 7);
    time.advance(400);
    expect(restored, [(1, 5)]);
  });

  test('a slider drag is ignored', () {
    var v = 3;
    for (var i = 0; i < 6; i++) {
      changes.onChange(v, v + 1);
      v++;
      time.advance(60);
    }
    time.advance(1000);
    expect(restored, isEmpty);
  });

  test('a drag that ends on an even step is still ignored', () {
    var v = 3;
    for (var i = 0; i < 4; i++) {
      changes.onChange(v, v + 1);
      v++;
      time.advance(120);
    }
    time.advance(1000);
    expect(restored, isEmpty);
  });

  test('jumps are not gestures', () {
    changes.onChange(2, 6);
    time.advance(100);
    changes.onChange(6, 7);
    time.advance(1000);
    expect(restored, isEmpty);
  });

  test('separate steps are not gestures', () {
    changes.onChange(5, 4);
    time.advance(800);
    changes.onChange(4, 3);
    time.advance(1000);
    expect(restored, isEmpty);
  });
}
