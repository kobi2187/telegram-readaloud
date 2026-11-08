import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/settings.dart';
import '../l10n/app_localizations.dart';
import '../text/language_segmenter.dart';
import '../tts/audio_renderer.dart';
import '../tts/gemini_tts.dart';
import '../tts/voices.dart';
import '../voice/voice_catalog.dart';
import '../voice/voice_downloads.dart';
import 'app_state.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _preview = AudioPlayer();
  bool? _hebrewDevice;
  bool? _englishDevice;
  int? _cacheSize;
  bool _previewFailed = false;

  @override
  void initState() {
    super.initState();
    final graph = context.read<AppState>().graph;
    graph.device.isLanguageAvailable(Lang.he).then((v) => mounted ? setState(() => _hebrewDevice = v) : null);
    graph.device.isLanguageAvailable(Lang.en).then((v) => mounted ? setState(() => _englishDevice = v) : null);
    graph.renderer.cacheSizeBytes().then((v) => mounted ? setState(() => _cacheSize = v) : null);
  }

  @override
  void dispose() {
    _preview.dispose();
    super.dispose();
  }

  Future<bool> _playPreview(AppSettings cfg, String voice, String sample) async {
    try {
      final file = await context.read<AppState>().graph.renderer.previewCloud(cfg, voice, sample);
      await _preview.setFilePath(file.path);
      await _preview.play();
      return true;
    } catch (_) {
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final graph = context.read<AppState>().graph;
    final store = graph.settings;
    return Scaffold(
      appBar: AppBar(title: Text(l.settingsTitle)),
      body: ValueListenableBuilder<AppSettings>(
        valueListenable: store,
        builder: (context, s, _) => ValueListenableBuilder<CloudHealth>(
          valueListenable: graph.renderer.health,
          builder: (context, health, _) {
            final status = health.voice == s.cloudVoice ? health.status : CloudStatus.ok;
            void set(AppSettings Function(AppSettings) f) => store.update(f);
            return ListView(padding: const EdgeInsets.only(bottom: 32), children: [
              _Section(l.settingsVoice),
              _Hint(l.onlineVoiceDesc),
              for (final (v, title, desc) in [
                (CloudVoice.gemini, l.ttsGemini, l.ttsGeminiDesc),
                (CloudVoice.azure, l.ttsAzure, l.ttsAzureDesc),
                (CloudVoice.none, l.ttsNone, l.ttsNoneDesc),
              ])
                RadioListTile<CloudVoice>(
                  value: v,
                  groupValue: s.cloudVoice,
                  onChanged: (x) => set((c) => c.copyWith(cloudVoice: x)),
                  title: Text(title),
                  subtitle: Text(desc),
                ),
              if (s.cloudVoice == CloudVoice.gemini) ...[
                _KeyEditor(
                  label: l.geminiKey,
                  initial: s.geminiKey,
                  status: status,
                  getKeyLabel: l.geminiGetKey,
                  getKeyUrl: 'https://aistudio.google.com/apikey',
                  messages: (l.geminiNoKey, l.geminiOk, l.geminiBadKey, l.geminiQuota, l.geminiThrottled),
                  onSaveTest: (key, _) async {
                    await store.update((c) => c.copyWith(geminiKey: key));
                    final cfg = store.value.copyWith(cloudVoice: CloudVoice.gemini, geminiKey: key);
                    return _playPreview(cfg, cfg.geminiVoice, l.voiceSampleHe);
                  },
                ),
                _VoicePicker(
                  title: l.voiceGemini,
                  voices: [for (final (name, character) in GeminiTts.voices) NeuralVoice(name, '$name · $character', male: false)],
                  selected: s.geminiVoice,
                  onPreview: (v) async {
                    final ok = await _playPreview(s, v, l.voiceSampleHe);
                    if (mounted) setState(() => _previewFailed = !ok);
                  },
                  onSelect: (v) => set((c) => c.copyWith(geminiVoice: v)),
                ),
                _Hint(l.geminiNote),
              ],
              if (s.cloudVoice == CloudVoice.azure) ...[
                _KeyEditor(
                  label: l.azureKey,
                  initial: s.azureKey,
                  regionLabel: l.azureRegion,
                  initialRegion: s.azureRegion,
                  status: status,
                  getKeyLabel: l.azureGetKey,
                  getKeyUrl: 'https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices',
                  messages: (l.azureNoKey, l.azureOk, l.azureBadKey, l.azureQuota, l.azureThrottled),
                  onSaveTest: (key, region) async {
                    await store.update((c) => c.copyWith(azureKey: key, azureRegion: region));
                    final cfg = store.value.copyWith(cloudVoice: CloudVoice.azure, azureKey: key, azureRegion: region);
                    return _playPreview(cfg, cfg.hebrewVoice, l.voiceSampleHe);
                  },
                ),
                _VoicePicker(title: l.voiceHebrew, voices: Voices.hebrew, selected: s.hebrewVoice, onPreview: (v) => _playPreview(s, v, l.voiceSampleHe), onSelect: (v) => set((c) => c.copyWith(hebrewVoice: v))),
                _VoicePicker(title: l.voiceEnglish, voices: Voices.english, selected: s.englishVoice, onPreview: (v) => _playPreview(s, v, l.voiceSampleEn), onSelect: (v) => set((c) => c.copyWith(englishVoice: v))),
              ],
              if (_previewFailed) _Hint(l.voicePreviewFailed, error: true),
              _Section(l.settingsOfflineVoice),
              _Hint(l.offlineVoicesDesc),
              ListenableBuilder(
                listenable: graph.voiceDownloads,
                builder: (context, _) => Column(children: [
                  for (final pack in graph.voices.catalog)
                    _OfflineVoiceItem(
                      pack: pack,
                      downloads: graph.voiceDownloads,
                      onPreview: () async {
                        try {
                          final file = await graph.renderer.previewOffline(pack.id, l.voiceSampleHe);
                          await _preview.setFilePath(file.path);
                          await _preview.play();
                          return true;
                        } catch (_) {
                          return false;
                        }
                      },
                    ),
                ]),
              ),
              _Hint(l.deviceVoicesStatus(_mark(_hebrewDevice), _mark(_englishDevice)), error: _hebrewDevice == false || _englishDevice == false),
              _Section(l.settingsPlayback),
              if (Platform.isAndroid) ...[
                ListTile(title: Text(l.settingsVolumeKeys), subtitle: Text(l.volumeKeysDesc)),
                for (final (mode, title) in [
                  (VolumeKeyMode.channels, l.volumeKeysChannels),
                  (VolumeKeyMode.messages, l.volumeKeysMessages),
                  (VolumeKeyMode.off, l.volumeKeysOff),
                ])
                  RadioListTile<VolumeKeyMode>(
                    value: mode,
                    groupValue: s.volumeKeys,
                    onChanged: (x) => set((c) => c.copyWith(volumeKeys: x)),
                    title: Text(title),
                    dense: true,
                  ),
              ],
              SwitchListTile(value: s.announceChannel, onChanged: (v) => set((c) => c.copyWith(announceChannel: v)), title: Text(l.announceChannel), subtitle: Text(l.announceChannelDesc)),
              SwitchListTile(value: s.autoAdvance, onChanged: (v) => set((c) => c.copyWith(autoAdvance: v)), title: Text(l.autoAdvance), subtitle: Text(l.autoAdvanceDesc)),
              _Section(l.settingsTranslation),
              _Hint(l.translationDesc),
              _TranslateKeyField(initial: s.translateKey, onSave: (k) => store.update((c) => c.copyWith(translateKey: k))),
              _Section(l.settingsSync),
              _Choice<int>(
                title: l.syncInterval,
                value: s.syncIntervalMinutes,
                options: [for (final m in [0, 30, 60, 180, 360]) (m, m == 0 ? l.syncManual : l.minutes(m))],
                onChanged: (v) => set((c) => c.copyWith(syncIntervalMinutes: v)),
              ),
              SwitchListTile(value: s.allowMetered, onChanged: (v) => set((c) => c.copyWith(allowMetered: v)), title: Text(l.allowMetered), subtitle: Text(l.allowMeteredDesc)),
              _Choice<int>(
                title: l.prefetchCount,
                value: s.prefetchCount,
                options: [for (final n in [25, 50, 100, 200, 500]) (n, '$n')],
                onChanged: (v) => set((c) => c.copyWith(prefetchCount: v)),
              ),
              _Choice<int>(
                title: l.defaultStart,
                value: s.defaultStartDays,
                options: [for (final d in [0, 1, 3, 7]) (d, d == 0 ? l.startToday : l.daysBack(d))],
                onChanged: (v) => set((c) => c.copyWith(defaultStartDays: v)),
              ),
              _Section(l.settingsStorage),
              SwitchListTile(value: s.deletePlayedAudio, onChanged: (v) => set((c) => c.copyWith(deletePlayedAudio: v)), title: Text(l.deletePlayed), subtitle: Text(l.deletePlayedDesc)),
              ListTile(
                title: Text(l.cacheSize(_cacheSize == null ? '…' : _size(_cacheSize!))),
                trailing: OutlinedButton(
                  onPressed: () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: Text(l.clearCache),
                        content: Text(l.clearCacheBody),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(MaterialLocalizations.of(context).cancelButtonLabel)),
                          TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l.clearCache)),
                        ],
                      ),
                    );
                    if (ok != true) return;
                    await graph.db.clearAllAudio();
                    await graph.renderer.clearAll();
                    setState(() => _cacheSize = 0);
                  },
                  child: Text(l.clearCache),
                ),
              ),
              _Section(l.settingsAbout),
              _Hint(l.aboutText),
            ]);
          },
        ),
      ),
    );
  }

  static String _mark(bool? v) => switch (v) { true => '✓', false => '✗', null => '…' };

  static String size(int bytes) => _size(bytes);

  static String _size(int bytes) {
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title);
  final String title;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Divider(height: 32),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Text(title, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Theme.of(context).colorScheme.primary)),
        ),
      ]);
}

