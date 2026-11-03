import 'dart:async';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';

import '../data/database.dart';
import '../data/models.dart';
import '../data/settings.dart';
import '../tts/audio_renderer.dart';
import 'android_volume.dart';
import 'volume_gestures.dart';

/// One entry of the reading queue: a spoken channel name, a post, or a notice.
@immutable
class QueueEntry {
  const QueueEntry.announce(this.channel, this.title, this.art)
      : kind = 'announce',
        id = 0,
        text = '',
        time = 0,
        key = '';
  const QueueEntry.message(this.channel, this.id, this.title, this.text, this.time, this.art)
      : kind = 'msg',
        key = '';
  const QueueEntry.notice(this.key, this.title)
      : kind = 'notice',
        channel = '',
        id = 0,
        text = '',
        time = 0,
        art = null;

  final String kind;
  final String channel;
  final int id;
  final String key;
  final String title;
  final String text;
  final int time;
  final String? art;

  String get mediaId => switch (kind) { 'msg' => 'msg:$channel:$id', 'announce' => 'announce:$channel', _ => 'notice:$key' };
}

/// Plays channels like a radio and keeps cursors and heard marks in step: one channel at a time
/// (optional spoken name, then its posts from the cursor onwards); at the end it continues with the
/// next channel that has unheard posts. Posts are voiced on demand, and the next one is prepared
/// while the current one plays. Lock-screen, notification and headset controls come through
/// audio_service.
class ReaderHandler extends BaseAudioHandler with SeekHandler {
  ReaderHandler({required this.db, required this.settings, required this.renderer, required this.noticeText, required this.appName, this.volume}) {
    if (volume != null) _setUpVolumeKeys(volume!);
    _player.playerStateStream.listen(_onPlayerState);
    _player.playbackEventStream.listen((_) => _broadcast());
    _player.positionStream.listen((_) {});
    settings.addListener(() => _player.setSpeed(settings.value.speed));
    db.changes.listen((_) => _onDataChanged());
    _saver = Timer.periodic(const Duration(seconds: 10), (_) {
      if (_player.playing) savePosition();
    });
  }

  final AppDatabase db;
  final SettingsStore settings;
  final AudioRenderer renderer;
  final String Function(String key) noticeText;
  final String appName;

  /// The device volume (Android), for switching channels with the volume buttons.
  final AndroidVolume? volume;
  final AudioPlayer _player = AudioPlayer();
  late final Timer _saver;

  static const noticeCaughtUp = 'caught_up';
  static const noticeNoOther = 'no_other';
  static const _queueBatch = 200;
  static const _topUpThreshold = 10;

  final List<QueueEntry> _entries = [];
  int _index = -1;
  String? currentChannel;
  int _lastQueuedId = 0;
  bool _ended = false;
  bool _wantPlaying = false;
  int _generation = 0;

  QueueEntry? get current => _index >= 0 && _index < _entries.length ? _entries[_index] : null;
  Stream<Duration> get positionStream => _player.positionStream;
  Duration get position => _player.position;
  Duration? get duration => _player.duration;
  bool get isCaughtUp => _ended && current?.kind == 'notice' && current?.key == noticeCaughtUp;

  // --- Queue building ----------------------------------------------------------------

  Future<List<QueueEntry>?> _prepareChannel(String username, int? fromId, bool announce) async {
    final ch = await db.channel(username);
    if (ch == null) return null;
    final start = fromId ?? ch.cursorId;
    final posts = await db.queue(username, start, ch.startDate, _queueBatch);
    if (posts.isEmpty) return null;
    final art = ch.avatarPath;
    final items = <QueueEntry>[
      if (announce) QueueEntry.announce(ch.username, ch.title, art),
      for (final m in posts) _messageEntry(ch, m),
    ];
    currentChannel = username;
    _lastQueuedId = posts.last.id;
    return items;
  }

  QueueEntry _messageEntry(Channel ch, Message m) {
    final firstLine = m.text.split('\n').map((l) => l.trim()).firstWhere((l) => l.isNotEmpty, orElse: () => ch.title);
    return QueueEntry.message(ch.username, m.id, firstLine.length > 120 ? firstLine.substring(0, 120) : firstLine, m.text, m.date, ch.avatarPath);
  }

