import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/database.dart';
import '../data/models.dart';
import '../data/settings.dart';
import '../text/language_segmenter.dart';
import '../tts/cloud_errors.dart';
import '../web/translator.dart';

/// Keeps the posts of channels read in another language translated: ahead of time during sync
/// (so they also play offline), or on demand when a post comes up before sync reached it.
class Translations {
  Translations({required this.db, required this.translator, required this.settings, DateTime Function()? clock})
      : _now = clock ?? DateTime.now;

  final AppDatabase db;
  final Translator translator;
  final ValueListenable<AppSettings> settings;
  final DateTime Function() _now;
  DateTime _pausedUntil = DateTime(0);

  /// Upcoming posts translated per channel and sync.
  static const perSync = 60;

  bool get paused => _now().isBefore(_pausedUntil);

  /// The translation of [m] into [lang], fetching it if needed; null when it cannot be had now
  /// (offline, or Google is limiting requests), so the original is read instead.
  Future<String?> ensure(Message m, String lang, {Duration timeout = const Duration(seconds: 20)}) async {
    if (m.translationLang == lang && m.translation != null) return m.translation;
    if (m.speech.trim().isEmpty) return null;
    // Already written in that language: nothing to ask Google.
    if (LanguageSegmenter.dominant(m.speech)?.name == lang) {
      await db.setTranslation(m.channel, m.id, lang, m.speech);
      return m.speech;
    }
    if (paused) return null;
    try {
      final t = await translator.translate(m.speech, lang, apiKey: settings.value.translateKey).timeout(timeout);
      final text = t.sourceLang == lang || t.text.isEmpty ? m.speech : t.text;
      await db.setTranslation(m.channel, m.id, lang, text);
      return text;
    } on ThrottledException catch (e) {
      _pausedUntil = _now().add(Duration(seconds: e.retryAfterSeconds ?? 30 * 60));
      debugPrint('Translation paused: $e');
    } on AuthException catch (e) {
      _pausedUntil = _now().add(const Duration(hours: 1));
      debugPrint('Translation key rejected: $e');
    } catch (e) {
      debugPrint('Translation of ${m.channel}/${m.id} failed: $e');
    }
    return null;
  }

  /// Translates the next posts of every channel in [channels] that is read in another language.
  Future<void> translatePending(List<Channel> channels, bool Function() passed) async {
    for (final c in channels) {
      final lang = c.translateTo;
      if (lang == null) continue;
      final pending = await db.untranslated(c.username, lang, c.cursorId, c.startDate, perSync);
      for (final m in pending) {
        if (passed() || paused) return;
        await ensure(m, lang);
      }
    }
  }
}
