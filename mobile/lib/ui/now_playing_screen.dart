import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../l10n/app_localizations.dart';
import 'app_state.dart';
import 'widgets.dart';

class NowPlayingScreen extends StatelessWidget {
  const NowPlayingScreen({super.key});

  static const _speeds = [0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0];

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final graph = context.read<AppState>().graph;
    final player = graph.player;
    return Scaffold(
      appBar: AppBar(),
      body: StreamBuilder<MediaItem?>(
        stream: player.mediaItem,
        builder: (context, snapshot) {
          final item = snapshot.data;
          final text = item?.displayDescription?.trim().isNotEmpty == true ? item!.displayDescription! : (item?.title ?? l.playerIdle);
          return SafeArea(
            child: Column(children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  if (item?.artist != null) Text(item!.artist!, style: Theme.of(context).textTheme.titleMedium),
                  if (item?.album != null) Text(item!.album!, style: Theme.of(context).textTheme.bodySmall),
                ]),
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: SizedBox(
                    width: double.infinity,
                    child: Text(text, textDirection: directionOf(text), style: Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.5)),
                  ),
                ),
              ),
              const _Progress(),
              StreamBuilder<PlaybackState>(
                stream: player.playbackState,
                builder: (context, state) {
                  final playing = state.data?.playing ?? false;
                  return Column(children: [
                    Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
                      IconButton(icon: const Icon(Icons.replay_10), tooltip: l.actionBack10, onPressed: () => player.seek(player.position - const Duration(seconds: 10))),
                      IconButton(icon: const Icon(Icons.skip_previous), tooltip: l.actionPrevMessage, onPressed: player.skipToPrevious),
                      IconButton.filled(
                        iconSize: 40,
                        icon: Icon(playing ? Icons.pause : Icons.play_arrow),
                        tooltip: playing ? l.actionPause : l.actionPlay,
                        onPressed: () => playing ? player.pause() : player.play(),
                      ),
                      IconButton(icon: const Icon(Icons.skip_next), tooltip: l.actionNextMessage, onPressed: player.skipToNext),
                      IconButton(icon: const Icon(Icons.forward_10), tooltip: l.actionForward10, onPressed: () => player.seek(player.position + const Duration(seconds: 10))),
                    ]),
                    Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
                      TextButton.icon(onPressed: () => player.switchChannel(-1), icon: const Icon(Icons.keyboard_double_arrow_left), label: Text(l.actionPrevChannelShort)),
                      ValueListenableBuilder(
                        valueListenable: graph.settings,
                        builder: (context, s, _) => PopupMenuButton<double>(
                          initialValue: s.speed,
                          onSelected: (v) => graph.settings.update((x) => x.copyWith(speed: v)),
                          itemBuilder: (_) => [for (final v in _speeds) PopupMenuItem(value: v, child: Text('${v}x'))],
                          child: Padding(padding: const EdgeInsets.all(12), child: Text(l.speedLabel('${s.speed}x'))),
                        ),
                      ),
                      TextButton.icon(onPressed: () => player.switchChannel(1), icon: const Icon(Icons.keyboard_double_arrow_right), label: Text(l.actionNextChannelShort)),
                    ]),
                    const SizedBox(height: 8),
                  ]);
                },
              ),
            ]),
          );
        },
      ),
    );
  }
}

class _Progress extends StatelessWidget {
  const _Progress();

  String _fmt(Duration d) => '${d.inMinutes}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final player = context.read<AppState>().graph.player;
    return StreamBuilder<Duration>(
      stream: player.positionStream,
      builder: (context, snapshot) {
        final pos = snapshot.data ?? Duration.zero;
        final dur = player.duration ?? Duration.zero;
        final max = dur.inMilliseconds.toDouble();
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(children: [
            Slider(
              value: max <= 0 ? 0 : pos.inMilliseconds.clamp(0, dur.inMilliseconds).toDouble(),
              max: max <= 0 ? 1 : max,
              onChanged: max <= 0 ? null : (v) => player.seek(Duration(milliseconds: v.toInt())),
            ),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(_fmt(pos)), Text(max <= 0 ? '' : _fmt(dur))]),
          ]),
        );
      },
    );
  }
}