  /// Loads and plays a channel. False when it has nothing left to read.
  Future<bool> playChannel(String username, {int? fromId, bool? announce, bool play = true}) async {
    await savePosition();
    final ch = await db.channel(username);
    if (ch == null) return false;
    final wantAnnounce = announce ?? settings.value.announceChannel;
    final items = await _prepareChannel(username, fromId, wantAnnounce);
    if (items == null) return false;
    final firstPost = items.firstWhere((e) => e.kind == 'msg');
    final resumeMs = (!wantAnnounce && fromId == null && firstPost.id == ch.cursorId) ? ch.cursorPositionMs : 0;
    await db.setCursor(username, firstPost.id, resumeMs);
    await settings.update((s) => s.copyWith(currentChannel: username));
    _setQueue(items, 0);
    _wantPlaying = play;
    await _playCurrent(Duration(milliseconds: resumeMs));
    return true;
  }

  void _setQueue(List<QueueEntry> items, int index) {
    _entries
      ..clear()
      ..addAll(items);
    _index = index;
    _ended = false;
    queue.add(_entries.map(_mediaItem).toList());
  }

  MediaItem _mediaItem(QueueEntry e) => MediaItem(
        id: e.mediaId,
        title: e.title,
        artist: e.kind == 'notice' ? appName : (e.kind == 'announce' ? e.title : null),
        album: e.time == 0 ? null : DateTime.fromMillisecondsSinceEpoch(e.time).toString().substring(0, 16),
        artUri: e.art != null && File(e.art!).existsSync() ? Uri.file(e.art!) : null,
        displayDescription: e.text,
        extras: {'channel': e.channel, 'id': e.id},
      );

  /// Moves to the next (+1) or previous (-1) channel that still has unheard posts.
  Future<void> switchChannel(int direction) async {
    final list = await db.enabledChannels();
    if (list.isEmpty) return;
    final idx = list.indexWhere((c) => c.username == currentChannel);
    for (var step = 1; step <= list.length; step++) {
      final candidate = list[(idx + direction * step) % list.length];
      if (candidate.username == currentChannel) break;
      if (await db.countFrom(candidate.username, candidate.cursorId, candidate.startDate) > 0) {
        await playChannel(candidate.username);
        return;
      }
    }
    await _playNotice(noticeNoOther, resumeAfter: true);
  }

  // --- Playing -------------------------------------------------------------------------

  Future<File> _resolve(QueueEntry e) => switch (e.kind) {
        'msg' => renderer.ensureMessageAudio(e.channel, e.id),
        'announce' => renderer.ensureClip('ch_${e.channel}', e.title),
        _ => renderer.ensureClip('notice_${e.key}', noticeText(e.key)),
      };

  Future<void> _playCurrent(Duration start) async {
    final e = current;
    if (e == null) return;
    final generation = ++_generation;
    _stopLive();
    mediaItem.add(_mediaItem(e));
    _broadcast(loading: true);
    PostAudio audio;
    try {
      audio = e.kind == 'msg' ? await renderer.openMessageAudio(e.channel, e.id) : ReadyAudio(await _resolve(e));
    } catch (err) {
      debugPrint('Could not voice ${e.mediaId}: $err');
      if (generation == _generation) await _advance(auto: true);
      return;
    }
    if (generation != _generation) {
      if (audio is LiveAudio) audio.cancel(); // the listener moved on meanwhile
      return;
    }
    try {
      switch (audio) {
        case ReadyAudio(:final file):
          await _player.setFilePath(file.path, initialPosition: start);
        case LiveAudio live:
          // Voiced on the phone: play each sentence as soon as it is ready.
          if (!await _startLive(live, generation)) return;
          start = Duration.zero;
      }
      await _player.setSpeed(settings.value.speed);
      if (_wantPlaying) unawaited(_player.play());
    } catch (err) {
      debugPrint('Could not play ${e.mediaId}: $err');
      if (generation == _generation) await _advance(auto: true);
      return;
    }
    if (e.kind == 'msg') await db.setCursor(e.channel, e.id, start.inMilliseconds);
    _prepareNext();
    if (_entries.length - _index < _topUpThreshold) await _topUp();
  }

