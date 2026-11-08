/// Channel signatures: a closing paragraph (or line) that repeats at the end of several posts,
/// such as "Join our channel" or "Kan News – reliable updates", is not read aloud.
abstract final class Footers {
  /// Posts that must end with the same text for it to count as a signature.
  static const minRepeats = 3;

  static String _norm(String s) => s.replaceAll(RegExp(r'\s+'), ' ').trim().toLowerCase();

  static final _paragraphBreak = RegExp(r'\n\s*\n');

  /// The closing paragraph and the closing line of [speech] (normalized), when the post has more
  /// before them.
  static Set<String> _endings(String speech) {
    final text = speech.trim();
    final out = <String>{};
    final paragraphs = text.split(_paragraphBreak);
    if (paragraphs.length > 1) out.add(_norm(paragraphs.last));
    final lines = text.split('\n').where((l) => l.trim().isNotEmpty).toList();
    if (lines.length > 1) out.add(_norm(lines.last));
    out.removeWhere((e) => e.length < 3);
    return out;
  }

  /// The signatures among a channel's posts: endings shared by at least [minRepeats] posts. A
  /// signature above another one ("Join us" over "Follow @news") is found once the lower one is
  /// set aside.
  static Set<String> detect(Iterable<String> speeches) {
    final found = <String>{};
    var texts = speeches.toList();
    for (var round = 0; round < 4; round++) {
      final counts = <String, int>{};
      for (final s in texts) {
        for (final e in _endings(s)) {
          counts[e] = (counts[e] ?? 0) + 1;
        }
      }
      final fresh = {for (final e in counts.entries) if (e.value >= minRepeats && !found.contains(e.key)) e.key};
      if (fresh.isEmpty) break;
      found.addAll(fresh);
      texts = [for (final s in texts) strip(s, found)];
    }
    return found;
  }

  /// [speech] without the signatures it ends with (several may follow each other); a post that is
  /// nothing but a signature is left as it is.
  static String strip(String speech, Set<String> footers) {
    if (footers.isEmpty) return speech;
    var text = speech.trim();
    while (true) {
      final paragraphs = text.split(_paragraphBreak);
      if (paragraphs.length > 1 && footers.contains(_norm(paragraphs.last))) {
        text = paragraphs.sublist(0, paragraphs.length - 1).join('\n\n').trim();
        continue;
      }
      final lines = text.split('\n');
      while (lines.isNotEmpty && lines.last.trim().isEmpty) {
        lines.removeLast();
      }
      if (lines.where((l) => l.trim().isNotEmpty).length > 1 && footers.contains(_norm(lines.last))) {
        text = lines.sublist(0, lines.length - 1).join('\n').trim();
        continue;
      }
      return text;
    }
  }
}
