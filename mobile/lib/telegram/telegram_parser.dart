import 'package:html/dom.dart';
import 'package:html/parser.dart' as html;

import '../text/speech_text.dart';

class TgMessage {
  TgMessage({
    required this.id,
    required this.dateMillis,
    required this.text,
    required this.speech,
    required this.media,
    required this.isService,
    required this.edited,
    required this.forwardedFrom,
  });

  final int id;
  final int dateMillis;

  /// Readable text for the screen (keeps links and emoji).
  final String text;

  /// Text prepared for speech; empty when there is nothing worth reading.
  final String speech;

  /// Comma-separated media kinds such as "photo,video"; null for text-only posts.
  final String? media;
  final bool isService;
  final bool edited;
  final String? forwardedFrom;
}

class TgPage {
  TgPage({
    required this.username,
    required this.title,
    required this.description,
    required this.avatarUrl,
    required this.messages,
    required this.prevBefore,
    required this.nextAfter,
  });

  final String username;
  final String? title;
  final String? description;
  final String? avatarUrl;

  /// Messages in ascending id order, including service messages (needed for paging).
  final List<TgMessage> messages;

  /// Value for `?before=` to load older posts, when there are any.
  final int? prevBefore;

  /// Value for `?after=` to load newer posts, when there are any.
  final int? nextAfter;

  int? get minId => messages.isEmpty ? null : messages.map((m) => m.id).reduce((a, b) => a < b ? a : b);
  int? get maxId => messages.isEmpty ? null : messages.map((m) => m.id).reduce((a, b) => a > b ? a : b);
}

/// Parses the public web preview at https://t.me/s/<channel>.
class TelegramParser {
  TelegramParser._();

  static final _before = RegExp(r'[?&]before=(\d+)');
  static final _after = RegExp(r'[?&]after=(\d+)');

  static TgPage parse(String source, String username) {
    final doc = html.parse(source);
    String? nonBlank(String? s) => (s == null || s.trim().isEmpty) ? null : s.trim();

    final title = nonBlank(doc.querySelector('.tgme_channel_info_header_title')?.text) ??
        nonBlank(doc.querySelector('meta[property="og:title"]')?.attributes['content']);
    final descEl = doc.querySelector('.tgme_channel_info_description');
    final description = descEl == null ? null : nonBlank(htmlToText(descEl));
    var avatar = nonBlank(doc.querySelector('.tgme_page_photo_image img')?.attributes['src']);
    if (avatar != null && avatar.startsWith('//')) avatar = 'https:$avatar';
    avatar ??= () {
      final og = doc.querySelector('meta[property="og:image"]')?.attributes['content'];
      return og != null && og.startsWith('http') ? og : null;
    }();

    final seen = <int>{};
    final messages = doc
        .querySelectorAll('div.tgme_widget_message')
        .where((e) => e.attributes.containsKey('data-post'))
        .map(_parseMessage)
        .whereType<TgMessage>()
        .where((m) => seen.add(m.id))
        .toList()
      ..sort((a, b) => a.id.compareTo(b.id));

    int? pageParam(String rel, RegExp re) {
      final href = doc.querySelector('link[rel="$rel"]')?.attributes['href'];
      final m = href == null ? null : re.firstMatch(href);
      return m == null ? null : int.tryParse(m[1]!);
    }

    return TgPage(
      username: username,
      title: title,
      description: description,
      avatarUrl: avatar,
      messages: messages,
      prevBefore: pageParam('prev', _before),
      nextAfter: pageParam('next', _after),
    );
  }

  static bool _insideReply(Element e) {
    for (Element? p = e.parent; p != null; p = p.parent) {
      if (p.classes.contains('tgme_widget_message_reply')) return true;
    }
    return false;
  }

