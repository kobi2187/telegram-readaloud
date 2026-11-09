import 'package:flutter_test/flutter_test.dart';
import 'package:tg_read_aloud/text/footers.dart';

void main() {
  const signature = 'להצטרפות לערוץ כאן חדשות.';
  final posts = [
    'ראש הממשלה נפגש היום עם השר.\n\n$signature',
    'גשם בצפון.\n\n$signature',
    'הבורסה עלתה.\nפרטים בהמשך.\n$signature',
    'הודעה בלי חתימה.',
    'שתי פסקאות.\n\nזו הפסקה האחרונה.',
  ];

  test('a closing paragraph or line repeated in three posts is a signature', () {
    final footers = Footers.detect(posts);
    expect(footers, {'להצטרפות לערוץ כאן חדשות.'});
    expect(Footers.strip(posts[0], footers), 'ראש הממשלה נפגש היום עם השר.');
    expect(Footers.strip(posts[2], footers), 'הבורסה עלתה.\nפרטים בהמשך.');
    expect(Footers.strip(posts[4], footers), posts[4], reason: 'an ending seen once is kept');
  });

  test('two posts are not enough', () {
    expect(Footers.detect(posts.sublist(0, 2)), isEmpty);
  });

  test('spacing and case do not matter; stacked signatures all go', () {
    final footers = Footers.detect([
      'One.\n\nJoin   us\n\nFollow @news',
      'Two.\n\nJOIN US\n\nFollow @news',
      'Three.\n\njoin us\n\nFollow  @news',
    ]);
    expect(footers, containsAll(['join us', 'follow @news']));
    expect(Footers.strip('Four.\n\nJoin us\n\nFollow @news', footers), 'Four.');
  });

  test('a post that is only the signature is still read', () {
    final footers = {'join us'};
    expect(Footers.strip('Join us', footers), 'Join us');
  });
}
