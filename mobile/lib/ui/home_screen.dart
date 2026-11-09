import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../data/models.dart';
import '../l10n/app_localizations.dart';
import '../sync/sync_engine.dart';
import 'app_state.dart';
import 'channel_screen.dart';
import 'now_playing_screen.dart';
import 'settings_screen.dart';
import 'share_receiver.dart';
import 'widgets.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final state = context.watch<AppState>();
    final graph = state.graph;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.appName),
        actions: [
          ValueListenableBuilder<SyncState>(
            valueListenable: graph.sync.state,
            builder: (context, sync, _) => IconButton(
              icon: const Icon(Icons.sync),
              tooltip: l.actionSync,
              onPressed: sync.running ? null : () => state.syncNow(),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.content_paste_go),
            tooltip: l.actionPasteLink,
            onPressed: () => ShareReceiver.pasteAndListen(context),
          ),
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: l.settingsTitle,
            onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const SettingsScreen())),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => showDialog<void>(context: context, builder: (_) => const AddChannelDialog()),
        icon: const Icon(Icons.add),
        label: Text(l.addChannelTitle),
      ),
      bottomNavigationBar: PlayerBar(onOpen: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const NowPlayingScreen()))),
      body: Column(children: [
        const _SyncStatus(),
        ValueListenableBuilder(
          valueListenable: graph.voices.state,
          builder: (context, _, _) => graph.settings.value.cloudEnabled || graph.renderer.offlineHebrew.activeId != null
              ? const SizedBox.shrink()
              : Card(
            color: Theme.of(context).colorScheme.secondaryContainer,
            margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: InkWell(
              onTap: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const SettingsScreen())),
              child: Padding(padding: const EdgeInsets.all(12), child: Text(l.tipBetterVoices, style: Theme.of(context).textTheme.bodySmall)),
            ),
          ),
        ),
        Expanded(
          child: state.channels.isEmpty
              ? Center(child: Padding(padding: const EdgeInsets.all(32), child: Text(l.emptyChannels, textAlign: TextAlign.center)))
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
                  itemCount: state.channels.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) => _ChannelCard(state.channels[i]),
                ),
        ),
      ]),
    );
  }
}

class _SyncStatus extends StatelessWidget {
  const _SyncStatus();

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return ValueListenableBuilder<SyncState>(
      valueListenable: context.read<AppState>().graph.sync.state,
      builder: (context, s, _) {
        String? text;
        if (s.running && s.phase == SyncPhase.text) text = l.syncTextProgress(s.channel ?? '');
        if (s.running && s.phase == SyncPhase.audio) text = l.syncAudioProgress(s.audioDone, s.audioTotal);
        if (!s.running && s.offline) text = l.syncOffline;
        if (text == null) return const SizedBox.shrink();
        return Column(children: [
          if (s.running) const LinearProgressIndicator(minHeight: 2),
          Padding(padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6), child: Text(text, style: Theme.of(context).textTheme.bodySmall)),
        ]);
      },
    );
  }
}

class _ChannelCard extends StatelessWidget {
  const _ChannelCard(this.summary);
  final ChannelSummary summary;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final state = context.read<AppState>();
    final ch = summary.channel;
    final subtitle = switch (ch.kind) {
      ChannelKind.feed => '${Uri.tryParse(ch.url ?? '')?.host.replaceFirst('www.', '') ?? ''} · ${l.channelFrom(formatDate(context, ch.startDate))}',
      ChannelKind.saved => l.savedSubtitle,
      _ => '@${ch.username} · ${l.channelFrom(formatDate(context, ch.startDate))}',
    };
    final counts = summary.remaining == 0
        ? l.channelCaughtUp
        : '${l.channelRemaining(summary.remaining)} · ${l.channelOfflineReady(summary.audioReady)}';
    final error = switch (ch.lastError) {
      null => null,
      SyncEngine.errorNoPreview => l.errorNoPreview,
      SyncEngine.errorRateLimit => l.errorRateLimited,
      SyncEngine.errorSiteRefused => l.errorSiteRefused,
      _ => l.errorSyncFailed,
    };
    return StreamBuilder<MediaItem?>(
      stream: state.graph.player.mediaItem,
      builder: (context, item) {
        final playing = item.data?.extras?['channel'] == ch.username;
        return Card(
          color: playing ? Theme.of(context).colorScheme.primaryContainer : null,
          child: InkWell(
            onTap: () => state.graph.player.playChannel(ch.username),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(children: [
                ChannelAvatar(ch.avatarPath, ch.title),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      if (playing) Padding(padding: const EdgeInsetsDirectional.only(end: 4), child: Icon(Icons.graphic_eq, color: Theme.of(context).colorScheme.primary, size: 20)),
                      Expanded(child: Text(ch.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleMedium)),
                    ]),
                    Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.secondary)),
                    Text(counts, style: Theme.of(context).textTheme.bodySmall),
                    if (error != null) Text(error, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.error)),
                    if (!ch.enabled) Text(l.channelPaused, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.error)),
                  ]),
                ),
                _ChannelMenu(ch),
              ]),
            ),
          ),
        );
      },
    );
  }
}

