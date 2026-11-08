import 'dart:async';

import 'package:sqflite/sqflite.dart';

import 'models.dart';

/// The app's SQLite store, with the same tables as the Android app. Changes are announced on
/// [changes] so screens refresh.
class AppDatabase {
  AppDatabase._(this._db);

  final Database _db;
  final _changes = StreamController<void>.broadcast();

  /// Fires after every write.
  Stream<void> get changes => _changes.stream;

  static Future<AppDatabase> open(String path, {DatabaseFactory? factory}) async {
    final f = factory ?? databaseFactory;
    final db = await f.openDatabase(path, options: OpenDatabaseOptions(version: 2, onCreate: _create, onUpgrade: _upgrade));
    return AppDatabase._(db);
  }

  static Future<void> _create(Database db, int version) async {
    await db.execute('''CREATE TABLE channels (
      username TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, description TEXT, avatarUrl TEXT, avatarPath TEXT,
      startDate INTEGER NOT NULL, sortOrder INTEGER NOT NULL, enabled INTEGER NOT NULL, startId INTEGER,
      fetchedFrom INTEGER NOT NULL, fetchedTo INTEGER NOT NULL, latestId INTEGER NOT NULL, cursorId INTEGER NOT NULL,
      cursorPositionMs INTEGER NOT NULL, lastSyncAt INTEGER NOT NULL, lastError TEXT, addedAt INTEGER NOT NULL,
      kind TEXT NOT NULL DEFAULT 'telegram', url TEXT, translateTo TEXT)''');
    await db.execute('''CREATE TABLE messages (
      channel TEXT NOT NULL, id INTEGER NOT NULL, date INTEGER NOT NULL, text TEXT NOT NULL, speech TEXT NOT NULL,
      media TEXT, edited INTEGER NOT NULL, forwardedFrom TEXT, audioState INTEGER NOT NULL, audioPath TEXT,
      audioVoiceKey TEXT, played INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, link TEXT, guid TEXT,
      translation TEXT, translationLang TEXT, PRIMARY KEY(channel, id))''');
    await db.execute('CREATE INDEX index_messages_channel_date ON messages (channel, date)');
  }

  /// Version 2: translations.
  static Future<void> _upgrade(Database db, int from, int to) async {
    if (from < 2) {
      await db.execute('ALTER TABLE channels ADD COLUMN translateTo TEXT');
      await db.execute('ALTER TABLE messages ADD COLUMN translation TEXT');
      await db.execute('ALTER TABLE messages ADD COLUMN translationLang TEXT');
    }
  }

  void _changed() => _changes.add(null);

  Future<void> close() async {
    await _changes.close();
    await _db.close();
  }

  // --- Channels -------------------------------------------------------------------------

  Future<List<ChannelSummary>> summaries() async {
    final rows = await _db.rawQuery('''
      SELECT c.*,
        (SELECT COUNT(*) FROM messages m WHERE m.channel = c.username AND m.id >= c.cursorId
           AND m.date >= c.startDate AND m.speech != '') AS remaining,
        (SELECT COUNT(*) FROM messages m WHERE m.channel = c.username AND m.id >= c.cursorId
           AND m.date >= c.startDate AND m.speech != '' AND m.audioPath IS NOT NULL) AS audioReady
      FROM channels c ORDER BY c.sortOrder, c.addedAt''');
    return rows.map((r) => ChannelSummary(Channel.fromRow(r), r['remaining'] as int, r['audioReady'] as int)).toList();
  }

  Future<List<Channel>> allChannels() async =>
      (await _db.query('channels', orderBy: 'sortOrder, addedAt')).map(Channel.fromRow).toList();

  Future<List<Channel>> enabledChannels() async =>
      (await _db.query('channels', where: 'enabled = 1', orderBy: 'sortOrder, addedAt')).map(Channel.fromRow).toList();

  Future<Channel?> channel(String username) async {
    final rows = await _db.query('channels', where: 'username = ?', whereArgs: [username]);
    return rows.isEmpty ? null : Channel.fromRow(rows.first);
  }

  Future<int> nextSortOrder() async =>
      (await _db.rawQuery('SELECT COALESCE(MAX(sortOrder), -1) + 1 AS n FROM channels')).first['n'] as int;

  Future<int> firstSortOrder() async =>
      (await _db.rawQuery('SELECT COALESCE(MIN(sortOrder), 0) - 1 AS n FROM channels')).first['n'] as int;