  // --- Volume buttons -----------------------------------------------------------------

  late final VolumePressDetector _presses = VolumePressDetector(onVolumeStep: _stepVolume, onDoublePress: _onVolumeGesture);
  late final VolumeChangeDetector _volumeChanges = VolumeChangeDetector(onDoubleChange: (direction, restoreTo) {
    _ignoreVolumeChangesUntil = monotonicMs() + 300;
    unawaited(volume?.set(restoreTo));
    _onVolumeGesture(direction);
  });
  int _ignoreVolumeChangesUntil = 0;
  bool _hijack = false;

  /// While the volume buttons have a job, the session reports remote playback: Android then hands
  /// volume presses to [androidAdjustRemoteVolume] (also with the screen off) instead of changing
  /// the volume itself. Single presses are applied to the music stream here, so they still work.
  void _setUpVolumeKeys(AndroidVolume volume) {
    void apply() {
      final on = settings.value.volumeKeys != VolumeKeyMode.off;
      if (on == _hijack) return;
      _hijack = on;
      _volumeChanges.reset();
      unawaited(_publishVolume());
    }

    settings.addListener(apply);
    apply();
    volume.changes.listen((change) {
      unawaited(_publishVolume());
      final (previous, current) = change;
      // Some Android builds keep volume keys away from remote sessions that are not casting;
      // there the volume changes directly, and a quick double step is the gesture.
      if (!_hijack || !_player.playing || previous < 0 || current < 0) return;
      if (monotonicMs() < _ignoreVolumeChangesUntil) return;
      _volumeChanges.onChange(previous, current);
    }, onError: (Object e) => debugPrint('Volume changes unavailable: $e'));
  }

  Future<void> _publishVolume() async {
    final v = volume;
    if (v == null) return;
    if (!_hijack) {
      androidPlaybackInfo.add(LocalAndroidPlaybackInfo());
      return;
    }
    try {
      final s = await v.state();
      androidPlaybackInfo.add(RemoteAndroidPlaybackInfo(volumeControlType: AndroidVolumeControlType.absolute, maxVolume: s.max, volume: s.current));
    } on Exception catch (e) {
      debugPrint('Volume state unavailable: $e');
    }
  }

  void _stepVolume(int direction) {
    _ignoreVolumeChangesUntil = monotonicMs() + 300;
    unawaited(volume?.adjust(direction).then((_) => _publishVolume()));
  }

  void _onVolumeGesture(int direction) {
    switch (settings.value.volumeKeys) {
      case VolumeKeyMode.channels:
        unawaited(switchChannel(direction));
      case VolumeKeyMode.messages:
        unawaited(direction > 0 ? skipToNext() : skipToPrevious());
      case VolumeKeyMode.off:
        break;
    }
  }

  @override
  Future<void> androidAdjustRemoteVolume(AndroidVolumeDirection direction) async {
    if (!_hijack || direction.index == 0) return;
    _presses.onPress(direction.index > 0 ? 1 : -1);
  }

  @override
  Future<void> androidSetRemoteVolume(int volumeIndex) async {
    // The system volume slider.
    _ignoreVolumeChangesUntil = monotonicMs() + 300;
    await volume?.set(volumeIndex);
    await _publishVolume();
  }

  // --- Posts voiced while they play ----------------------------------------------------

  LiveAudio? _live;
  StreamSubscription<File>? _liveParts;
  bool _liveDone = false;
  bool _waitingForPart = false;

