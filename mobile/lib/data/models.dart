/// Where a channel's posts come from.
class ChannelKind {
  ChannelKind._();
  static const telegram = 'telegram';

  /// A website's RSS/Atom feed; each post is an article.
  static const feed = 'feed';

  /// Links shared to the app to listen to later.
  static const saved = 'saved';
  static const savedId = 'saved';
}

class AudioState {
  AudioState._();
  static const none = 0;

  /// Online-voice audio for the voices in [Message.audioVoiceKey].
  static const neural = 1;

  /// Rendered on the phone.
  static const local = 2;

  /// The online voice cannot voice this text; use the phone.
  static const neuralFailed = 3;
}

class Channel {
  const Channel({
    required this.username,
    required this.title,
    this.description,
    this.avatarUrl,
    this.avatarPath,
    required this.startDate,
    required this.sortOrder,
    this.enabled = true,
    this.startId,
    this.fetchedFrom = 0,
    this.fetchedTo = 0,
    this.latestId = 0,
    this.cursorId = 0,
    this.cursorPositionMs = 0,
    this.lastSyncAt = 0,
    this.lastError,
    required this.addedAt,
    this.kind = ChannelKind.telegram,
    this.url,
    this.translateTo,
  });

  final String username;
  final String title;
  final String? description;
  final String? avatarUrl;
  final String? avatarPath;

  /// Read posts published at or after this instant (epoch millis).
  final int startDate;
  final int sortOrder;
  final bool enabled;

  /// First post id at or after [startDate]; null when it must be (re)located.
  final int? startId;

  /// Contiguous range of post ids already downloaded: [fetchedFrom, fetchedTo]. 0 = none.
  final int fetchedFrom;
  final int fetchedTo;

  /// Newest post id seen on the channel.
  final int latestId;

  /// Next post to play; posts with a smaller id count as heard. 0 = from the start date.
  final int cursorId;

  /// Resume offset inside the post at [cursorId].
  final int cursorPositionMs;
  final int lastSyncAt;
  final String? lastError;
  final int addedAt;
  final String kind;

  /// The feed URL of a website channel.
  final String? url;

  /// Language (he, en, ru, ar) the channel's posts are translated into and read in; null: as written.
  final String? translateTo;

  bool get isTelegram => kind == ChannelKind.telegram;

  static const _absent = Object();

  Channel copyWith({
    String? title,
    Object? description = _absent,
    Object? avatarUrl = _absent,
    Object? avatarPath = _absent,
    int? startDate,
    int? sortOrder,
    bool? enabled,
    Object? startId = _absent,
    int? fetchedFrom,
    int? fetchedTo,
    int? latestId,
    int? cursorId,
    int? cursorPositionMs,
    int? lastSyncAt,
    Object? lastError = _absent,
    Object? translateTo = _absent,
  }) =>
      Channel(
        username: username,
        title: title ?? this.title,
        description: identical(description, _absent) ? this.description : description as String?,
        avatarUrl: identical(avatarUrl, _absent) ? this.avatarUrl : avatarUrl as String?,
        avatarPath: identical(avatarPath, _absent) ? this.avatarPath : avatarPath as String?,
        startDate: startDate ?? this.startDate,
        sortOrder: sortOrder ?? this.sortOrder,
        enabled: enabled ?? this.enabled,
        startId: identical(startId, _absent) ? this.startId : startId as int?,
        fetchedFrom: fetchedFrom ?? this.fetchedFrom,
        fetchedTo: fetchedTo ?? this.fetchedTo,
        latestId: latestId ?? this.latestId,
        cursorId: cursorId ?? this.cursorId,
        cursorPositionMs: cursorPositionMs ?? this.cursorPositionMs,
        lastSyncAt: lastSyncAt ?? this.lastSyncAt,
        lastError: identical(lastError, _absent) ? this.lastError : lastError as String?,
        addedAt: addedAt,
        kind: kind,
        url: url,
        translateTo: identical(translateTo, _absent) ? this.translateTo : translateTo as String?,
      );

