import 'dart:async';
import 'dart:io';

import 'package:app_links/app_links.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../data/channel_repository.dart';
import '../l10n/app_localizations.dart';
import '../web/web_client.dart';
import 'app_state.dart';
import 'widgets.dart';

/// Takes text shared to the app: from Android's share sheet, or on iPhone a link
/// `readaloud://share?text=…` (what the "Listen later" Shortcut opens). A Telegram link opens
/// the add-channel dialog; any other web link is saved for listening.
class ShareReceiver extends StatefulWidget {
  const ShareReceiver({super.key, required this.child});
  final Widget child;

  @override
  State<ShareReceiver> createState() => _ShareReceiverState();

  /// The shared text inside a `readaloud:` link, or null.
  static String? textOf(Uri uri) {
    if (uri.scheme != 'readaloud') return null;
    final text = uri.queryParameters['text'] ?? uri.queryParameters['url'];
    if (text != null && text.trim().isNotEmpty) return text.trim();
    return null;
  }

  /// Opens the add dialog for a Telegram link, or saves any other web link for listening.
  static Future<void> handle(BuildContext context, String text) async {
    final l = L.of(context);
    final what = classify(text);
    if (what.add != null) {
      await showDialog<void>(context: context, builder: (_) => AddChannelDialog(initial: what.add!));
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    final result = await context.read<AppState>().graph.channels.saveLink(what.save!, l.savedTitle);
    messenger.showSnackBar(SnackBar(
      content: Text(switch (result) {
        Saved(:final title) => title != null ? l.savedToast(title) : l.savedToastPending,
        AlreadySaved() => l.savedAlready,
      }),
    ));
  }

  /// Listens to the link on the clipboard (the way to share on iPhone without the Shortcut).
  static Future<void> pasteAndListen(BuildContext context) async {
    final l = L.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final text = (await Clipboard.getData(Clipboard.kTextPlain))?.text ?? '';
    if (!context.mounted) return;
    if (WebClient.firstUrl(text) == null && !text.contains('t.me/')) {
      messenger.showSnackBar(SnackBar(content: Text(l.pasteNoLink)));
      return;
    }
    await handle(context, text);
  }

  /// What to do with shared [text]: a web link (not Telegram) to save, or text for the add dialog.
  static ({String? save, String? add}) classify(String text) {
    final link = WebClient.firstUrl(text);
    final telegram = text.contains('t.me/') || text.contains('telegram.me/');
    return link != null && !telegram ? (save: link, add: null) : (save: null, add: text);
  }
}

class _ShareReceiverState extends State<ShareReceiver> {
  final _subs = <StreamSubscription<Object?>>[];
  String? _last;
  DateTime _lastAt = DateTime(0);

  @override
  void initState() {
    super.initState();
    if (Platform.isAndroid) {
      const MethodChannel('readaloud/share').invokeMethod<String>('initial').then((t) {
        if (t != null) _received(t);
      }, onError: (Object e) => debugPrint('No share channel: $e'));
      _subs.add(const EventChannel('readaloud/shares').receiveBroadcastStream().listen((t) => _received(t as String), onError: (Object _) {}));
    } else if (Platform.isIOS) {
      void open(Uri? uri) {
        final text = uri == null ? null : ShareReceiver.textOf(uri);
        if (text != null) _received(text);
      }

      final links = AppLinks();
      // The link that launched the app, then later ones (a repeat of the first is ignored).
      links.getInitialLink().then(open, onError: (Object _) {});
      _subs.add(links.uriLinkStream.listen(open, onError: (Object _) {}));
    }
  }

  @override
  void dispose() {
    for (final s in _subs) {
      s.cancel();
    }
    super.dispose();
  }

  Future<void> _received(String text) async {
    // The same share can arrive twice (as the initial one and as an event).
    if (text == _last && DateTime.now().difference(_lastAt) < const Duration(seconds: 5)) return;
    _last = text;
    _lastAt = DateTime.now();
    // Wait for the first frame, so the dialog and messages have somewhere to show.
    await WidgetsBinding.instance.endOfFrame;
    if (mounted) await ShareReceiver.handle(context, text);
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
