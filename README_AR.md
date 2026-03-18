# مشروع تطبيق Sedir Ads - WebView + Credential Manager

هذه نسخة Android WebView محدثة تضيف **تسجيل الدخول بجوجل من الهاتف نفسه** عبر **Credential Manager** بدل محاولة تسجيل الدخول داخل WebView.

## الموجود الآن
- فتح `https://sedirads.com/` داخل التطبيق نفسه
- سحب للتحديث
- شريط تحميل أعلى الصفحة
- زر رجوع داخل التطبيق
- رفع الملفات من حقول `<input type="file">`
- تنزيل الملفات عبر DownloadManager
- السماح بالكوكيز و third-party cookies
- طلب إذن الإشعارات على Android 13+
- أي رابط خارج `sedirads.com` يفتح خارج التطبيق
- زر Native أعلى التطبيق باسم **تسجيل الدخول بجوجل من الهاتف**
- استخدام **Credential Manager** و **Sign in with Google** بدل WebView login
- إرسال `id_token` إلى إضافة الموقع الحالية على:
  - `https://sedirads.com/index.php?gotRedirect=1`
- مزامنة كوكيز الدخول مع WebView ثم فتح لوحة المستخدم بعد نجاح تسجيل الدخول

## إعدادات Google المستخدمة
- Web Client ID مضبوط داخل التطبيق على:
  - `262552582759-db1fuh11o4gpvqjl0p053mo0tfd4bfi6.apps.googleusercontent.com`

## لماذا هذا أفضل من Google login داخل WebView
- Google قد تمنع تسجيل الدخول داخل WebView بخطأ `disallowed_useragent`
- Credential Manager يعرض حسابات Google الموجودة على الهاتف بطريقة أصلية وآمنة
- التطبيق يستفيد من إضافة `google_onetap_login` الموجودة في الموقع دون الاعتماد على One Tap داخل WebView

## ملاحظات مهمة
- هذه النسخة تفترض أن إضافة الموقع الحالية تقبل `POST id_token` على `?gotRedirect=1` وتضبط كوكيز المستخدم عند النجاح
- إذا غيّرت دومين الموقع أو مسار إضافة Google لاحقًا، حدّث هذه القيم في `strings.xml`
- عند الضغط على زر Google الموجود داخل صفحات الموقع نفسها، سيتم فتح الروابط الخارجية خارج التطبيق بدل WebView

## البناء عبر GitHub
1. فك ضغط المشروع.
2. ارفع كل الملفات إلى مستودع GitHub.
3. افتح تبويب Actions.
4. شغل `Build debug APK`.
5. بعد نجاح البناء نزّل artifact باسم `sedirads-webview-debug-apk`.

## تحسينات لاحقة ممكنة
- إخفاء زر Google الخاص بالموقع داخل WebView عندما يكون التطبيق مفتوحًا
- إضافة FCM للإشعارات الحقيقية من التطبيق
- إضافة Splash Screen مخصصة
- ربط Digital Asset Links إذا أردت تكامل WebView auth أعمق لاحقًا
