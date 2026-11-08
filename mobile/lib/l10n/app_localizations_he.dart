// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Hebrew (`he`).
class LHe extends L {
  LHe([String locale = 'he']) : super(locale);

  @override
  String get appName => 'הקראת טלגרם';

  @override
  String get actionAdd => 'הוספה';

  @override
  String get actionBack => 'חזרה';

  @override
  String get actionBack10 => '10 שניות אחורה';

  @override
  String get actionForward10 => '10 שניות קדימה';

  @override
  String get actionMore => 'עוד';

  @override
  String get actionNextChannel => 'הערוץ הבא';

  @override
  String get actionPrevChannel => 'הערוץ הקודם';

  @override
  String get actionNextChannelShort => 'ערוץ הבא';

  @override
  String get actionPrevChannelShort => 'ערוץ קודם';

  @override
  String get actionNextMessage => 'ההודעה הבאה';

  @override
  String get actionPrevMessage => 'ההודעה הקודמת';

  @override
  String get actionPause => 'השהיה';

  @override
  String get actionPlay => 'הקראה';

  @override
  String get actionSync => 'סנכרון עכשיו';

  @override
  String get emptyChannels =>
      'הוסיפו ערוץ טלגרם ציבורי כדי להתחיל להאזין.\n\nהדביקו קישור (t.me/…) או ‎@שם, או שתפו קישור מטלגרם לאפליקציה.';

  @override
  String get playerIdle => 'מוכן להקראה';

  @override
  String channelFrom(String p1) {
    return 'החל מ־$p1';
  }

  @override
  String get channelCaughtUp => 'אין הודעות חדשות';

