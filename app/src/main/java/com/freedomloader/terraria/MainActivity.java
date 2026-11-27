package com.freedomloader.terraria;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_FREDL = 1001;
    private static final int REQUEST_IMPORT_GAME_APK = 1002;
    private static final int REQUEST_UNKNOWN_SOURCES = 1003;
    private static final String GAME_PACKAGE = "com.and.games505.TerrariaPaid";
    private static final String TARGET_GAME_VERSION = "1.4.5.6.4";
    private static final String PLATFORM = "android-arm64";
    private static final long MAX_FREDL_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_GAME_APK_BYTES = 500L * 1024L * 1024L;

    private static final int colorSurface = Color.rgb(25, 29, 37);
    private static final int colorCard = Color.rgb(36, 42, 53);
    private static final int colorText = Color.rgb(241, 244, 250);
    private static final int colorMuted = Color.rgb(171, 181, 196);
    private static final int colorAccent = Color.rgb(111, 160, 255);

    private static boolean nativeReady;
    static {
        try {
            System.loadLibrary("fredl_jni");
            nativeReady = true;
        } catch (UnsatisfiedLinkError error) {
            nativeReady = false;
        }
    }

    private native String nativeValidate(String path, String gameVersion, String platform);
    private native String nativeInstall(String path, String modsDirectory,
                                        String gameVersion, String platform);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView statusView, selectedView, installedView, importedApkView, gameStatusView;
    private Button validateButton, installButton, importGameButton, installGameButton, launchGameButton;
    private File selectedPackage, importedGameApk;
    private GameApkInfo importedGameInfo;
    private boolean packageValid, installedCurrent, importingGame, installingGame;

    private static final class GameApkInfo {
        final String packageName, versionName;
        final long versionCode;
        final boolean hasMetadata;
        GameApkInfo(String packageName, String versionName, long versionCode, boolean hasMetadata) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.hasMetadata = hasMetadata;
        }
        boolean isFredlTarget() {
            return TARGET_GAME_VERSION.equals(versionName) || versionCode == 301543L;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildInterface();
        refreshInstalledMods();
        restoreImportedApk();
        refreshInstalledGame();
        if (!nativeReady) showStatus("محرك التحقق من FREDL غير محمّل. استيراد وتشغيل Terraria لا يحتاجان هذا المحرك، لكن فحص المودات يحتاجه.", true);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshInstalledGame();
        updateGameButtons();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(colorSurface);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);

        TextView brand = text("TERRARIA", 13, colorAccent, true);
        brand.setLetterSpacing(0.18f);
        root.addView(brand);
        TextView title = text("Freedom Loader", 30, colorText, true);
        title.setPadding(0, dp(4), 0, dp(4));
        root.addView(title);
        root.addView(text("مدير Terraria وحزم المودات بصيغة .fredl", 15, colorMuted, false));
        TextView version = text("إصدار اللودر 0.2.0  •  هدف المودات الحالي: " + TARGET_GAME_VERSION + " • ARM64", 12, colorMuted, false);
        version.setPadding(0, dp(8), 0, dp(18));
        root.addView(version);

        LinearLayout gameCard = card(root, "استيراد وتشغيل Terraria");
        gameCard.addView(text("اختر ملف Terraria APK من هاتفك. سيفحص Freedom Loader اسم الحزمة والإصدار ووجود مكتبات ARM64، ثم يطلب من Android تثبيت اللعبة الأصلية. بعد التثبيت يفتح اللعبة كتطبيق مستقل؛ لن يتم تشغيل Unity داخل اللودر.", 14, colorMuted, false));
        importGameButton = button("استيراد Terraria APK");
        gameCard.addView(importGameButton);
        importGameButton.setOnClickListener(v -> openGameApkPicker());
        importedApkView = text("لم يتم استيراد APK بعد.", 13, colorMuted, false);
        importedApkView.setPadding(0, dp(10), 0, 0);
        gameCard.addView(importedApkView);
        installGameButton = button("تثبيت Terraria عبر Android");
        installGameButton.setEnabled(false);
        gameCard.addView(installGameButton);
        installGameButton.setOnClickListener(v -> requestGameInstall());
        launchGameButton = button("تشغيل Terraria المثبتة");
        launchGameButton.setEnabled(false);
        gameCard.addView(launchGameButton);
        launchGameButton.setOnClickListener(v -> launchTerraria());
        gameStatusView = text("اللعبة غير مفحوصة بعد.", 13, colorMuted, false);
        gameStatusView.setPadding(0, dp(10), 0, 0);
        gameCard.addView(gameStatusView);

        LinearLayout importCard = card(root, "استيراد مود FREDL");
        importCard.addView(text("اختر ملفاً بامتداد .fredl. سيتحقق التطبيق من البنية والتوافق وسلامة ملفات ZIP قبل تثبيته داخل مساحة التطبيق.", 14, colorMuted, false));
        Button choose = button("اختيار ملف .fredl");
        importCard.addView(choose);
        choose.setOnClickListener(v -> openFredlPicker());
        selectedView = text("لم يتم اختيار ملف مود بعد.", 13, colorMuted, false);
        selectedView.setPadding(0, dp(10), 0, 0);
        importCard.addView(selectedView);
        validateButton = button("فحص الحزمة");
        validateButton.setEnabled(false);
        importCard.addView(validateButton);
        validateButton.setOnClickListener(v -> validateSelected());
        installButton = button("تثبيت المود داخل مساحة التطبيق");
        installButton.setEnabled(false);
        importCard.addView(installButton);
        installButton.setOnClickListener(v -> installSelected());

        LinearLayout statusCard = card(root, "حالة العملية");
        statusView = text(nativeReady ? "جاهز لاستيراد APK أو مود FREDL." : "محرك FREDL الأصلي غير متاح؛ ميزات APK مستقلة عنه.", 14, colorText, false);
        statusView.setMinHeight(dp(54));
        statusCard.addView(statusView);
        LinearLayout installedCard = card(root, "المودات المثبتة محلياً");
        installedView = text("", 13, colorMuted, false);
        installedCard.addView(installedView);
        LinearLayout noteCard = card(root, "حدود الإصدار الحالي");
        noteCard.addView(text("يمكن لهذا الإصدار استيراد APK وفحصه وطلب تثبيته وتشغيل نسخة Terraria المثبتة، كما يمكنه التحقق من ملفات FREDL وحفظ المودات محلياً. حفظ المود لا يفعّله داخل اللعبة تلقائياً؛ ما زال يلزم تطوير واجهة Runtime متوافقة واختبارها. لا يتم تجاوز PairIP أو التوقيع أو الترخيص، ولا يتم تعديل APK اللعبة.", 13, colorMuted, false));
        TextView footer = text("FREDL package manager • v0.2.0", 11, colorMuted, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
        setContentView(scroll);
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackgroundColor(colorCard);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        parent.addView(card, params);
        TextView title = text(heading, 16, colorText, true);
        title.setPadding(0, 0, 0, dp(11));
        card.addView(title);
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(3), 1.0f);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorAccent));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void openGameApkPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_GAME_APK);
    }

    private void openFredlPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
        startActivityForResult(intent, REQUEST_IMPORT_FREDL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNKNOWN_SOURCES) { updateGameButtons(); return; }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT_GAME_APK) importGameApk(data.getData(), queryDisplayName(data.getData()));
        else if (requestCode == REQUEST_IMPORT_FREDL) importFredl(data.getData(), queryDisplayName(data.getData()));
    }

    private void importGameApk(Uri uri, String displayName) {
        if (importingGame) return;
        importingGame = true;
        updateGameButtons();
        importedApkView.setText("جارٍ نسخ وفحص APK...");
        worker.execute(() -> {
            File folder = new File(getFilesDir(), "imported-game");
            File temporary = new File(folder, "Terraria.apk.tmp");
            File destination = new File(folder, "Terraria.apk");
            try {
                if (!folder.exists() && !folder.mkdirs()) throw new Exception("تعذر إنشاء مجلد APK المستورد");
                long total = 0;
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary, false)) {
                    if (input == null) throw new Exception("تعذر فتح الملف المحدد");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_GAME_APK_BYTES) throw new Exception("حجم APK أكبر من 500 MiB");
                        if (folder.getUsableSpace() < 8L * 1024L * 1024L) throw new Exception("مساحة التخزين غير كافية");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (total < 1024L * 1024L) throw new Exception("الملف أصغر من أن يكون APK صالحاً");
                GameApkInfo info = inspectGameApk(temporary);
                if (destination.exists() && !destination.delete()) throw new Exception("تعذر استبدال APK المستورد السابق");
                if (!temporary.renameTo(destination)) throw new Exception("تعذر حفظ APK في مساحة التطبيق");

                final long copied = total;
                String label = displayName == null ? "Terraria.apk" : displayName;
                runOnUiThread(() -> {
                    importedGameApk = destination;
                    importedGameInfo = info;
                    importingGame = false;
                    importedApkView.setText(label + " • " + (copied / (1024 * 1024)) + " MiB\nالحزمة: " + info.packageName +
                            "\nالإصدار: " + info.versionName + " (code " + info.versionCode) +
                            "\nARM64: موجود • global-metadata.dat: " + (info.hasMetadata ? "موجود" : "غير موجود"));
                    gameStatusView.setText(info.isFredlTarget()
                            ? "APK يطابق إصدار المودات المستهدف " + TARGET_GAME_VERSION + "."
                            : "تم استيراد APK، لكن إصدار FREDL الحالي يستهدف " + TARGET_GAME_VERSION + "؛ يمكن تثبيت اللعبة وتشغيلها، لكن توافق المودات غير مؤكد.");
                    gameStatusView.setTextColor(info.isFredlTarget() ? Color.rgb(158, 224, 176) : Color.rgb(255, 196, 120));
                    updateGameButtons();
                    showStatus("تم استيراد APK وفحصه. لم يتم تثبيته بعد.", false);
                });
            } catch (Exception error) {
                temporary.delete();
                runOnUiThread(() -> {
                    importingGame = false;
                    importedGameApk = null;
                    importedGameInfo = null;
                    importedApkView.setText("لم يتم استيراد APK صالح.");
                    gameStatusView.setText("فشل فحص APK: " + safeMessage(error));
                    gameStatusView.setTextColor(Color.rgb(255, 145, 145));
                    updateGameButtons();
                    showStatus("فشل استيراد Terraria: " + safeMessage(error), true);
                });
            }
        });
    }

    private GameApkInfo inspectGameApk(File apk) throws Exception {
        PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA);
        if (info == null || info.packageName == null) throw new Exception("Android لم يتعرف على الملف كحزمة APK صالحة");
        if (!GAME_PACKAGE.equals(info.packageName)) throw new Exception("اسم الحزمة غير صحيح: " + info.packageName + "؛ المطلوب " + GAME_PACKAGE);
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        String version = info.versionName == null ? "غير معروف" : info.versionName;
        boolean il2cpp, unity, metadata;
        try (ZipFile zip = new ZipFile(apk)) {
            il2cpp = zip.getEntry("lib/arm64-v8a/libil2cpp.so") != null;
            unity = zip.getEntry("lib/arm64-v8a/libunity.so") != null;
            metadata = zip.getEntry("assets/bin/Data/Managed/Metadata/global-metadata.dat") != null;
        }
        if (!il2cpp || !unity) throw new Exception("APK لا يحتوي libil2cpp.so وlibunity.so لمعمارية arm64-v8a. قد يكون ملف split ناقصاً.");
        return new GameApkInfo(info.packageName, version, code, metadata);
    }

    private void requestGameInstall() {
        if (importedGameApk == null || importedGameInfo == null || installingGame) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                startActivityForResult(settings, REQUEST_UNKNOWN_SOURCES);
                showStatus("اسمح لهذا التطبيق بتثبيت ملفات APK من إعدادات Android، ثم ارجع واضغط تثبيت مرة أخرى.", false);
            } catch (Exception error) {
                showStatus("تعذر فتح إعداد تثبيت التطبيقات: " + safeMessage(error), true);
            }
            return;
        }

        installingGame = true;
        updateGameButtons();
        showStatus("جارٍ إرسال APK إلى مثبت Android...", false);
        worker.execute(() -> {
            int sessionId = -1;
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(GAME_PACKAGE);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                     InputStream input = new FileInputStream(importedGameApk);
                     OutputStream output = session.openWrite("base.apk", 0, importedGameApk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    session.fsync(output);
                    Intent callback = new Intent(this, InstallResultReceiver.class);
                    callback.setAction(InstallResultReceiver.ACTION_INSTALL_STATUS);
                    int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) pendingFlags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getBroadcast(this, sessionId, callback, pendingFlags);
                    session.commit(pending.getIntentSender());
                }
                runOnUiThread(() -> {
                    installingGame = false;
                    updateGameButtons();
                    showStatus("أرسل Freedom Loader ملف APK إلى Android. أكمل التأكيد في نافذة مثبت الحزم؛ نجاح العملية سيظهر من Android.", false);
                });
            } catch (Exception error) {
                if (sessionId >= 0) {
                    try { getPackageManager().getPackageInstaller().abandonSession(sessionId); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> {
                    installingGame = false;
                    updateGameButtons();
                    showStatus("تعذر إرسال APK للتثبيت: " + safeMessage(error), true);
                });
            }
        });
    }

    private void launchTerraria() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(GAME_PACKAGE);
            if (launch == null) {
                showStatus("Terraria غير مثبتة أو لا تحتوي نشاط تشغيل. ثبّتها أولاً من زر التثبيت.", true);
                refreshInstalledGame();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception error) { showStatus("تعذر تشغيل Terraria: " + safeMessage(error), true); }
    }

    private void restoreImportedApk() {
        File apk = new File(new File(getFilesDir(), "imported-game"), "Terraria.apk");
        if (!apk.isFile()) return;
        importedApkView.setText("جارٍ التحقق من APK المحفوظ...");
        worker.execute(() -> {
            try {
                GameApkInfo info = inspectGameApk(apk);
                runOnUiThread(() -> {
                    importedGameApk = apk;
                    importedGameInfo = info;
                    importedApkView.setText("Terraria.apk محفوظ • الإصدار: " + info.versionName + " • ARM64: موجود");
                    updateGameButtons();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    importedApkView.setText("APK المحفوظ غير صالح: " + safeMessage(error));
                    importedGameApk = null;
                    importedGameInfo = null;
                    updateGameButtons();
                });
            }
        });
    }

    private void refreshInstalledGame() {
        if (gameStatusView == null || launchGameButton == null) return;
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) info = getPackageManager().getPackageInfo(GAME_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            else info = getPackageManager().getPackageInfo(GAME_PACKAGE, 0);
            String version = info.versionName == null ? "غير معروف" : info.versionName;
            gameStatusView.setText("Terraria مثبتة على الجهاز • الإصدار: " + version);
            gameStatusView.setTextColor(Color.rgb(158, 224, 176));
            launchGameButton.setEnabled(true);
        } catch (PackageManager.NameNotFoundException e) {
            gameStatusView.setText("Terraria غير مثبتة حالياً. استورد APK ثم ثبّتها.");
            gameStatusView.setTextColor(colorMuted);
            launchGameButton.setEnabled(false);
        } catch (Exception error) {
            gameStatusView.setText("تعذر التحقق من تثبيت Terraria: " + safeMessage(error));
            gameStatusView.setTextColor(Color.rgb(255, 145, 145));
            launchGameButton.setEnabled(false);
        }
    }

    private void updateGameButtons() {
        if (importGameButton != null) importGameButton.setEnabled(!importingGame);
        if (installGameButton != null) installGameButton.setEnabled(!importingGame && !installingGame &&
                importedGameApk != null && importedGameApk.isFile() && importedGameInfo != null);
    }

    private void importFredl(Uri uri, String displayName) {
        if (displayName == null || !displayName.toLowerCase().endsWith(".fredl")) {
            showStatus("الملف يجب أن ينتهي بالامتداد .fredl", true);
            return;
        }
        selectedView.setText("يتم نسخ: " + displayName);
        setBusy(true);
        worker.execute(() -> {
            try {
                File inbox = new File(getCacheDir(), "fredl-inbox");
                if (!inbox.exists() && !inbox.mkdirs()) throw new Exception("تعذر إنشاء مجلد الاستيراد");
                File destination = new File(inbox, "selected.fredl");
                long total = 0;
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(destination, false)) {
                    if (input == null) throw new Exception("تعذر فتح الملف المحدد");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_FREDL_BYTES) throw new Exception("الحزمة تتجاوز 128 MiB");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                final long copiedBytes = total;
                String validation = nativeReady ? nativeValidate(destination.getAbsolutePath(), TARGET_GAME_VERSION, PLATFORM) : "محرك FREDL الأصلي غير محمّل";
                runOnUiThread(() -> {
                    selectedPackage = destination;
                    packageValid = validation.equals("VALID");
                    installedCurrent = false;
                    selectedView.setText(displayName + "  •  " + (copiedBytes / 1024) + " KB");
                    showStatus(validation, !validation.equals("VALID"));
                    validateButton.setEnabled(nativeReady);
                    installButton.setEnabled(nativeReady && validation.equals("VALID"));
                    setBusy(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    selectedPackage = null;
                    packageValid = false;
                    installedCurrent = false;
                    selectedView.setText("لم يتم اختيار حزمة صالحة");
                    showStatus("فشل الاستيراد: " + safeMessage(error), true);
                    validateButton.setEnabled(false);
                    installButton.setEnabled(false);
                    setBusy(false);
                });
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void validateSelected() {
        if (selectedPackage == null || !nativeReady) return;
        setBusy(true);
        worker.execute(() -> {
            String result = nativeValidate(selectedPackage.getAbsolutePath(), TARGET_GAME_VERSION, PLATFORM);
            runOnUiThread(() -> {
                showStatus(result, !result.equals("VALID"));
                packageValid = result.equals("VALID");
                installedCurrent = false;
                setBusy(false);
            });
        });
    }

    private void installSelected() {
        if (selectedPackage == null || !nativeReady) return;
        setBusy(true);
        worker.execute(() -> {
            File mods = new File(getFilesDir(), "mods");
            String result = nativeInstall(selectedPackage.getAbsolutePath(), mods.getAbsolutePath(), TARGET_GAME_VERSION, PLATFORM);
            runOnUiThread(() -> {
                showStatus(result + "\nالمجلد المحلي: " + mods.getAbsolutePath(), !result.equals("INSTALLED"));
                installedCurrent = result.equals("INSTALLED");
                packageValid = false;
                setBusy(false);
                refreshInstalledMods();
            });
        });
    }

    private void refreshInstalledMods() {
        if (installedView == null) return;
        File mods = new File(getFilesDir(), "mods");
        File[] entries = mods.listFiles(File::isDirectory);
        if (entries == null || entries.length == 0) {
            installedView.setText("لا توجد مودات مثبتة محلياً.");
            return;
        }
        StringBuilder result = new StringBuilder();
        for (File entry : entries) {
            if (result.length() > 0) result.append("\n");
            result.append("• ").append(entry.getName());
        }
        installedView.setText(result.toString());
    }

    private void showStatus(String message, boolean error) {
        if (statusView == null) return;
        statusView.setText(message);
        statusView.setTextColor(error ? Color.rgb(255, 145, 145) : Color.rgb(158, 224, 176));
    }

    private void setBusy(boolean busy) {
        if (validateButton != null) validateButton.setEnabled(!busy && nativeReady && selectedPackage != null);
        if (installButton != null) installButton.setEnabled(!busy && nativeReady && packageValid && !installedCurrent);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
