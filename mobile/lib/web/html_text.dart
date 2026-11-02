import 'package:html/dom.dart';
import 'package:html/parser.dart' as html;

/// Turns HTML into paragraphs of plain text.
class HtmlText {
  HtmlText._();

  static const _blockTags = {
    'p', 'div', 'section', 'article', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'pre', 'tr', 'dd',
    'dt', 'figcaption', 'header', 'footer', 'main', 'ul', 'ol', 'table',
  };
  static const _skipTags = {'script', 'style', 'noscript', 'iframe', 'svg', 'button', 'form'};
  static final _spaces = RegExp('[ \t ]+');

  static List<String> blocks(Node root) {
    final out = <String>[];
    final current = StringBuffer();
    void flush() {
      final t = current.toString().replaceAll(_spaces, ' ').replaceAll(RegExp(r'\s*\n\s*'), ' ').trim();
      if (t.isNotEmpty) out.add(t);
      current.clear();
    }

    void walk(Node node) {
      if (node is Text) {
        current.write(node.text);
      } else if (node is Element) {
        final tag = node.localName?.toLowerCase() ?? '';
        if (_skipTags.contains(tag)) return;
        if (tag == 'br') {
          flush();
          return;
        }
        final block = _blockTags.contains(tag);
        if (block) flush();
        node.nodes.forEach(walk);
        if (block) flush();
      } else if (node is Document || node is DocumentFragment) {
        node.nodes.forEach(walk);
      }
    }

    walk(root);
    flush();
    return out;
  }

  /// HTML (escaped or not) to plain text with paragraph breaks.
  static String plain(String htmlOrText) {
    final s = htmlOrText.trim();
    if (s.isEmpty) return '';
    if (!s.contains('<') && !s.contains('&')) return s;
    return blocks(html.parseFragment(s)).join('\n\n');
  }
}