  static TgMessage? _parseMessage(Element el) {
    final post = el.attributes['data-post'] ?? '';
    final id = int.tryParse(post.substring(post.lastIndexOf('/') + 1));
    if (id == null) return null;
    final datetime = el.querySelector('.tgme_widget_message_date time')?.attributes['datetime'] ??
        el.querySelector('time')?.attributes['datetime'];
    if (datetime == null) return null;
    final date = DateTime.tryParse(datetime);
    if (date == null) return null;
    final isService = el.classes.contains('service_message');

    final textEl = el.querySelectorAll('.js-message_text').where((e) => !_insideReply(e)).firstOrNull;
    var text = textEl == null ? '' : htmlToText(textEl);
    var speech = SpeechText.clean(text);

    final poll = el.querySelector('.tgme_widget_message_poll');
    if (poll != null) {
      final question = poll.querySelector('.tgme_widget_message_poll_question')?.text.trim() ?? '';
      final options = poll.querySelectorAll('.tgme_widget_message_poll_option_text').map((e) => e.text.trim()).toList();
      final pollText = StringBuffer(question);
      for (final o in options) {
        pollText.write('\n• $o');
      }
      text = text.trim().isEmpty ? pollText.toString() : '$text\n\n$pollText';
      speech = SpeechText.clean([speech, question, options.join('\n')].where((s) => s.trim().isNotEmpty).join('\n'));
    }

    if (speech.trim().isEmpty) {
      // A post that is only a link: read the link preview's title instead.
      final previewTitle = el.querySelector('.tgme_widget_message_link_preview .link_preview_title')?.text;
      if (previewTitle != null && previewTitle.trim().isNotEmpty) speech = SpeechText.clean(previewTitle);
    }

    bool has(String selector) => el.querySelector(selector) != null;
    final media = <String>[
      if (has('.tgme_widget_message_photo_wrap')) 'photo',
      if (has('.tgme_widget_message_video_player') || has('.tgme_widget_message_roundvideo_player')) 'video',
      if (has('.tgme_widget_message_voice')) 'voice',
      if (has('.tgme_widget_message_document')) 'file',
      if (has('.tgme_widget_message_sticker_wrap') || has('.tgme_widget_message_sticker')) 'sticker',
      if (has('.tgme_widget_message_location_wrap')) 'location',
      if (poll != null) 'poll',
    ].toSet().join(',');

    final meta = el.querySelector('.tgme_widget_message_meta');
    final ownText = meta?.nodes.whereType<Text>().map((t) => t.text).join() ?? '';
    final forwarded = el.querySelector('.tgme_widget_message_forwarded_from_name')?.text.trim();

    return TgMessage(
      id: id,
      dateMillis: date.millisecondsSinceEpoch,
      text: text,
      speech: isService ? '' : speech,
      media: media.isEmpty ? null : media,
      isService: isService,
      edited: ownText.contains('edited'),
      forwardedFrom: (forwarded == null || forwarded.isEmpty) ? null : forwarded,
    );
  }

  /// Converts message HTML to plain text, keeping line breaks and emoji.
  static String htmlToText(Element root) {
    final sb = StringBuffer();
    _append(root, sb);
    return sb
        .toString()
        .replaceAll(' ', ' ')
        .split('\n')
        .map((l) => l.trimRight())
        .join('\n')
        .replaceAll(RegExp(r'\n{3,}'), '\n\n')
        .trim();
  }

  static void _append(Node node, StringBuffer sb) {
    bool endsWithNewline() => sb.isNotEmpty && sb.toString().endsWith('\n');
    if (node is Text) {
      sb.write(node.text);
    } else if (node is Element) {
      switch (node.localName?.toLowerCase()) {
        case 'br':
          sb.write('\n');
        case 'blockquote' || 'pre':
          if (sb.isNotEmpty && !endsWithNewline()) sb.write('\n');
          for (final c in node.nodes) {
            _append(c, sb);
          }
          if (!endsWithNewline()) sb.write('\n');
        case 'div':
          if (sb.isNotEmpty && !endsWithNewline()) sb.write('\n');
          for (final c in node.nodes) {
            _append(c, sb);
          }
        case 'script' || 'style' || 'svg':
          break;
        default:
          for (final c in node.nodes) {
            _append(c, sb);
          }
      }
    }
  }
}
