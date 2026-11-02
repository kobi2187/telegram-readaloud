/// Parses what the user typed or shared into a Telegram channel username.
class ChannelInput {
  ChannelInput._();

  static final _username = RegExp(r'^[A-Za-z][A-Za-z0-9_]{3,31}$');
  static final _link = RegExp(
    r'(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me|telegram\.dog)/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})',
    caseSensitive: false,
  );
  static const _reserved = {'joinchat', 'addstickers', 'share', 'proxy', 'socks', 'addlist', 'boost', 'iv'};

  /// Accepts "@name", "name", "t.me/name", "https://t.me/s/name/123" or text containing such a
  /// link (e.g. shared from Telegram) and returns the lowercase username, or null.
  static String? parse(String input) {
    final trimmed = input.trim();
    final m = _link.firstMatch(trimmed);
    if (m != null && !_reserved.contains(m[1]!.toLowerCase())) return m[1]!.toLowerCase();
    final bare = trimmed.startsWith('@') ? trimmed.substring(1).trim() : trimmed;
    return _username.hasMatch(bare) ? bare.toLowerCase() : null;
  }
}