  /// Plays the first part of [live] and appends the rest as they come. False if there was nothing
  /// to play or the listener moved on.
  Future<bool> _startLive(LiveAudio live, int generation) async {
    _live = live;
    _liveDone = false;
    _waitingForPart = false;
    final first = Completer<File?>();
    _liveParts = live.parts.listen(
      (f) async {
        if (!first.isCompleted) {
          first.complete(f);
          return;
        }
        await _player.addAudioSource(AudioSource.file(f.path));
        if (_waitingForPart && generation == _generation) {
          // Playback caught up with the voice: go on with the new part.
          _waitingForPart = false;
          await _player.seek(Duration.zero, index: _player.audioSources.length - 1);
          if (_wantPlaying) unawaited(_player.play());
        }
      },
      onError: (Object err) => debugPrint('Voicing failed mid-post: $err'),
      onDone: () {
        _liveDone = true;
        if (!first.isCompleted) first.complete(null);
        if (_waitingForPart && generation == _generation) {
          _waitingForPart = false;
          unawaited(_advance(auto: true));
        }
      },
    );
    final head = await first.future;
    if (generation != _generation) return false;
    if (head == null) {
      await _advance(auto: true);
      return false;
    }
    await _player.setAudioSources([AudioSource.file(head.path)]);
    return true;
  }

  /// Stops voicing a post nobody listens to any more, so the voice is free for the next one.
  void _stopLive() {
    final live = _live;
    _live = null;
    unawaited(_liveParts?.cancel());
    _liveParts = null;
    _waitingForPart = false;
    if (live != null && !_liveDone) live.cancel();
  }

  /// Voices the next post while this one plays.
  void _prepareNext() {
    final next = _index + 1 < _entries.length ? _entries[_index + 1] : null;
    if (next != null) unawaited(_resolve(next).then((_) {}, onError: (_) {}));
  }

  void _onPlayerState(PlayerState s) {
    if (s.processingState == ProcessingState.completed && !_ended) {
      if (_live != null && !_liveDone) {
        _waitingForPart = true; // the next sentence is still being voiced
      } else {
        unawaited(_advance(auto: true));
      }
    }
    _broadcast();
  }

  /// After an item: mark heard, go on, or wrap up the channel.
  Future<void> _advance({required bool auto}) async {
    final finished = current;
    if (finished != null && finished.kind == 'msg' && finished.channel == currentChannel) {
      await db.markPlayed(finished.channel, finished.id);
    }
    if (_index + 1 < _entries.length) {
      _index++;
      await _playCurrent(Duration.zero);
      return;
    }
    await _onEnded(finished);
  }

  Future<void> _onEnded(QueueEntry? finished) async {
    if (finished?.kind == 'notice') {
      _ended = true;
      _broadcast();
      return;
    }
    final channel = currentChannel;
    if (finished != null && finished.kind == 'msg' && channel != null) {
      await db.setCursor(channel, finished.id + 1, 0);
    }
    _ended = true;
    if (settings.value.autoAdvance && await _advanceToNextChannel()) return;
    await _playNotice(noticeCaughtUp, resumeAfter: false);
  }

  Future<bool> _advanceToNextChannel() async {
    final list = await db.enabledChannels();
    if (list.isEmpty) return false;
    final idx = list.indexWhere((c) => c.username == currentChannel);
    for (var step = 1; step <= list.length; step++) {
      final candidate = list[(idx + step) % list.length];
      if (await db.countFrom(candidate.username, candidate.cursorId, candidate.startDate) > 0) {
        return playChannel(candidate.username);
      }
    }
    return false;
  }

  Future<void> _playNotice(String key, {required bool resumeAfter}) async {
    final notice = QueueEntry.notice(key, noticeText(key));
    if (resumeAfter && current != null) {
      // Say it, then carry on where we were.
      _entries.insert(_index, notice);
      queue.add(_entries.map(_mediaItem).toList());
    } else {
      _setQueue([notice], 0);
    }
    _ended = false;
    _wantPlaying = true;
    await _playCurrent(Duration.zero);
  }

  /// Appends posts that arrived (sync) or were beyond the first batch.
  Future<void> _topUp() async {
    final username = currentChannel;
    if (username == null) return;
    final ch = await db.channel(username);
    if (ch == null) return;
    final more = await db.queue(username, _lastQueuedId + 1, ch.startDate, _queueBatch);
    if (more.isEmpty || currentChannel != username) return;
    _lastQueuedId = more.last.id;
    final wasEnded = _ended && current?.kind == 'msg';
    _entries.addAll(more.map((m) => _messageEntry(ch, m)));
    queue.add(_entries.map(_mediaItem).toList());
    if (wasEnded) {
      _ended = false;
      _index++;
      await _playCurrent(Duration.zero);
    }
  }

