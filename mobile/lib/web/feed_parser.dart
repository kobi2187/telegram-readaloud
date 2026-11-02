import 'package:xml/xml.dart';

import 'charsets.dart';
import 'html_text.dart';

class FeedItem {
  FeedItem({required this.guid, required this.link, required this.title, required this.date, required this.summary, required this.content});

  /// Stable identity: the guid/id, else the link.
  final String guid;
  final String? link;
  final String title;

  /// Epoch millis, or null when the feed gives no usable date.
  final int? date;

  /// Description or summary, as plain text.
  final String summary;

  /// Full content when the feed carries it (content:encoded, Atom content), as plain text.
  final String content;
}

class Feed {
  Feed(this.title, this.link, this.items);
  final String? title;
  final String? link;
  final List<FeedItem> items;
}

/// Reads RSS 2.0, RSS 1.0 (RDF) and Atom feeds.
class FeedParser {
  FeedParser._();

  static bool looksLikeFeed(List<int> bytes) {
    final head = String.fromCharCodes(bytes.take(1024)).toLowerCase();
    return head.contains('<rss') || head.contains('<feed') || head.contains('<rdf:rdf');
  }

  /// Parses [bytes]; the XML declaration decides the encoding (Hebrew feeds may use windows-1255).
  static Feed parse(List<int> bytes) => parseString(Charsets.decodeDocument(bytes));

  static Feed parseString(String source) {
    XmlDocument doc;
    try {
      doc = XmlDocument.parse(source);
    } on XmlException {
      // Feeds often use HTML entities (&nbsp;) that XML does not know.
      final fixed = source.replaceAllMapped(
        RegExp(r'&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)'),
        (_) => '&amp;',
      );
      doc = XmlDocument.parse(fixed);
    }
    final root = doc.rootElement;
    return root.localName == 'feed' ? _atom(root) : _rss(root);
  }

  static Feed _rss(XmlElement root) {
    final channel = root.findAllElements('channel').firstOrNull;
    final items = root.findAllElements('item').map((item) {
      String? link = _child(item, 'link')?.trim();
      if (link != null && link.isEmpty) link = null;
      if (link == null) {
        final guidEl = _childEl(item, 'guid');
        final g = guidEl?.innerText.trim();
        if (g != null && g.startsWith('http') && guidEl!.getAttribute('isPermaLink') != 'false') link = g;
      }
      final guid = _child(item, 'guid')?.trim().nonEmpty ?? link;
      if (guid == null) return null;
      return FeedItem(
        guid: guid,
        link: link,
        title: HtmlText.plain(_child(item, 'title') ?? ''),
        date: parseDate(_child(item, 'pubDate') ?? _child(item, 'dc:date') ?? _child(item, 'date')),
        summary: HtmlText.plain(_child(item, 'description') ?? ''),
        content: HtmlText.plain(_child(item, 'content:encoded') ?? ''),
      );
    }).whereType<FeedItem>().toList();
    return Feed(
      channel == null ? null : HtmlText.plain(_child(channel, 'title') ?? '').nonEmpty,
      channel == null ? null : _child(channel, 'link'),
      items,
    );
  }

  static Feed _atom(XmlElement feed) {
    String? alternate(XmlElement e) {
      final links = e.childElements.where((c) => c.localName == 'link').toList();
      final alt = links.where((l) => (l.getAttribute('rel') ?? 'alternate') == 'alternate').firstOrNull ?? links.firstOrNull;
      return alt?.getAttribute('href')?.nonEmpty;
    }

    final items = feed.findAllElements('entry').map((entry) {
      final link = alternate(entry);
      final guid = _child(entry, 'id')?.trim().nonEmpty ?? link;
      if (guid == null) return null;
      return FeedItem(
        guid: guid,
        link: link?.trim(),
        title: HtmlText.plain(_child(entry, 'title') ?? ''),
        date: parseDate(_child(entry, 'published') ?? _child(entry, 'updated')),
        summary: HtmlText.plain(_child(entry, 'summary') ?? ''),
        content: HtmlText.plain(_child(entry, 'content') ?? ''),
      );
    }).whereType<FeedItem>().toList();
    return Feed(HtmlText.plain(_child(feed, 'title') ?? '').nonEmpty, alternate(feed), items);
  }

  /// A direct child by qualified name (feeds nest items, so a deep search would be wrong).
  static XmlElement? _childEl(XmlElement parent, String name) =>
      parent.childElements.where((c) => c.name.qualified.toLowerCase() == name.toLowerCase()).firstOrNull;

  static String? _child(XmlElement parent, String name) => _childEl(parent, name)?.innerText;

  static const _months = {'jan': 1, 'feb': 2, 'mar': 3, 'apr': 4, 'may': 5, 'jun': 6, 'jul': 7, 'aug': 8, 'sep': 9, 'oct': 10, 'nov': 11, 'dec': 12};
  static const _zones = {'gmt': 0, 'ut': 0, 'utc': 0, 'z': 0, 'est': -300, 'edt': -240, 'cst': -360, 'cdt': -300, 'mst': -420, 'mdt': -360, 'pst': -480, 'pdt': -420, 'idt': 180, 'ist': 120};
  static final _rfc822 = RegExp(r'^(?:[A-Za-z]{3},?\s+)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,4})?$');

  static int? parseDate(String? raw) {
    final s = raw?.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (s == null || s.isEmpty) return null;
    final iso = DateTime.tryParse(s.contains('T') || s.contains('-') ? s : '');
    if (iso != null) {
      // No zone given: taken as UTC.
      final hasZone = RegExp(r'(Z|[+-]\d{2}:?\d{2})$').hasMatch(s);
      return hasZone || iso.isUtc ? iso.millisecondsSinceEpoch : DateTime.utc(iso.year, iso.month, iso.day, iso.hour, iso.minute, iso.second).millisecondsSinceEpoch;
    }
    final m = _rfc822.firstMatch(s);
    if (m == null) return null;
    final month = _months[m[2]!.toLowerCase()];
    if (month == null) return null;
    var year = int.parse(m[3]!);
    if (year < 100) year += 2000;
    final utc = DateTime.utc(year, month, int.parse(m[1]!), int.parse(m[4]!), int.parse(m[5]!), int.parse(m[6] ?? '0'));
    final zone = m[7];
    var offsetMinutes = 0;
    if (zone != null) {
      if (zone.startsWith('+') || zone.startsWith('-')) {
        final sign = zone.startsWith('-') ? -1 : 1;
        offsetMinutes = sign * (int.parse(zone.substring(1, 3)) * 60 + int.parse(zone.substring(3, 5)));
      } else {
        offsetMinutes = _zones[zone.toLowerCase()] ?? 0;
      }
    }
    return utc.subtract(Duration(minutes: offsetMinutes)).millisecondsSinceEpoch;
  }
}

extension on String {
  String? get nonEmpty => trim().isEmpty ? null : this;
}