  @override
  String channelOfflineReady(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count מוכנות לא־מקוון',
      two: '$count מוכנות לא־מקוון',
      one: 'אחת מוכנה לא־מקוון',
    );
    return '$_temp0';
  }

  @override
  String get channelPaused => 'מושהה — מדולג בסנכרון ובמעבר ערוצים';

  @override
  String get channelNoPosts =>
      'עדיין לא הורדו הודעות. הן יופיעו אחרי הסנכרון הבא.';

  @override
  String channelRemaining(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count הודעות להאזנה',
      two: '$count הודעות להאזנה',
      one: 'הודעה אחת להאזנה',
    );
    return '$_temp0';
  }

  @override
  String get warningNoHebrewVoice =>
      'אין קול עברי זמין, ולכן הודעות בעברית ידולגו. הקישו כדי להוסיף מפתח Gemini חינמי או להוריד את הקול העברי הלא־מקוון.';

  @override
  String get tipBetterVoices =>
      'לעברית שנשמעת טבעית, הוסיפו מפתח Gemini חינמי או הורידו את הקול העברי הלא־מקוון. הקישו לפתיחת ההגדרות.';

  @override
  String offlineVoiceNotification(String p1) {
    return 'מוריד את הקול $p1';
  }

  @override
  String get errorNoPreview =>
      'לערוץ הזה אין תצוגה ציבורית באתר, ולכן אי אפשר לקרוא אותו בלי התחברות.';

  @override
  String get errorRateLimited => 'טלגרם ביקשה להאט; ננסה שוב מאוחר יותר.';

  @override
  String get errorSyncFailed => 'הסנכרון האחרון נכשל; ננסה שוב כשתהיה רשת.';

  @override
  String get menuPosts => 'הצגת ההודעות';

  @override
  String get menuStartDate => 'תאריך התחלה';

  @override
  String get menuMarkHeard => 'סימון הכל כנשמע';

  @override
  String get menuRestart => 'התחלה מחדש מתאריך ההתחלה';

  @override
  String get menuMoveUp => 'הזזה למעלה';

  @override
  String get menuMoveDown => 'הזזה למטה';

  @override
  String get menuPauseChannel => 'השהיית הערוץ';

  @override
  String get menuResumeChannel => 'חידוש הערוץ';

  @override
  String get menuRemove => 'הסרה';

  @override
  String removeTitle(String p1) {
    return 'להסיר את $p1?';
  }

  @override
  String get removeBody => 'ההודעות וההקלטות שהורדו מהערוץ יימחקו.';

  @override
  String get addChannelTitle => 'הוספת ערוץ';

  @override
  String get addChannelHint => 'קישור טלגרם או ‎@שם, או אתר';

  @override
  String get addChecking => 'בודק…';

  @override
  String get addErrorExists => 'הערוץ כבר ברשימה.';

  @override
  String get addErrorInvalid => 'זה לא קישור או שם ערוץ תקין.';

  @override
  String get addErrorNoPreview =>
      'לערוץ אין תצוגה ציבורית באתר (פרטי, או שהתצוגה כבויה).';

  @override
  String startFromLabel(String p1) {
    return 'להקריא הודעות מתאריך: $p1';
  }

  @override
  String get startToday => 'היום';

  @override
  String get startYesterday => 'אתמול';

  @override
  String get start3Days => '3 ימים';

  @override
  String get startWeek => 'שבוע';

  @override
  String get startPickDate => 'בחירת תאריך…';

  @override
  String get changeStartTitle => 'תאריך התחלה';

  @override
  String get changeStartBody => 'הערוץ יוקרא מחדש החל מהתאריך הזה.';

  @override
  String get statusPlaying => 'מושמע';

  @override
  String get statusHeard => 'נשמע';

  @override
  String get statusNeuralReady => 'קול נוירוני הורד';

  @override
  String get statusDeviceReady => 'שמע לא־מקוון מוכן';

  @override
  String get statusNotDownloaded => 'השמע עוד לא הורד';

  @override
  String postMediaOnly(String p1) {
    return '[$p1, בלי טקסט — מדולג]';
  }

  @override
  String speedLabel(String p1) {
    return 'מהירות $p1';
  }

  @override
  String get noticeCaughtUp => 'שמעת את כל ההודעות.';

  @override
  String get noticeNoOtherChannels => 'אין הודעות חדשות בערוצים אחרים.';

  @override
  String syncTextProgress(String p1) {
    return 'מסנכרן את $p1…';
  }

  @override
  String syncAudioProgress(int p1, int p2) {
    return 'מוריד הקראות $p1 מתוך $p2…';
  }

  @override
  String get syncOffline =>
      'אין רשת — מושמע מה שכבר הורד. הסנכרון יתחדש אוטומטית.';

  @override
  String syncLast(String p1) {
    return 'סנכרון אחרון: $p1';
  }

  @override
  String get syncNotificationChannel => 'סנכרון';

  @override
  String get syncNotificationTitle => 'מוריד הודעות';

  @override
  String get settingsTitle => 'הגדרות';

  @override
  String get settingsVoice => 'קול מקוון';

  @override
  String get onlineVoiceDesc =>
      'השמע מורד עם כל הודעה בזמן הסנכרון, כך שאפשר להאזין לו גם בלי רשת.';

  @override
  String get ttsGemini => '‏Google Gemini (מפתח חינמי, מומלץ)';

  @override
  String get ttsGeminiDesc =>
      'קולות טבעיים מאוד בעברית ובאנגלית. מפתח חינמי מ־Google AI Studio דורש רק חשבון Google, בלי כרטיס אשראי. המסלול החינמי מכסה מספר מוגבל של הודעות ביום; את השאר מקריאים הקולות הלא־מקוונים.';

  @override
  String get ttsAzure => 'מיקרוסופט Azure (מפתח משלך)';

  @override
  String get ttsAzureDesc =>
      'משתמש במפתח Azure Speech שלך (במסלול החינמי: 500,000 תווים בחודש; ההרשמה ל־Azure דורשת כרטיס אשראי).';

  @override
  String get ttsNone => 'ללא, רק קולות לא־מקוונים';

  @override
  String get ttsNoneDesc =>
      'שום דבר לא נשלח לרשת; ההודעות מוקראות בקולות הלא־מקוונים שלהלן.';

  @override
  String get keySaveTest => 'שמירה ובדיקה';

  @override
  String get geminiKey => 'מפתח Gemini API';

  @override
  String get geminiGetKey => 'קבלת מפתח חינמי';

  @override
  String get geminiNoKey =>
      'אין עדיין מפתח. פתחו את Google AI Studio, התחברו עם חשבון Google, הקישו ‏“Create API key” והדביקו כאן את המפתח.';

  @override
  String get geminiOk => 'המפתח תקין. השמע של Gemini יורד בסנכרון הבא.';

  @override
  String get geminiBadKey =>
      'המפתח נדחה על ידי Google. העתיקו אותו שוב מ־Google AI Studio.';

  @override
  String get geminiQuota =>
      'הבקשות החינמיות של Gemini להיום נוצלו; הקולות הלא־מקוונים מקריאים עד שהמכסה תתחדש (בחצות לפי שעון החוף המערבי של ארה״ב, בערך 10:00 בבוקר בישראל).';

  @override
  String get geminiThrottled =>
      'התבקשנו להאט על ידי Gemini; ההורדות יתחדשו בעוד דקה.';

  @override
  String get geminiNote =>
      'כל השמעת דוגמה מנצלת בקשה אחת מהמכסה היומית החינמית. Google עשויה להשתמש בבקשות במסלול החינמי לשיפור המוצרים שלה.';

  @override
  String get voiceGemini => 'קול';

  @override
  String get azureKey => 'מפתח Azure Speech';

  @override
  String get azureRegion => 'אזור (למשל westeurope)';

  @override
  String get azureGetKey => 'קבלת מפתח';

  @override
  String get azureNoKey =>
      'אין עדיין מפתח. בפורטל Azure צרו משאב ‏“Speech” במסלול Free F0, והעתיקו לכאן את Key 1 ואת האזור שלו.';

  @override
  String get azureOk => 'המפתח תקין. השמע של Azure יורד בסנכרון הבא.';

  @override
  String get azureBadKey =>
      'המפתח או האזור נדחו. בדקו את שניהם בפורטל Azure ‏(Keys and Endpoint).';

  @override
  String get azureQuota =>
      'המכסה החינמית של החודש נוצלה; הקולות הלא־מקוונים מקריאים עד שתתחדש.';

  @override
  String get azureThrottled => 'Azure ביקשה להאט; ההורדות יתחדשו בקרוב.';

  @override
  String get voiceHebrew => 'קול לעברית';

  @override
  String get voiceEnglish => 'קול לאנגלית';

  @override
  String get voicePreview => 'השמעת דוגמה';

  @override
  String get voicePreviewFailed =>
      'לא ניתן היה להגיע לשירות (אין רשת, או שלא הוגדר מפתח).';

  @override
  String get voiceSampleHe => 'שלום! כך אקריא לך את ההודעות מערוצי הטלגרם שלך.';

  @override
  String get voiceSampleEn =>
      'Hi! This is how I will read your Telegram channels to you.';

  @override
  String get settingsOfflineVoice => 'קולות לא־מקוונים';

  @override
  String offlineVoiceDownload(String p1) {
    return 'הורדה $p1';
  }

  @override
  String offlineVoiceDownloading(String p1, String p2) {
    return 'מוריד… $p1 מתוך $p2';
  }

  @override
  String get offlineVoiceWaiting => 'ממתין לחיבור…';

  @override
  String get offlineVoiceFailed => 'ההורדה נכשלה. נסו שוב.';

  @override
  String get offlineVoiceRemove => 'הסרה';

  @override
  String offlineVoiceRemoveBody(String p1) {
    return 'למחוק את הקול $p1? קבצים שקולות מותקנים אחרים משתמשים בהם יישמרו.';
  }

  @override
  String get offlineVoiceUnsupported =>
      'הקול הזה לא פועל בטלפון הזה; קול אחר מקריא במקומו.';

  @override
  String deviceVoicesStatus(String p1, String p2) {
    return 'קולות הטלפון (לאנגלית בלי רשת, וכגיבוי אחרון). עברית: $p1  אנגלית: $p2';
  }

  @override
  String get deviceTtsSettings => 'הגדרות הקראה של הטלפון';

  @override
  String get settingsPlayback => 'השמעה';

  @override
  String get announceChannel => 'הקראת שם הערוץ';

  @override
  String get announceChannelDesc => 'במעבר לערוץ.';

  @override
  String get autoAdvance => 'המשך לערוץ הבא';

  @override
  String get autoAdvanceDesc => 'כשנגמרות ההודעות בערוץ.';

  @override
  String get settingsVolumeKeys => 'כפתורי עוצמת השמע';

  @override
  String get volumeKeysDesc =>
      'בזמן הקראה, לחיצה כפולה מהירה על כפתור עוצמה מפעילה פעולה; לחיצה בודדת עדיין משנה את העוצמה. עובד גם כשהמסך כבוי.';

  @override
  String get volumeKeysChannels => 'לחיצה כפולה: ערוץ הבא / קודם';

  @override
  String get volumeKeysMessages => 'לחיצה כפולה: הודעה הבאה / קודמת';

  @override
  String get volumeKeysOff => 'כבוי (כפתורי עוצמה רגילים)';

  @override
  String get settingsSync => 'סנכרון';

  @override
  String get syncInterval => 'סנכרון אוטומטי';

  @override
  String get syncManual => 'ידני בלבד';

  @override
  String minutes(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'כל $count דקות',
      two: 'כל $count דקות',
      one: 'כל דקה',
    );
    return '$_temp0';
  }

  @override
  String get allowMetered => 'שימוש בנתונים סלולריים';

  @override
  String get allowMeteredDesc => 'כבו כדי לסנכרן רק ב־Wi‑Fi.';

  @override
  String get prefetchCount => 'הודעות להורדה מראש בכל ערוץ';

  @override
  String get defaultStart => 'התחלת ברירת מחדל לערוצים חדשים';

  @override
  String daysBack(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ימים אחורה',
      two: 'יומיים אחורה',
      one: 'יום אחורה',
    );
    return '$_temp0';
  }

  @override
  String get settingsStorage => 'אחסון';

  @override
  String get deletePlayed => 'מחיקת השמע אחרי האזנה';

  @override
  String get deletePlayedDesc => 'הטקסט נשמר; מתפנה מקום.';

  @override
  String cacheSize(String p1) {
    return 'שמע שמור: $p1';
  }

  @override
  String get clearCache => 'מחיקת השמע';

  @override
  String get clearCacheBody =>
      'כל השמע שהורד יימחק. הטקסט נשמר והשמע יורד שוב בסנכרון הבא.';

  @override
  String get settingsAbout => 'אודות';

  @override
  String get aboutText =>
      'ההודעות נקראות מהתצוגה הציבורית של טלגרם באתר (t.me/s/…), ולכן אין צורך בחשבון. הקולות המקוונים מגיעים מ־Gemini API של Google או מ־Azure Speech של מיקרוסופט, עם המפתח שלך. הקולות העבריים הלא־מקוונים משתמשים ב־renikud (הגיית טקסט עברי, CC BY 4.0) ובקולות נועה (CC BY 4.0), שאול ומיכאל של Phonikud, ‏Kokoro עברית (לא מסחרי) ו־BlueTTS ‏(MIT), מאת thewh1teagle, צוות Phonikud, מקסים מליחוב ושותפים. הקול של הטלפון תמיד משמש כגיבוי אחרון.';

  @override
  String get offlineVoicesDesc =>
      'קולות עבריים נוירוניים שפועלים בטלפון עצמו, בלי אינטרנט. הקול שנבחר מקריא עברית בכל פעם שהקול המקוון אינו זמין. אפשר להוריד כמה שרוצים; קבצים משותפים יורדים פעם אחת.';

  @override
  String get voiceMale => 'גבר';

  @override
  String get voiceFemale => 'אישה';

  @override
  String get voiceFast => 'מהיר';

  @override
  String get voiceHeavy => 'הכי טבעי, איטי יותר, דורש טלפון חדש';

  @override
  String get offlineVoiceWaitingWifi => 'ממתין ל־Wi-Fi…';

  @override
  String offlineVoiceUseMobileData(String p1) {
    return 'להוריד עכשיו בגלישה סלולרית ($p1)';
  }

  @override
  String offlineVoiceNoSpace(String p1) {
    return 'אין מספיק מקום פנוי: נדרשים $p1.';
  }

  @override
  String get addErrorNoFeed =>
      'לא נמצא פיד חדשות באתר הזה. נסו את קישור ה־RSS שלו.';

  @override
  String get addErrorSiteRefused => 'האתר סירב לחיבור.';

  @override
  String get addErrorOffline => 'אין חיבור. אפשר להוסיף אתרים כשיש רשת.';

  @override
  String get errorSiteRefused => 'האתר סירב לחיבור; ננסה שוב.';

  @override
  String get savedTitle => 'שמור להאזנה';

  @override
  String get savedSubtitle => 'קישורים ששיתפת לאפליקציה';

  @override
  String savedToast(String p1) {
    return 'נשמר להאזנה: $p1';
  }

  @override
  String get savedToastPending => 'נשמר להאזנה. הכתבה תורד כשתהיה רשת.';

  @override
  String get savedAlready => 'הקישור הזה כבר שמור.';

  @override
  String get actionOpenLink => 'פתיחה בדפדפן';

  @override
  String offlineVoiceSpeed(String p1) {
    return 'בטלפון הזה: מייצר דיבור פי $p1 מהר יותר מקצב ההקראה';
  }

  @override
  String get offlineVoiceTooSlow =>
      'איטי מדי בטלפון הזה: ההשמעה תיעצר שוב ושוב. בחרו קול מהיר.';

  @override
  String get actionJumpEnd => 'קפיצה להודעה האחרונה';

  @override
  String get actionJumpStart => 'קפיצה להודעה הראשונה';

  @override
  String get actionPasteLink => 'האזנה לקישור שהועתק';

  @override
  String get pasteNoLink => 'העתיקו קישור קודם, ואז הקישו כאן כדי להאזין לו.';

  @override
  String get voiceRecommended => 'מומלץ';

  @override
  String get actionTranslate => 'תרגום…';

  @override
  String get translateTitle => 'להקריא את הערוץ ב';

  @override
  String get translateOff => 'כפי שנכתב (ללא תרגום)';

  @override
  String get translateNote =>
      'ההודעות מתורגמות בעזרת Google Translate ומוקראות בשפה שנבחרה. התרגום נעשה בזמן הסנכרון, כך שהן מתנגנות גם בלי חיבור.';

  @override
  String translatedTo(String p1) {
    return 'תורגם ל$p1';
  }

  @override
  String get showOriginal => 'הצג מקור';

  @override
  String get showTranslation => 'הצג תרגום';

  @override
  String get settingsTranslation => 'תרגום';

  @override
  String get translationDesc =>
      'כל ערוץ אפשר להקריא בשפה אחרת: בחרו “תרגום…” בתפריט שלו. השימוש ב-Google Translate חינמי; לשימוש רב אפשר להוסיף מפתח Google Cloud Translation API (רשות).';

  @override
  String get translateKey => 'מפתח Google Cloud Translation API (רשות)';

  @override
  String get translateKeySaved => 'נשמר. התרגומים ישתמשו במפתח שלך.';

  @override
  String get pronunciationTitle => 'הגייה (קולות לא מקוונים)';

  @override
  String get pronunciationDesc =>
      'כיצד הטקסט העברי הופך לצלילים לפני שקול לא מקוון מקריא אותו. נסו את שתי האפשרויות והשאירו את זו שנשמעת לכם טוב יותר; ההודעות מוקראות מחדש אחרי שינוי.';

  @override
  String get g2pRenikud => 'Renikud (מובנה)';

  @override
  String get g2pRenikudDesc => 'חוזה את ההגייה של כל אות ישירות. מהיר וקטן.';

  @override
  String get g2pPhonikud => 'Phonikud';

  @override
  String get g2pPhonikudDesc =>
      'המסלול המקורי של Phonikud: מודל מוסיף ניקוד והטעמה, ואז כללים קוראים אותם. הורדה חד-פעמית של 308 MB.';

  @override
  String get ipaStress => 'סימוני הטעמה';

  @override
  String get ipaStressDesc =>
      'לומר לקול איזו הברה להטעים. כבו כדי לשמוע איך זה נשמע בלעדיהם.';
}