class _Hint extends StatelessWidget {
  const _Hint(this.text, {this.error = false});
  final String text;
  final bool error;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        child: Text(text, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: error ? Theme.of(context).colorScheme.error : Theme.of(context).colorScheme.secondary)),
      );
}

class _Choice<T> extends StatelessWidget {
  const _Choice({required this.title, required this.value, required this.options, required this.onChanged});
  final String title;
  final T value;
  final List<(T, String)> options;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final label = options.where((o) => o.$1 == value).map((o) => o.$2).firstOrNull ?? '$value';
    return ListTile(
      title: Text(title),
      trailing: Text(label, style: TextStyle(color: Theme.of(context).colorScheme.primary)),
      onTap: () async {
        final picked = await showDialog<T>(
          context: context,
          builder: (context) => SimpleDialog(
            title: Text(title),
            children: [for (final (v, l) in options) RadioListTile<T>(value: v, groupValue: value, onChanged: (x) => Navigator.pop(context, x), title: Text(l))],
          ),
        );
        if (picked != null) onChanged(picked);
      },
    );
  }
}

class _VoicePicker extends StatelessWidget {
  const _VoicePicker({required this.title, required this.voices, required this.selected, required this.onPreview, required this.onSelect});
  final String title;
  final List<NeuralVoice> voices;
  final String selected;
  final void Function(String) onPreview;
  final ValueChanged<String> onSelect;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final current = voices.where((v) => v.id == selected).map((v) => v.label).firstOrNull ?? selected;
    return ListTile(
      title: Text(title),
      subtitle: Text(current, style: TextStyle(color: Theme.of(context).colorScheme.primary)),
      trailing: IconButton(icon: const Icon(Icons.volume_up), tooltip: l.voicePreview, onPressed: () => onPreview(selected)),
      onTap: () async {
        final picked = await showDialog<String>(
          context: context,
          builder: (context) => SimpleDialog(title: Text(title), children: [
            for (final v in voices)
              Row(children: [
                Expanded(child: RadioListTile<String>(value: v.id, groupValue: selected, onChanged: (x) => Navigator.pop(context, x), title: Text(v.label))),
                IconButton(icon: const Icon(Icons.volume_up), tooltip: l.voicePreview, onPressed: () => onPreview(v.id)),
              ]),
          ]),
        );
        if (picked != null) onSelect(picked);
      },
    );
  }
}