  /// Inserts unless it exists; true when inserted.
  Future<bool> insertChannel(Channel c) async {
    final n = await _db.insert('channels', c.toRow(), conflictAlgorithm: ConflictAlgorithm.ignore);
    _changed();
    return n > 0;
  }

  Future<void> updateChannel(Channel c) async {
    await _db.update('channels', c.toRow(), where: 'username = ?', whereArgs: [c.username]);
    _changed();
  }

  Future<void> deleteChannel(String username) async {
    await _db.delete('messages', where: 'channel = ?', whereArgs: [username]);
    await _db.delete('channels', where: 'username = ?', whereArgs: [username]);
    _changed();
  }

  Future<void> setCursor(String username, int cursorId, int positionMs) async {
    await _db.update('channels', {'cursorId': cursorId, 'cursorPositionMs': positionMs}, where: 'username = ?', whereArgs: [username]);
    _changed();
  }

  Future<void> setSyncResult(String username, String? error, int at) async {
    await _db.update('channels', {'lastError': error, 'lastSyncAt': at}, where: 'username = ?', whereArgs: [username]);
    _changed();
  }

  Future<void> setSortOrder(String username, int order) async {
    await _db.update('channels', {'sortOrder': order}, where: 'username = ?', whereArgs: [username]);
    _changed();
  }

  // --- Messages -------------------------------------------------------------------------

  Future<List<Message>> channelMessages(String channel, int startDate) async =>
      (await _db.query('messages', where: 'channel = ? AND date >= ?', whereArgs: [channel, startDate], orderBy: 'id'))
          .map(Message.fromRow)
          .toList();

  Future<List<Message>> queue(String channel, int fromId, int startDate, int limit) async => (await _db.query(
        'messages',
        where: "channel = ? AND id >= ? AND date >= ? AND speech != ''",
        whereArgs: [channel, fromId, startDate],
        orderBy: 'id',
        limit: limit,
      ))
          .map(Message.fromRow)
          .toList();

  Future<int> countFrom(String channel, int fromId, int startDate) async => (await _db.rawQuery(
        "SELECT COUNT(*) AS n FROM messages WHERE channel = ? AND id >= ? AND date >= ? AND speech != ''",
        [channel, fromId, startDate],
      ))
          .first['n'] as int;

  /// The newest posts of [channel] that have something to read (for spotting signatures).
  Future<List<Message>> recentWithSpeech(String channel, int limit) async => (await _db.query(
        'messages',
        where: "channel = ? AND speech != ''",
        whereArgs: [channel],
        orderBy: 'id DESC',
        limit: limit,
      ))
          .map(Message.fromRow)
          .toList();

  /// Posts of [channel] from [fromId] that are not yet translated into [lang], oldest first.
  Future<List<Message>> untranslated(String channel, String lang, int fromId, int startDate, int limit) async => (await _db.query(
        'messages',
        where: "channel = ? AND id >= ? AND date >= ? AND speech != '' AND (translationLang IS NULL OR translationLang != ?)",
        whereArgs: [channel, fromId, startDate, lang],
        orderBy: 'id',
        limit: limit,
      ))
          .map(Message.fromRow)
          .toList();

  /// Stores a post's translation; audio voiced from the original is dropped.
  Future<void> setTranslation(String channel, int id, String lang, String text) async {
    await _db.update(
      'messages',
      {'translation': text, 'translationLang': lang, 'audioState': 0, 'audioPath': null, 'audioVoiceKey': null},
      where: 'channel = ? AND id = ?',
      whereArgs: [channel, id],
    );
    _changed();
  }

  /// Sets the language [username] is read in; its audio is voiced again.
  Future<void> setTranslateTo(String username, String? lang) async {
    await _db.update('channels', {'translateTo': lang}, where: 'username = ?', whereArgs: [username]);
    await _db.update('messages', {'audioState': 0, 'audioPath': null, 'audioVoiceKey': null}, where: 'channel = ?', whereArgs: [username]);
    _changed();
  }

  Future<Message?> message(String channel, int id) async {
    final rows = await _db.query('messages', where: 'channel = ? AND id = ?', whereArgs: [channel, id]);
    return rows.isEmpty ? null : Message.fromRow(rows.first);
  }