  bool _checking = false;

  /// New posts: top up the current channel, or once everything was heard, carry on like a radio.
  Future<void> _onDataChanged() async {
    if (_checking) return;
    _checking = true;
    try {
      final username = currentChannel;
      if (username != null) {
        final ch = await db.channel(username);
        if (ch != null && await db.countFrom(username, _lastQueuedId + 1, ch.startDate) > 0) {
          await _topUp();
          return;
        }
      }
      if (isCaughtUp && _wantPlaying) await _advanceToNextChannel();
    } finally {
      _checking = false;
    }
  }

  Future<void> savePosition() async {
    // After the queue ended the cursor is already past the last post; saving would pull it back.
    if (_ended) return;
    final e = current;
    if (e != null && e.kind == 'msg' && e.channel == currentChannel) {
      // Inside a post still being voiced, the position is within one sentence: start it over.
      await db.setCursor(e.channel, e.id, _live != null ? 0 : _player.position.inMilliseconds);
    }
  }

  // --- audio_service controls ------------------------------------------------------

  @override
  Future<void> play() async {
    _wantPlaying = true;
    if (current == null || isCaughtUp) {
      await resumeOrStart();
      return;
    }
    if (_player.processingState == ProcessingState.idle) {
      await _playCurrent(_player.position);
      return;
    }
    await _player.play();
  }

  /// Play pressed with nothing loaded: the last channel, else any channel with news.
  Future<void> resumeOrStart() async {
    final preferred = settings.value.currentChannel;
    if (preferred != null && await playChannel(preferred, announce: false)) return;
    for (final ch in await db.enabledChannels()) {
      if (await playChannel(ch.username)) return;
    }
  }

  @override
  Future<void> pause() async {
    _wantPlaying = false;
    await _player.pause();
    await savePosition();
  }

  @override
  Future<void> stop() async {
    await savePosition();
    _wantPlaying = false;
    await _player.stop();
    await super.stop();
  }

  @override
  Future<void> seek(Duration position) => _player.seek(position);

  @override
  Future<void> skipToNext() => _advance(auto: false);

  @override
  Future<void> skipToPrevious() async {
    if (_player.position > const Duration(seconds: 3) || _index <= 0) {
      await _player.seek(Duration.zero);
      return;
    }
    _index--;
    await _playCurrent(Duration.zero);
  }

  @override
  Future<void> setSpeed(double speed) => settings.update((s) => s.copyWith(speed: speed));

  @override
  Future<dynamic> customAction(String name, [Map<String, dynamic>? extras]) async {
    switch (name) {
      case 'nextChannel':
        await switchChannel(1);
      case 'previousChannel':
        await switchChannel(-1);
    }
  }

  void _broadcast({bool loading = false}) {
    final playing = _player.playing;
    final state = _player.processingState;
    playbackState.add(PlaybackState(
      controls: [MediaControl.skipToPrevious, if (playing) MediaControl.pause else MediaControl.play, MediaControl.skipToNext],
      systemActions: const {MediaAction.seek, MediaAction.skipToNext, MediaAction.skipToPrevious, MediaAction.playPause},
      androidCompactActionIndices: const [0, 1, 2],
      processingState: loading
          ? AudioProcessingState.loading
          : _waitingForPart
              ? AudioProcessingState.buffering
              : switch (state) {
              ProcessingState.idle => current == null ? AudioProcessingState.idle : AudioProcessingState.ready,
              ProcessingState.loading => AudioProcessingState.loading,
              ProcessingState.buffering => AudioProcessingState.buffering,
              ProcessingState.ready => AudioProcessingState.ready,
              ProcessingState.completed => AudioProcessingState.completed,
            },
      playing: playing,
      updatePosition: _player.position,
      bufferedPosition: _player.bufferedPosition,
      speed: _player.speed,
      queueIndex: _index < 0 ? null : _index,
    ));
  }

  Future<void> dispose() async {
    _saver.cancel();
    await _player.dispose();
  }
}
