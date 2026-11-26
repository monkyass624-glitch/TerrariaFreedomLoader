# تقارير التحليل الثابت — Terraria Mobile 1.4.5.6.4 ARM64

هذه التقارير تخص الملفين اللذين أرسلهما المستخدم، وتُستخدم فقط مع نفس الـ build.

## الملفات الرئيسية

- `terraria_method_signatures_resolved.csv`: تواقيع 39 overload مختارة، مع نوع الإرجاع وأنواع المعاملات وأسمائها.
- `terraria_field_offsets_resolved.csv`: 3,038 إزاحة حقل خامة من الأصناف المستهدفة؛ قد تكون القيمة صفرية للحقول static، ويجب تأكيدها وقت التشغيل.
- `metadata_registration_and_signatures.json`: خريطة MetadataRegistration ومؤشرات Type/FieldOffsets وعدد السجلات التي تم التحقق منها.
- `terraria_methods_native.csv`: قائمة دوال Terraria ومؤشرات RVA التي تقع في مقاطع الكود التنفيذي.
- `initial_hook_candidates.csv`: قائمة أولية لدوال مرشحة للربط، وليست إثباتاً على نجاح Hook في اللعبة.
- `all_types.csv`, `terraria_classes_summary.csv`, `codegen_modules.csv`, `runtime_api_offsets.csv`: جداول مساعدة.

## حدود الدقة

- أسماء الأنواع جرى حلّها من جدول `Il2CppType*` داخل المكتبة، وربط الأنواع الصنفية بفهرس TypeDefinition.
- إزاحات الحقول هي قيم ثابتة مستخرجة من جدول field offsets؛ لا تميّز وحدها كل الحالات الخاصة بالحقول static أو حقول value types.
- تواقيع الدوال المستخرجة ثابتة من Metadata v31، لكنها تحتاج تأكيد runtime عبر واجهات `il2cpp_method_*` قبل استخدامها في Hook.
- لا يعني وجود RVA تنفيذي أن استدعاء Hook صحيح؛ يجب مطابقة MethodInfo pointer وABI وبصمة التعليمات داخل عملية اللعبة.
