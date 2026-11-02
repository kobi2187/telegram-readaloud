import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:provider/provider.dart';

import '../data/channel_repository.dart';
import '../l10n/app_localizations.dart';
import 'app_state.dart';

String formatDate(BuildContext context, int millis) =>
    DateFormat.yMMMd(Localizations.localeOf(context).toLanguageTag()).format(DateTime.fromMillisecondsSinceEpoch(millis));

String formatDateTime(BuildContext context, int millis) =>
    DateFormat.MMMd(Localizations.localeOf(context).toLanguageTag()).add_Hm().format(DateTime.fromMillisecondsSinceEpoch(millis));

/// Start of the day [daysBack] days ago, in local time.
int startOfDay(int daysBack) {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day).subtract(Duration(days: daysBack)).millisecondsSinceEpoch;
}

/// Text direction from the text itself, so Hebrew posts align right in either UI language.
TextDirection directionOf(String text) {
  for (final c in text.runes) {
    if ((c >= 0x0590 && c <= 0x05FF) || (c >= 0x0600 && c <= 0x06FF)) return TextDirection.rtl;
    if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || (c >= 0x0400 && c <= 0x04FF)) return TextDirection.ltr;
  }
  return TextDirection.ltr;
}

class ChannelAvatar extends StatelessWidget {
  const ChannelAvatar(this.path, this.title, {super.key, this.size = 48});
  final String? path;
  final String title;
  final double size;

  @override
  Widget build(BuildContext context) {
    final file = path == null ? null : File(path!);
    final letter = title.trim().isEmpty ? '?' : String.fromCharCode(title.trim().runes.first).toUpperCase();
    return CircleAvatar(
      radius: size / 2,
      backgroundImage: file != null && file.existsSync() ? FileImage(file) : null,
      child: file != null && file.existsSync() ? null : Text(letter, style: TextStyle(fontSize: size * 0.4, fontWeight: FontWeight.bold)),
    );
  }
}

/// The mini player at the bottom of the list screens.
class PlayerBar extends StatelessWidget {
  const PlayerBar({super.key, required this.onOpen});
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final player = context.read<AppState>().graph.player;
    return StreamBuilder<MediaItem?>(
      stream: player.mediaItem,
      builder: (context, item) => StreamBuilder<PlaybackState>(
        stream: player.playbackState,
        builder: (context, state) {
          final playing = state.data?.playing ?? false;
          final loading = state.data?.processingState == AudioProcessingState.loading || state.data?.processingState == AudioProcessingState.buffering;
          final title = item.data?.title;
          return Material(
            color: Theme.of(context).colorScheme.surfaceContainerHigh,
            child: SafeArea(
              top: false,
              child: InkWell(
                onTap: title == null ? null : onOpen,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(children: [
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                        Text(title ?? l.playerIdle, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.bodyLarge),
                        if (item.data?.artist != null)
                          Text(item.data!.artist!, maxLines: 1, style: Theme.of(context).textTheme.bodySmall),
                      ]),
                    ),
                    if (loading) const Padding(padding: EdgeInsets.all(12), child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
                    IconButton(
                      icon: Icon(playing ? Icons.pause : Icons.play_arrow),
                      tooltip: playing ? l.actionPause : l.actionPlay,
                      onPressed: () => playing ? player.pause() : player.play(),
                    ),
                  ]),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Choosing where reading starts: today, yesterday, 3 days, a week, or any date.
class StartDateChooser extends StatelessWidget {
  const StartDateChooser({super.key, required this.value, required this.onChanged});
  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final presets = [(0, l.startToday), (1, l.startYesterday), (3, l.start3Days), (7, l.startWeek)];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(l.startFromLabel(formatDate(context, value)), style: Theme.of(context).textTheme.bodyMedium),
      const SizedBox(height: 6),
      Wrap(spacing: 6, runSpacing: 6, children: [
        for (final (days, label) in presets)
          ChoiceChip(label: Text(label), selected: value == startOfDay(days), onSelected: (_) => onChanged(startOfDay(days))),
        ActionChip(
          label: Text(l.startPickDate),
          onPressed: () async {
            final picked = await showDatePicker(
              context: context,
              initialDate: DateTime.fromMillisecondsSinceEpoch(value),
              firstDate: DateTime(2013),
              lastDate: DateTime.now(),
            );
            if (picked != null) onChanged(picked.millisecondsSinceEpoch);
          },
        ),
      ]),
    ]);
  }
}

class AddChannelDialog extends StatefulWidget {
  const AddChannelDialog({super.key, this.initial = ''});
  final String initial;

  @override
  State<AddChannelDialog> createState() => _AddChannelDialogState();
}

class _AddChannelDialogState extends State<AddChannelDialog> {
  late final _input = TextEditingController(text: widget.initial);
  late int _start = startOfDay(context.read<AppState>().graph.settings.value.defaultStartDays);
  String? _error;
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      title: Text(l.addChannelTitle),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          TextField(
            controller: _input,
            autofocus: widget.initial.isEmpty,
            decoration: InputDecoration(labelText: l.addChannelHint, errorText: _error, border: const OutlineInputBorder()),
            onChanged: (_) => setState(() => _error = null),
          ),
          const SizedBox(height: 12),
          StartDateChooser(value: _start, onChanged: (v) => setState(() => _start = v)),
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(MaterialLocalizations.of(context).cancelButtonLabel)),
        TextButton(
          onPressed: _busy || _input.text.trim().isEmpty ? null : _add,
          child: Text(_busy ? l.addChecking : l.actionAdd),
        ),
      ],
    );
  }

  Future<void> _add() async {
    final l = L.of(context);
    setState(() => _busy = true);
    final state = context.read<AppState>();
    final result = await state.graph.channels.add(_input.text, _start);
    if (!mounted) return;
    setState(() => _busy = false);
    switch (result) {
      case Added(:final username):
        state.syncNow(only: username);
        Navigator.pop(context);
      case AddProblem.alreadyExists:
        setState(() => _error = l.addErrorExists);
      case AddProblem.invalidName:
        setState(() => _error = l.addErrorInvalid);
      case AddProblem.noPreview:
        setState(() => _error = l.addErrorNoPreview);
      case AddProblem.noFeed:
        setState(() => _error = l.addErrorNoFeed);
      case AddProblem.siteRefused:
        setState(() => _error = l.addErrorSiteRefused);
      case AddProblem.offline:
        setState(() => _error = l.addErrorOffline);
    }
  }
}