  Future<int> maxId(String channel) async =>
      (await _db.rawQuery('SELECT COALESCE(MAX(id), 0) AS n FROM messages WHERE channel = ?', [channel])).first['n'] as int;

  Future<void> insertMessages(List<Message> messages) async {
    if (messages.isEmpty) return;
    final batch = _db.batch();
    for (final m in messages) {
      batch.insert('messages', m.toRow(), conflictAlgorithm: ConflictAlgorithm.ignore);
    }
    await batch.commit(noResult: true);
    _changed();
  }

  /// Stores posts from the web; edited posts get their new text (and lose stale audio).
  Future<void> upsertFromWeb(List<Message> incoming) async {
    if (incoming.isEmpty) return;
    final channel = incoming.first.channel;
    await _db.transaction((txn) async {
      final ids = incoming.map((m) => m.id).toList();
      final existing = {
        for (final r in await txn.query('messages', where: 'channel = ? AND id IN (${List.filled(ids.length, '?').join(',')})', whereArgs: [channel, ...ids]))
          r['id'] as int: Message.fromRow(r),
      };
      for (final m in incoming) {
        final old = existing[m.id];
        if (old == null) {
          await txn.insert('messages', m.toRow(), conflictAlgorithm: ConflictAlgorithm.ignore);
        } else if (old.text != m.text || old.speech != m.speech) {
          await txn.update(
            'messages',
            {
              'text': m.text, 'speech': m.speech, 'media': m.media, 'edited': 1, 'audioState': 0, 'audioPath': null, 'audioVoiceKey': null,
              'translation': null, 'translationLang': null,
            },
            where: 'channel = ? AND id = ?',
            whereArgs: [channel, m.id],
          );
        }
      }
    });
    _changed();
  }

  Future<List<String>> guids(String channel) async =>
      (await _db.query('messages', columns: ['guid'], where: 'channel = ? AND guid IS NOT NULL', whereArgs: [channel]))
          .map((r) => r['guid'] as String)
          .toList();

  Future<List<Message>> pendingLinks(String channel) async =>
      (await _db.query('messages', where: "channel = ? AND speech = '' AND link IS NOT NULL", whereArgs: [channel], orderBy: 'id'))
          .map(Message.fromRow)
          .toList();

  Future<void> setArticle(String channel, int id, String text, String speech) async {
    await _db.update(
      'messages',
      {'text': text, 'speech': speech, 'audioState': 0, 'audioPath': null, 'audioVoiceKey': null, 'translation': null, 'translationLang': null},
      where: 'channel = ? AND id = ?',
      whereArgs: [channel, id],
    );
    _changed();
  }

  Future<void> setAudio(String channel, int id, int state, String? path, String? voiceKey) async {
    await _db.update('messages', {'audioState': state, 'audioPath': path, 'audioVoiceKey': voiceKey}, where: 'channel = ? AND id = ?', whereArgs: [channel, id]);
    _changed();
  }

  Future<void> markPlayed(String channel, int id) async {
    await _db.update('messages', {'played': 1}, where: 'channel = ? AND id = ?', whereArgs: [channel, id]);
    _changed();
  }

  Future<void> markPlayedBefore(String channel, int beforeId) async {
    await _db.update('messages', {'played': 1}, where: 'channel = ? AND id < ?', whereArgs: [channel, beforeId]);
    _changed();
  }

  Future<void> markAllUnplayed(String channel) async {
    await _db.update('messages', {'played': 0}, where: 'channel = ?', whereArgs: [channel]);
    _changed();
  }

  /// Played posts with audio before [beforeId], newest first, skipping the newest [keep].
  Future<List<Message>> playedWithAudio(String channel, int beforeId, int keep) async => (await _db.rawQuery(
        'SELECT * FROM messages WHERE audioPath IS NOT NULL AND played = 1 AND channel = ? AND id < ? ORDER BY id DESC LIMIT -1 OFFSET ?',
        [channel, beforeId, keep],
      ))
          .map(Message.fromRow)
          .toList();

  Future<List<String>> allAudioPaths() async =>
      (await _db.rawQuery('SELECT audioPath FROM messages WHERE audioPath IS NOT NULL')).map((r) => r['audioPath'] as String).toList();

  Future<void> clearAllAudio() async {
    await _db.rawUpdate('UPDATE messages SET audioState = 0, audioPath = NULL, audioVoiceKey = NULL');
    _changed();
  }
}
