import 'dart:async';

/// Schedules [block] after [delayMs]; returns a function that cancels it.
typedef Scheduler = void Function() Function(int delayMs, void Function() block);

/// Real time: [Timer]s and a monotonic clock.
final Stopwatch _clock = Stopwatch()..start();
int monotonicMs() => _clock.elapsedMilliseconds;
void Function() timerScheduler(int delayMs, void Function() block) => Timer(Duration(milliseconds: delayMs), block).cancel;

/// Turns volume key presses into gestures, as the Android app does. A single press is still a
/// volume step (applied after a short wait to rule out a double press); two quick presses in the
/// same direction are a gesture. Auto-repeat from holding a key (about 50 ms apart) is treated as
/// plain volume steps. Direction: +1 = volume up, -1 = volume down.
class VolumePressDetector {
  VolumePressDetector({
    required this.onVolumeStep,
    required this.onDoublePress,
    Scheduler? scheduler,
    int Function()? now,
    this.windowMs = doublePressWindowMs,
  })  : _schedule = scheduler ?? timerScheduler,
        _now = now ?? monotonicMs;

  static const doublePressWindowMs = 400;
  static const repeatMaxIntervalMs = 90;

  final void Function(int direction) onVolumeStep;
  final void Function(int direction) onDoublePress;
  final Scheduler _schedule;
  final int Function() _now;
  final int windowMs;

  int _pendingDirection = 0;
  int _pendingAt = 0;
  void Function()? _cancelPending;
  int _lastPressAt = -1 << 40;
  int _lastDirection = 0;
  bool _holding = false;

  void onPress(int direction) {
    final t = _now();
    final sinceLast = t - _lastPressAt;
    if (direction == _lastDirection && sinceLast < repeatMaxIntervalMs) {
      // Key auto-repeat: the user is holding the key to change volume.
      _flushPending();
      _holding = true;
      onVolumeStep(direction);
    } else if (_pendingDirection == direction && t - _pendingAt <= windowMs && !_holding) {
      _cancelPending?.call();
      _clearPending();
      onDoublePress(direction);
    } else {
      _flushPending();
      _holding = false;
      _pendingDirection = direction;
      _pendingAt = t;
      _cancelPending = _schedule(windowMs, () {
        final d = _pendingDirection;
        _clearPending();
        if (d != 0) onVolumeStep(d);
      });
    }
    _lastPressAt = t;
    _lastDirection = direction;
  }

  void _flushPending() {
    final d = _pendingDirection;
    if (d != 0) {
      _cancelPending?.call();
      _clearPending();
      onVolumeStep(d);
    }
  }

  void _clearPending() {
    _pendingDirection = 0;
    _cancelPending = null;
  }
}

/// Fallback for devices that keep volume keys away from media sessions: watches actual volume
/// changes. Exactly two one-step changes in the same direction within the window, followed by a
/// quiet moment (so slider drags and held keys don't count), is a gesture; the caller then
/// restores the volume that was in effect before the first change.
class VolumeChangeDetector {
  VolumeChangeDetector({
    required this.onDoubleChange,
    Scheduler? scheduler,
    int Function()? now,
    this.windowMs = VolumePressDetector.doublePressWindowMs,
    this.quietMs = 300,
  })  : _schedule = scheduler ?? timerScheduler,
        _now = now ?? monotonicMs;

  final void Function(int direction, int restoreTo) onDoubleChange;
  final Scheduler _schedule;
  final int Function() _now;
  final int windowMs;
  final int quietMs;

  int _firstAt = 0;
  int _firstDirection = 0;
  int _volumeBefore = 0;
  int _count = 0;
  void Function()? _cancelConfirm;
  int _suppressUntil = -1 << 40;

  void onChange(int previous, int current) {
    final delta = current - previous;
    final t = _now();
    if (delta == 0) return;
    final direction = delta > 0 ? 1 : -1;
    _cancelConfirm?.call();
    _cancelConfirm = null;
    if (t < _suppressUntil) {
      _suppressUntil = t + windowMs; // still dragging or holding
      return;
    }
    if (delta.abs() != 1) {
      reset();
      _suppressUntil = t + windowMs;
      return;
    }
    if (_count == 0 || direction != _firstDirection || t - _firstAt > windowMs) {
      _count = 1;
      _firstAt = t;
      _firstDirection = direction;
      _volumeBefore = previous;
      return;
    }
    _count++;
    if (_count == 2) {
      final restore = _volumeBefore;
      _cancelConfirm = _schedule(quietMs, () {
        _cancelConfirm = null;
        reset();
        onDoubleChange(direction, restore);
      });
    } else {
      reset(); // three or more steps: a slider drag or a held key
      _suppressUntil = t + windowMs;
    }
  }

  void reset() {
    _count = 0;
    _firstDirection = 0;
  }
}
