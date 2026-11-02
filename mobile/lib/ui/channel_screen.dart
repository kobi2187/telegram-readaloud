import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/models.dart';
import '../l10n/app_localizations.dart';
import 'app_state.dart';
import 'home_screen.dart';
import 'now_playing_screen.dart';
import 'widgets.dart';

/// A channel's posts since its start date, with what has been heard and what is ready offline.
class ChannelScreen extends StatefulWidget {
  const ChannelScreen({super.key, required this.username});
  final String username;

  @override
  State<ChannelScreen> createState() => _ChannelScreenState();
}

class _ChannelScreenState extends State<ChannelScreen> {
  final _scroll = ScrollController();
  Channel? _channel;
  List<Message> _posts = const [];
  bool _positioned = false;
  bool _atEnd = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final atEnd = _scroll.hasClients && _scroll.position.pixels >= _scroll.position.maxScrollExtent - 4;
      if (atEnd != _atEnd) setState(() => _atEnd = atEnd);
    });
    context.read<AppState>().addListener(_load);
    _load();
  }

  @override
  void dispose() {
    context.read<AppState>().removeListener(_load);
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final db = context.read<AppState>().graph.db;
    final ch = await db.channel(widget.username);
    final posts = ch == null ? <Message>[] : await db.channelMessages(widget.username, ch.startDate);
    if (!mounted) return;
    setState(() {
      _channel = ch;
      _posts = posts;
    });
    if (!_positioned && posts.isNotEmpty) {
      // Open at the next post to be read.
      _positioned = true;
      final i = posts.indexWhere((p) => p.id >= (ch?.cursorId ?? 0));
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (i > 0 && _scroll.hasClients) _scroll.jumpTo((i * 140.0).clamp(0, _scroll.position.maxScrollExtent));
      });
    }
  }

  void _jump() {
    if (!_scroll.hasClients) return;
    _scroll.jumpTo(_atEnd ? 0 : _scroll.position.maxScrollExtent);
    // Long lists measure lazily; settle on the real end.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_atEnd && _scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final state = context.read<AppState>();
    final ch = _channel;
    final scrollable = _scroll.hasClients && _scroll.position.maxScrollExtent > 0;
    return Scaffold(
      appBar: AppBar(
        title: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(ch?.title ?? widget.username, maxLines: 1, overflow: TextOverflow.ellipsis),
          if (ch != null) Text(l.channelFrom(formatDate(context, ch.startDate)), style: Theme.of(context).textTheme.bodySmall),
        ]),
        actions: [
          if (ch != null && ch.kind != ChannelKind.saved)
            IconButton(
              icon: const Icon(Icons.date_range),
              tooltip: l.menuStartDate,
              onPressed: () async {
                final date = await showDialog<int>(context: context, builder: (_) => StartDateDialog(initial: ch.startDate));
                if (date != null) {
                  await state.graph.channels.setStartDate(ch.username, date);
                  state.syncNow(only: ch.username);
                }
              },
            ),
          IconButton(icon: const Icon(Icons.play_arrow), tooltip: l.actionPlay, onPressed: () => state.graph.player.playChannel(widget.username)),
        ],
      ),
      floatingActionButton: scrollable
          ? FloatingActionButton.small(
              onPressed: _jump,
              tooltip: _atEnd ? l.actionJumpStart : l.actionJumpEnd,
              child: Icon(_atEnd ? Icons.vertical_align_top : Icons.vertical_align_bottom),
            )
          : null,
      bottomNavigationBar: PlayerBar(onOpen: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const NowPlayingScreen()))),
      body: _posts.isEmpty
          ? Center(child: Text(l.channelNoPosts, style: TextStyle(color: Theme.of(context).colorScheme.secondary)))
          : StreamBuilder<MediaItem?>(
              stream: state.graph.player.mediaItem,
              builder: (context, item) => ListView.separated(
                controller: _scroll,
                padding: const EdgeInsets.all(12),
                itemCount: _posts.length,
                separatorBuilder: (_, _) => const SizedBox(height: 8),
                itemBuilder: (context, i) {
                  final post = _posts[i];
                  final playing = item.data?.extras?['channel'] == widget.username && item.data?.extras?['id'] == post.id;
                  return _PostCard(
                    post: post,
                    heard: post.id < (ch?.cursorId ?? 0) || post.played,
                    playing: playing,
                    onTap: post.speech.trim().isEmpty ? null : () => state.graph.player.playChannel(widget.username, fromId: post.id, announce: false),
                  );
                },
              ),
            ),
    );
  }
}

class _PostCard extends StatelessWidget {
  const _PostCard({required this.post, required this.heard, required this.playing, required this.onTap});
  final Message post;
  final bool heard;
  final bool playing;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final scheme = Theme.of(context).colorScheme;
    final readable = post.speech.trim().isNotEmpty;
    final body = post.text.trim().isEmpty ? l.postMediaOnly(post.media ?? '') : post.text;
    Widget status(IconData icon, String label) => Padding(
          padding: const EdgeInsetsDirectional.only(start: 6),
          child: Tooltip(message: label, child: Icon(icon, size: 18, color: scheme.secondary)),
        );
    return Card(
      color: playing ? scheme.primaryContainer : (heard || !readable ? scheme.surfaceContainerLow : null),
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(formatDateTime(context, post.date), style: Theme.of(context).textTheme.labelMedium?.copyWith(color: scheme.secondary))),
              if (playing) status(Icons.graphic_eq, l.statusPlaying),
              if (heard) status(Icons.check_circle, l.statusHeard),
              if (post.link != null)
                IconButton(
                  visualDensity: VisualDensity.compact,
                  iconSize: 18,
                  tooltip: l.actionOpenLink,
                  icon: const Icon(Icons.open_in_new),
                  onPressed: () => launchUrl(Uri.parse(post.link!), mode: LaunchMode.externalApplication),
                ),
              if (readable)
                post.audioState == AudioState.neural
                    ? status(Icons.offline_pin, l.statusNeuralReady)
                    : post.audioPath != null
                        ? status(Icons.phone_android, l.statusDeviceReady)
                        : status(Icons.cloud_download, l.statusNotDownloaded),
            ]),
            const SizedBox(height: 4),
            SizedBox(
              width: double.infinity,
              child: Text(
                body,
                textDirection: directionOf(body),
                maxLines: playing ? null : 8,
                overflow: playing ? null : TextOverflow.ellipsis,
                style: TextStyle(color: readable ? scheme.onSurface : scheme.secondary),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}
