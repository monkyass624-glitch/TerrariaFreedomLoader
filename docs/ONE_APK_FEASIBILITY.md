# جدوى دمج Terraria داخل Freedom Engine

## نتيجة فحص ملف المستخدم

تم فحص ملف APK المرفق محلياً، دون نسخ أي ملفات اللعبة إلى مستودع GitHub.

النتائج التي أمكن التحقق منها:

- يحتوي APK على `lib/arm64-v8a/libpairipcore.so`.
- يحتوي `classes.dex` على أصناف PairIP، ومنها `com.pairip.VMRunner` و`com.pairip.SignatureCheck` و`com.pairip.StartupLauncher`.
- جدول `DT_NEEDED` في `libunity.so` لا يعرض اعتماداً مباشراً على `libpairipcore.so`. هذا لا ينفي حماية PairIP؛ فوجود مكونات bootstrap في DEX وملف المكتبة الأصلي دليل مستقل على وجودها.
- تضمين UnityPlayer ومكتبات Unity داخل تطبيق آخر ليس وحده كافياً لتضمين لعبة تجارية مبنية على Unity وتشغيلها. ميزة Unity as a Library مصممة لمشروع Unity ومكونات Runtime التي يبنيها صاحب المشروع، ولها قيود على دورة حياة التشغيل.

## لماذا لا نبني APK موحداً من الملف الحالي؟

1. إعادة تغليف APK التجاري في حزمة مختلفة تغيّر هوية الحزمة وتوقيع التطبيق.
2. Android Package Manager يتحقق من توقيع APK، كما يعتمد عزل التطبيقات على هوية التطبيق والتوقيع.
3. وجود PairIP في هذه النسخة يعني أن نسخ ملفات اللعبة إلى APK جديد قد يفشل عند فحوص التوقيع/الترخيص أو bootstrap.
4. لا يجوز أن نعالج ذلك بتعطيل PairIP أو إزالة فحوص التوقيع أو الترخيص.

## القرار التقني

لا نعلن أن نسخة Terraria المرفقة قابلة للدمج في APK موحد. أمامنا مساران مشروعان:

- **المسار المفضل:** الحصول على دعم رسمي أو SDK/مشروع دمج من صاحب اللعبة، ثم بناء Freedom Engine حول Runtime مصرح به.
- **مسار تطوير اللودر:** الاستمرار في تطوير مدير FREDL وواجهته ومحرك الحزم، والتحقق من توافق أي نسخة لعبة يملكها المستخدم ولا تمنع الاستضافة وفق شروطها. يجب أن يرفض الفاحص إصدارات PairIP بدلاً من محاولة تجاوز الحماية.

## ما لا يزال غير منجز

- لا يوجد بعد Runtime ينفذ `mod.dll` داخل Terraria.
- لا يوجد ربط API موثّق بين FREDL وأصناف اللعبة.
- لا يوجد تشغيل لعبة أو مود داخل جهاز فعلي تم إثباته من خلال هذا المستودع.
- نجاح بناء APK لا يساوي نجاح دمج اللعبة.

## المراجع التقنية

- Unity as a Library: https://docs.unity.cn/Manual/UnityasaLibrary.html
- Unity Android integration: https://docs.unity.cn/6000.1/Documentation/Manual/UnityasaLibrary-Android.html
- Android app signing and sandbox: https://source.android.com/docs/security/overview/app-security
