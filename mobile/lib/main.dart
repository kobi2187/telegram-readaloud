import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:provider/provider.dart';

import 'app_graph.dart';
import 'l10n/app_localizations.dart';
import 'l10n/app_localizations_en.dart';
import 'l10n/app_localizations_he.dart';
import 'playback/reader_handler.dart';
import 'ui/app_state.dart';
import 'ui/home_screen.dart';
import 'ui/share_receiver.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Notices are spoken in the phone's language.
  final locale = WidgetsBinding.instance.platformDispatcher.locale;
  final L strings = locale.languageCode == 'he' || locale.languageCode == 'iw' ? LHe() : LEn();
  final graph = await AppGraph.create(
    appName: strings.appName,
    noticeText: (key) => key == ReaderHandler.noticeNoOther ? strings.noticeNoOtherChannels : strings.noticeCaughtUp,
  );
  runApp(ChangeNotifierProvider(create: (_) => AppState(graph), child: const ReadAloudApp()));
}

class ReadAloudApp extends StatelessWidget {
  const ReadAloudApp({super.key});

  @override
  Widget build(BuildContext context) {
    ThemeData theme(Brightness b) => ThemeData(colorSchemeSeed: const Color(0xFF2E5AAC), brightness: b, useMaterial3: true);
    return MaterialApp(
      onGenerateTitle: (context) => L.of(context).appName,
      theme: theme(Brightness.light),
      darkTheme: theme(Brightness.dark),
      localizationsDelegates: const [L.delegate, GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
      supportedLocales: L.supportedLocales,
      home: const ShareReceiver(child: HomeScreen()),
    );
  }
}