class _ChannelMenu extends StatelessWidget {
  const _ChannelMenu(this.ch);
  final Channel ch;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final state = context.read<AppState>();
    final repo = state.graph.channels;
    return PopupMenuButton<String>(
      tooltip: l.actionMore,
      onSelected: (v) async {
        switch (v) {
          case 'posts':
            await Navigator.push(context, MaterialPageRoute<void>(builder: (_) => ChannelScreen(username: ch.username)));
          case 'start':
            final date = await showDialog<int>(context: context, builder: (_) => StartDateDialog(initial: ch.startDate));
            if (date != null) {
              await repo.setStartDate(ch.username, date);
              state.syncNow(only: ch.username);
            }
          case 'translate':
            final lang = await showDialog<String>(context: context, builder: (_) => TranslateDialog(current: ch.translateTo));
            if (lang != null) {
              await repo.setTranslateTo(ch.username, lang.isEmpty ? null : lang);
              state.syncNow(only: ch.username);
            }
          case 'heard':
            await repo.markAllHeard(ch.username);
          case 'restart':
            await repo.restart(ch.username);
          case 'up':
            await repo.move(ch.username, -1);
          case 'down':
            await repo.move(ch.username, 1);
          case 'pause':
            await repo.setEnabled(ch.username, !ch.enabled);
          case 'remove':
            final ok = await showDialog<bool>(
              context: context,
              builder: (context) => AlertDialog(
                title: Text(l.removeTitle(ch.title)),
                content: Text(l.removeBody),
                actions: [
                  TextButton(onPressed: () => Navigator.pop(context, false), child: Text(MaterialLocalizations.of(context).cancelButtonLabel)),
                  TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l.menuRemove)),
                ],
              ),
            );
            if (ok == true) await repo.remove(ch.username);
        }
      },
      itemBuilder: (_) => [
        PopupMenuItem(value: 'posts', child: Text(l.menuPosts)),
        if (ch.kind != ChannelKind.saved) PopupMenuItem(value: 'start', child: Text(l.menuStartDate)),
        PopupMenuItem(value: 'translate', child: Text(l.actionTranslate)),
        PopupMenuItem(value: 'heard', child: Text(l.menuMarkHeard)),
        PopupMenuItem(value: 'restart', child: Text(l.menuRestart)),
        PopupMenuItem(value: 'up', child: Text(l.menuMoveUp)),
        PopupMenuItem(value: 'down', child: Text(l.menuMoveDown)),
        PopupMenuItem(value: 'pause', child: Text(ch.enabled ? l.menuPauseChannel : l.menuResumeChannel)),
        PopupMenuItem(value: 'remove', child: Text(l.menuRemove)),
      ],
    );
  }
}

class StartDateDialog extends StatefulWidget {
  const StartDateDialog({super.key, required this.initial});
  final int initial;

  @override
  State<StartDateDialog> createState() => _StartDateDialogState();
}

class _StartDateDialogState extends State<StartDateDialog> {
  late int _value = widget.initial;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      title: Text(l.changeStartTitle),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(l.changeStartBody, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 8),
        StartDateChooser(value: _value, onChanged: (v) => setState(() => _value = v)),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(MaterialLocalizations.of(context).cancelButtonLabel)),
        TextButton(onPressed: () => Navigator.pop(context, _value), child: Text(MaterialLocalizations.of(context).okButtonLabel)),
      ],
    );
  }
}