/// A key (and optionally region) with "save and test", a link to get one, and what we know about it.
class _KeyEditor extends StatefulWidget {
  const _KeyEditor({
    required this.label,
    required this.initial,
    this.regionLabel,
    this.initialRegion,
    required this.status,
    required this.getKeyLabel,
    required this.getKeyUrl,
    required this.messages,
    required this.onSaveTest,
  });
  final String label;
  final String initial;
  final String? regionLabel;
  final String? initialRegion;
  final CloudStatus status;
  final String getKeyLabel;
  final String getKeyUrl;
  final (String, String, String, String, String) messages; // no key, ok, bad key, quota, throttled
  final Future<bool> Function(String key, String region) onSaveTest;

  @override
  State<_KeyEditor> createState() => _KeyEditorState();
}

class _KeyEditorState extends State<_KeyEditor> {
  late final _key = TextEditingController(text: widget.initial);
  late final _region = TextEditingController(text: widget.initialRegion ?? '');
  bool _testing = false;
  bool? _tested;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final (noKey, ok, badKey, quota, throttled) = widget.messages;
    final message = widget.initial.trim().isEmpty
        ? noKey
        : switch (widget.status) {
            CloudStatus.badKey => badKey,
            CloudStatus.quota => quota,
            CloudStatus.throttled => throttled,
            CloudStatus.ok => _tested == false ? l.voicePreviewFailed : (_tested == true ? ok : null),
          };
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(controller: _key, obscureText: true, decoration: InputDecoration(labelText: widget.label, border: const OutlineInputBorder()), onChanged: (_) => setState(() => _tested = null)),
        if (widget.regionLabel != null) ...[
          const SizedBox(height: 8),
          TextField(controller: _region, decoration: InputDecoration(labelText: widget.regionLabel, border: const OutlineInputBorder()), onChanged: (_) => setState(() => _tested = null)),
        ],
        Row(children: [
          OutlinedButton(
            onPressed: _testing || _key.text.trim().isEmpty
                ? null
                : () async {
                    setState(() => _testing = true);
                    final result = await widget.onSaveTest(_key.text.trim(), _region.text.trim().toLowerCase());
                    if (mounted) setState(() {
                      _testing = false;
                      _tested = result;
                    });
                  },
            child: Text(_testing ? l.addChecking : l.keySaveTest),
          ),
          const SizedBox(width: 8),
          TextButton(onPressed: () => launchUrl(Uri.parse(widget.getKeyUrl), mode: LaunchMode.externalApplication), child: Text(widget.getKeyLabel)),
        ]),
        if (message != null)
          Text(message, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: message == ok ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.error)),
      ]),
    );
  }
}


