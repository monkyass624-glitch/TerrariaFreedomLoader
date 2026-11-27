package com.freedomloader.terraria;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_FREDL = 1001;
    private static final String GAME_VERSION = "1.4.5.6.4";
    private static final String PLATFORM = "android-arm64";
    private static final long MAX_PACKAGE_BYTES = 128L * 1024L * 1024L;

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
    private TextView statusView;
    private TextView selectedView;
    private TextView installedView;
    private Button validateButton;
    private Button installButton;
    private Button connectGameButton;
    private Button playGameButton;
    private TextView gameStatusView;
    private GameRuntime.Result gameRuntimeResult;
    private boolean checkingGame;
    private File selectedPackage;
    private boolean packageValid;
    private boolean installedCurrent;

    private int colorSurface = Color.rgb(25, 29, 37);
    private int colorCard = Color.rgb(36, 42, 53);
    private int colorText = Color.rgb(241, 244, 250);
    private int colorMuted = Color.rgb(171, 181, 196);
    private int colorAccent = Color.rgb(111, 160, 255);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildInterface();
        refreshInstalled();
        if (!nativeReady) {
            showStatus("لم يتم تحميل محرك FREDL الأصلي. أعد تثبيت APK الصحيح.", true);
        }
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

        TextView subtitle = text("مدير حزم المودات بصيغة .fredl", 15, colorMuted, false);
        root.addView(subtitle);

        TextView version = text("الهدف الحالي: Terraria Mobile " + GAME_VERSION
                + "  •  ARM64", 12, colorMuted, false);
        version.setPadding(0, dp(8), 0, dp(18));
        root.addView(version);

        LinearLayout gameCard = card(root, "ربط Terraria");
        TextView gameInfo = text(
                "يفحص التطبيق نسخة Terraria المثبتة وملفات ARM64. إذا كانت النسخة متوافقة وغير محمية بـ PairIP، سيحاول تشغيل Unity داخل عملية Freedom Loader.",
                14, colorMuted, false);
        gameCard.addView(gameInfo);

        connectGameButton = button("فحص توافق Terraria");
        gameCard.addView(connectGameButton);
        connectGameButton.setOnClickListener(v -> inspectTerraria());

        gameStatusView = text("لم يتم فحص اللعبة بعد.", 13, colorMuted, false);
        gameStatusView.setPadding(0, dp(10), 0, 0);
        gameCard.addView(gameStatusView);

        playGameButton = button("تشغيل Terraria داخل Freedom Loader");
        playGameButton.setEnabled(false);
        gameCard.addView(playGameButton);
        playGameButton.setOnClickListener(v -> startTerraria());

        LinearLayout importCard = card(root, "استيراد مود");
        TextView info = text("اختر ملفاً بامتداد .fredl. سيتحقق التطبيق من البنية والتوافق "
                + "وسلامة ملفات ZIP قبل السماح بالتثبيت.", 14, colorMuted, false);
        importCard.addView(info);

        Button choose = button("اختيار ملف .fredl");
        importCard.addView(choose);
        choose.setOnClickListener(v -> openPackagePicker());

        selectedView = text("لم يتم اختيار ملف بعد", 13, colorMuted, false);
        selectedView.setPadding(0, dp(10), 0, 0);
        importCard.addView(selectedView);

        validateButton = button("فحص الحزمة");
        validateButton.setEnabled(false);
        importCard.addView(validateButton);
        validateButton.setOnClickListener(v -> validateSelected());

        installButton = button("تثبيت داخل مساحة التطبيق");
        installButton.setEnabled(false);
        importCard.addView(installButton);
        installButton.setOnClickListener(v -> installSelected());

        LinearLayout statusCard = card(root, "حالة العملية");
        statusView = text(nativeReady ? "جاهز لاستيراد أول مود." :
                "محرك FREDL الأصلي غير متاح.", 14, colorText, false);
        statusView.setMinHeight(dp(54));
        statusCard.addView(statusView);

        LinearLayout installedCard = card(root, "المودات المثبتة محلياً");
        installedView = text("", 13, colorMuted, false);
        installedCard.addView(installedView);

        LinearLayout noteCard = card(root, "ملاحظة مهمة");
        TextView note = text("ملفات FREDL تُفحص وتُثبت داخل مساحة التطبيق. الربط داخل اللعبة "
                + "يعمل فقط مع بناء Terraria متوافق يسمح باستضافة Unity؛ النسخ المحمية بـ PairIP "
                + "يتم رفضها ولا نحاول تجاوز الحماية. هذه المرحلة تشغّل محرك اللعبة وتفحص IL2CPP؛ "
                + "تشغيل كود المودات C# يحتاج Runtime وواجهة API واختباراً منفصلاً.", 13, colorMuted, false);
        noteCard.addView(note);

        TextView footer = text("FREDL package manager • v0.1.0", 11, colorMuted, false);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openPackagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/octet-stream", "*/*"});
        startActivityForResult(intent, REQUEST_IMPORT_FREDL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_FREDL || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;

        Uri uri = data.getData();
        String displayName = queryDisplayName(uri);
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
                        if (total > MAX_PACKAGE_BYTES) throw new Exception("الحزمة تتجاوز 128 MiB");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                final long copiedBytes = total;
                String validation = nativeReady
                        ? nativeValidate(destination.getAbsolutePath(), GAME_VERSION, PLATFORM)
                        : "محرك FREDL الأصلي غير محمّل";
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
                    showStatus("فشل الاستيراد: " + error.getMessage(), true);
                    validateButton.setEnabled(false);
                    installButton.setEnabled(false);
                    setBusy(false);
                });
            }
        });
    }


    private void inspectTerraria() {
        if (checkingGame) return;
        checkingGame = true;
        connectGameButton.setEnabled(false);
        playGameButton.setEnabled(false);
        gameStatusView.setText("جارٍ فحص Terraria وتجهيز مكتبات ARM64...");
        gameStatusView.setTextColor(colorMuted);
        worker.execute(() -> {
            GameRuntime.Result result = GameRuntime.prepare(getApplicationContext());
            runOnUiThread(() -> {
                checkingGame = false;
                gameRuntimeResult = result;
                connectGameButton.setEnabled(true);
                playGameButton.setEnabled(result.ready);
                gameStatusView.setText(result.message);
                gameStatusView.setTextColor(result.ready
                        ? Color.rgb(158, 224, 176) : Color.rgb(255, 145, 145));
            });
        });
    }

    private void startTerraria() {
        if (gameRuntimeResult == null || !gameRuntimeResult.ready) {
            showStatus("افحص توافق Terraria أولاً.", true);
            return;
        }
        try {
            startActivity(new Intent(this, GameActivity.class));
        } catch (Throwable error) {
            gameStatusView.setText("تعذر فتح شاشة اللعبة: " + error.getMessage());
            gameStatusView.setTextColor(Color.rgb(255, 145, 145));
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
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
            String result = nativeValidate(selectedPackage.getAbsolutePath(), GAME_VERSION, PLATFORM);
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
            String result = nativeInstall(selectedPackage.getAbsolutePath(), mods.getAbsolutePath(),
                    GAME_VERSION, PLATFORM);
            runOnUiThread(() -> {
                showStatus(result + "\nالمجلد المحلي: " + mods.getAbsolutePath(),
                        !result.equals("INSTALLED"));
                installedCurrent = result.equals("INSTALLED");
                packageValid = false;
                setBusy(false);
                refreshInstalled();
            });
        });
    }

    private void refreshInstalled() {
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
        if (validateButton != null)
            validateButton.setEnabled(!busy && nativeReady && selectedPackage != null);
        if (installButton != null)
            installButton.setEnabled(!busy && nativeReady && packageValid && !installedCurrent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
