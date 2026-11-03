import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/ui/share_receiver.dart';

void main() {
  test('a shared web link is saved; Telegram links and plain names go to the add dialog', () {
    expect(ShareReceiver.classify('Read this https://www.ynet.co.il/news/article/abc123 via ynet').save, 'https://www.ynet.co.il/news/article/abc123');
    expect(ShareReceiver.classify('https://t.me/kann_news/1234').add, 'https://t.me/kann_news/1234');
    expect(ShareReceiver.classify('@kann_news').add, '@kann_news');
  });

  test('readaloud links from the iPhone Shortcut carry the shared text', () {
    expect(ShareReceiver.textOf(Uri.parse('readaloud://share?text=${Uri.encodeQueryComponent('https://example.com/a?b=1&c=2')}')), 'https://example.com/a?b=1&c=2');
    expect(ShareReceiver.textOf(Uri.parse('readaloud://share?url=https%3A%2F%2Ft.me%2Fabc')), 'https://t.me/abc');
    expect(ShareReceiver.textOf(Uri.parse('readaloud://share')), isNull);
    expect(ShareReceiver.textOf(Uri.parse('https://example.com/?text=x')), isNull);
  });
}