/// Renders at less than about 1.2× real time: playback would keep pausing to wait for it.
const _slowVoice = 0.85;

/// One downloadable offline voice: select, download, preview or remove it.
class _OfflineVoiceItem extends StatefulWidget {
  const _OfflineVoiceItem({required this.pack, required this.downloads, required this.onPreview});
  final VoicePack pack;
  final VoiceDownloads downloads;
  final Future<bool> Function() onPreview;

  @override
  State<_OfflineVoiceItem> createState() => _OfflineVoiceItemState();
}

class _OfflineVoiceItemState extends State<_OfflineVoiceItem> {
  bool _previewFailed = false;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final theme = Theme.of(context);
    final pack = widget.pack;
    final voices = widget.downloads.voices;
    final state = voices.state.value;
    final installed = state.installed.contains(pack.id);
    final active = state.active == pack.id;
    final status = widget.downloads.statusOf(pack.id);
    final toDownload = voices.remainingBytes(pack.id);
    final small = theme.textTheme.bodySmall;
    final error = small?.copyWith(color: theme.colorScheme.error);
    final secondary = small?.copyWith(color: theme.colorScheme.secondary);
    final traits = [
      if (pack.id == VoiceCatalog.recommendedId) l.voiceRecommended,
      pack.male ? l.voiceMale : l.voiceFemale,
      pack.heavy ? l.voiceHeavy : l.voiceFast,
      pack.license,
    ].join(' · ');
    final speed = state.speed[pack.id];