  Map<String, Object?> toRow() => {
        'username': username,
        'title': title,
        'description': description,
        'avatarUrl': avatarUrl,
        'avatarPath': avatarPath,
        'startDate': startDate,
        'sortOrder': sortOrder,
        'enabled': enabled ? 1 : 0,
        'startId': startId,
        'fetchedFrom': fetchedFrom,
        'fetchedTo': fetchedTo,
        'latestId': latestId,
        'cursorId': cursorId,
        'cursorPositionMs': cursorPositionMs,
        'lastSyncAt': lastSyncAt,
        'lastError': lastError,
        'addedAt': addedAt,
        'kind': kind,
        'url': url,
        'translateTo': translateTo,
      };

  static Channel fromRow(Map<String, Object?> r) => Channel(
        username: r['username'] as String,
        title: r['title'] as String,
        description: r['description'] as String?,
        avatarUrl: r['avatarUrl'] as String?,
        avatarPath: r['avatarPath'] as String?,
        startDate: r['startDate'] as int,
        sortOrder: r['sortOrder'] as int,
        enabled: (r['enabled'] as int) != 0,
        startId: r['startId'] as int?,
        fetchedFrom: r['fetchedFrom'] as int,
        fetchedTo: r['fetchedTo'] as int,
        latestId: r['latestId'] as int,
        cursorId: r['cursorId'] as int,
        cursorPositionMs: r['cursorPositionMs'] as int,
        lastSyncAt: r['lastSyncAt'] as int,
        lastError: r['lastError'] as String?,
        addedAt: r['addedAt'] as int,
        kind: r['kind'] as String,
        url: r['url'] as String?,
        translateTo: r['translateTo'] as String?,
      );
}

class Message {
  const Message({
    required this.channel,
    required this.id,
    required this.date,
    required this.text,
    required this.speech,
    this.media,
    this.edited = false,
    this.forwardedFrom,
    this.audioState = AudioState.none,
    this.audioPath,
    this.audioVoiceKey,
    this.played = false,
    this.fetchedAt = 0,
    this.link,
    this.guid,
    this.translation,
    this.translationLang,
  });

  final String channel;
  final int id;
  final int date;
  final String text;
  final String speech;
  final String? media;
  final bool edited;
  final String? forwardedFrom;
  final int audioState;
  final String? audioPath;
  final String? audioVoiceKey;
  final bool played;
  final int fetchedAt;

  /// The article's web address (website and saved posts).
  final String? link;

  /// The feed item's identity, so an article is stored once.
  final String? guid;

  /// [speech] translated into [translationLang] (or [speech] itself when already in it).
  final String? translation;
  final String? translationLang;

  /// What to read when the channel is translated into [lang]: the translation, if there is one.
  String speechIn(String? lang) => lang != null && translationLang == lang && translation != null ? translation! : speech;

  Map<String, Object?> toRow() => {
        'channel': channel,
        'id': id,
        'date': date,
        'text': text,
        'speech': speech,
        'media': media,
        'edited': edited ? 1 : 0,
        'forwardedFrom': forwardedFrom,
        'audioState': audioState,
        'audioPath': audioPath,
        'audioVoiceKey': audioVoiceKey,
        'played': played ? 1 : 0,
        'fetchedAt': fetchedAt == 0 ? DateTime.now().millisecondsSinceEpoch : fetchedAt,
        'link': link,
        'guid': guid,
        'translation': translation,
        'translationLang': translationLang,
      };

  static Message fromRow(Map<String, Object?> r) => Message(
        channel: r['channel'] as String,
        id: r['id'] as int,
        date: r['date'] as int,
        text: r['text'] as String,
        speech: r['speech'] as String,
        media: r['media'] as String?,
        edited: (r['edited'] as int) != 0,
        forwardedFrom: r['forwardedFrom'] as String?,
        audioState: r['audioState'] as int,
        audioPath: r['audioPath'] as String?,
        audioVoiceKey: r['audioVoiceKey'] as String?,
        played: (r['played'] as int) != 0,
        fetchedAt: r['fetchedAt'] as int,
        link: r['link'] as String?,
        guid: r['guid'] as String?,
        translation: r['translation'] as String?,
        translationLang: r['translationLang'] as String?,
      );
}

class ChannelSummary {
  const ChannelSummary(this.channel, this.remaining, this.audioReady);
  final Channel channel;

  /// Posts from the cursor onwards that have something to read.
  final int remaining;

  /// Of those, how many have audio ready for offline playback.
  final int audioReady;
}