    Future<void> remove() async {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(l.offlineVoiceRemove),
          content: Text(l.offlineVoiceRemoveBody(pack.name)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: Text(MaterialLocalizations.of(context).cancelButtonLabel)),
            TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l.offlineVoiceRemove)),
          ],
        ),
      );
      if (ok == true) await voices.remove(pack.id);
    }

    final Widget action = switch (status) {
      _ when installed => Row(mainAxisSize: MainAxisSize.min, children: [
          IconButton(
            tooltip: l.voicePreview,
            icon: const Icon(Icons.volume_up),
            onPressed: () async {
              final ok = await widget.onPreview();
              if (mounted) setState(() => _previewFailed = !ok);
            },
          ),
          TextButton(onPressed: remove, child: Text(l.offlineVoiceRemove)),
        ]),
      Downloading() || WaitingForNetwork() => TextButton(
          onPressed: () => widget.downloads.cancel(pack.id),
          child: Text(MaterialLocalizations.of(context).cancelButtonLabel),
        ),
      _ => OutlinedButton(
          onPressed: () => widget.downloads.download(pack.id),
          child: Text(l.offlineVoiceDownload(_SettingsScreenState.size(toDownload))),
        ),
    };

    return InkWell(
      onTap: installed ? () => voices.select(pack.id) : null,
      child: Padding(
        padding: const EdgeInsetsDirectional.only(start: 4, end: 8, top: 6, bottom: 6),
        child: Row(children: [
          IgnorePointer(
            child: Radio<bool>(value: true, groupValue: active, onChanged: installed ? (_) {} : null),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(pack.name, style: theme.textTheme.bodyLarge),
              Text(traits, style: secondary),
              switch (status) {
                Downloading(:final done, :final total) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(l.offlineVoiceDownloading(_SettingsScreenState.size(done), _SettingsScreenState.size(total)), style: small),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: LinearProgressIndicator(value: total > 0 ? (done / total).clamp(0.0, 1.0) : null),
                    ),
                  ]),
                WaitingForNetwork(:final wifiOnly) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(wifiOnly ? l.offlineVoiceWaitingWifi : l.offlineVoiceWaiting, style: small),
                    if (wifiOnly)
                      TextButton(
                        onPressed: () => widget.downloads.download(pack.id, mobileData: true),
                        child: Text(l.offlineVoiceUseMobileData(_SettingsScreenState.size(toDownload))),
                      ),
                  ]),
                DownloadFailed(:final reason, :final neededBytes) => Text(
                    switch (reason) {
                      FailReason.noSpace => l.offlineVoiceNoSpace(_SettingsScreenState.size(neededBytes)),
                      FailReason.unsupported => l.offlineVoiceUnsupported,
                      FailReason.failed => l.offlineVoiceFailed,
                    },
                    style: error,
                  ),
                NotDownloading() => const SizedBox.shrink(),
              },
              if (installed && speed != null)
                Text(
                  speed > _slowVoice ? l.offlineVoiceTooSlow : l.offlineVoiceSpeed((1 / speed).toStringAsFixed(1)),
                  style: speed > _slowVoice ? error : secondary,
                ),
              if (_previewFailed) Text(l.offlineVoiceUnsupported, style: error),
            ]),
          ),
          action,
        ]),
      ),
    );
  }
}

/// The optional Google Cloud Translation API key.
class _TranslateKeyField extends StatefulWidget {
  const _TranslateKeyField({required this.initial, required this.onSave});
  final String initial;
  final Future<void> Function(String key) onSave;

  @override
  State<_TranslateKeyField> createState() => _TranslateKeyFieldState();
}

class _TranslateKeyFieldState extends State<_TranslateKeyField> {
  late final _key = TextEditingController(text: widget.initial);
  bool _saved = false;

  @override
  void dispose() {
    _key.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(
          controller: _key,
          obscureText: true,
          autocorrect: false,
          decoration: InputDecoration(labelText: l.translateKey, isDense: true),
          onChanged: (_) => setState(() => _saved = false),
          onSubmitted: (v) async {
            await widget.onSave(v.trim());
            if (mounted) setState(() => _saved = v.trim().isNotEmpty);
          },
        ),
        if (_saved) Padding(padding: const EdgeInsets.only(top: 4), child: Text(l.translateKeySaved, style: Theme.of(context).textTheme.bodySmall)),
      ]),
    );
  }
}
