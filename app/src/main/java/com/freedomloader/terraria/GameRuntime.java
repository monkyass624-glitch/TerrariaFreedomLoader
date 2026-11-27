package com.freedomloader.terraria;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GameRuntime {
    public static final String PACKAGE = "com.and.games505.TerrariaPaid";
    public static final String TARGET_VERSION = "1.4.5.6.4";
    public static final long TARGET_VERSION_CODE = 301543L;
    public static final String ABI = "arm64-v8a";
    private static final String[] LIBRARIES = {"libmain.so", "libunity.so", "libil2cpp.so"};
    private static final long MAX_LIBRARY_BYTES = 120L * 1024L * 1024L;

    public static final class Result {
        public final boolean ready;
        public final String message;
        public final String versionName;
        public final long versionCode;
        public final File libraryDirectory;
        public final Context gameContext;
        public final ClassLoader gameClassLoader;

        private Result(boolean ready, String message, String versionName, long versionCode,
                       File libraryDirectory, Context gameContext, ClassLoader gameClassLoader) {
            this.ready = ready;
            this.message = message;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.libraryDirectory = libraryDirectory;
            this.gameContext = gameContext;
            this.gameClassLoader = gameClassLoader;
        }

        static Result fail(String message, String version, long code) {
            return new Result(false, message, version, code, null, null, null);
        }
    }

    private GameRuntime() { }

    public static Result prepare(Context context) {
        if (!Process.is64Bit()) return Result.fail("يلزم نظام Android بمعمارية 64-bit.", "", 0);
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if (ABI.equals(abi)) arm64 = true;
        if (!arm64) return Result.fail("هذا الإصدار يتطلب ARM64.", "", 0);

        final PackageInfo info;
        try {
            info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return Result.fail("لم يتم العثور على Terraria من Google Play. ثبّت اللعبة أولاً.", "", 0);
        } catch (Throwable t) {
            return Result.fail("تعذر فحص تثبيت Terraria: " + safeMessage(t), "", 0);
        }

        long versionCode = Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() : info.versionCode;
        String versionName = info.versionName == null ? "" : info.versionName;
        boolean targetVersion = versionName.contains(TARGET_VERSION) ||
                versionCode == TARGET_VERSION_CODE;
        if (!targetVersion) {
            return Result.fail("نسخة Terraria المكتشفة هي " + versionName + " (code " +
                    versionCode + "). هذه النسخة الأولية مخصصة لـ " + TARGET_VERSION + ".",
                    versionName, versionCode);
        }

        try {
            ApplicationInfo app = info.applicationInfo;
            if (app == null || app.sourceDir == null) {
                return Result.fail("لم يعثر Android على ملف APK الأساسي للعبة.", versionName, versionCode);
            }
            String pairIpEvidence = findPairIpBootstrapEvidence(app);
            if (pairIpEvidence != null) {
                return Result.fail(
                        "اكتشف Freedom Loader مؤشر PairIP: " + pairIpEvidence +
                        ". هذا البناء غير متوافق مبدئياً مع الاستضافة داخل تطبيق منفصل؛ " +
                        "لن نحاول تعطيل الحماية.",
                        versionName, versionCode);
            }

            File libraryDirectory = extractLibraries(context, app);
            File unity = new File(libraryDirectory, "libunity.so");
            if (dependsOnPairIp(unity)) {
                return Result.fail(
                        "نسخة Terraria هذه تعتمد على PairIP (libpairipcore.so). " +
                        "هذه الحماية غير مدعومة في Freedom Loader؛ لن نحاول تجاوزها. " +
                        "نحتاج نسخة متوافقة لا تعتمد على PairIP.",
                        versionName, versionCode);
            }

            Context gameContext = context.createPackageContext(
                    PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = gameContext.getClassLoader();
            Class<?> unityPlayer = loader.loadClass("com.unity3d.player.UnityPlayer");
            boolean hasActivityConstructor = false;
            for (java.lang.reflect.Constructor<?> constructor : unityPlayer.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && Context.class.isAssignableFrom(parameters[0])) {
                    hasActivityConstructor = true;
                    break;
                }
                if (parameters.length == 1 && Context.class.isAssignableFrom(parameters[0])) {
                    hasActivityConstructor = true;
                }
            }
            if (!hasActivityConstructor) {
                return Result.fail("UnityPlayer موجود لكن لم نجد مُنشئ Context متوافقاً.",
                        versionName, versionCode);
            }

            return new Result(true,
                    "Terraria متوافقة مبدئياً: " + versionName +
                    ". سيتم تشغيل Unity داخل مساحة Freedom Loader.",
                    versionName, versionCode, libraryDirectory, gameContext, loader);
        } catch (Throwable t) {
            return Result.fail("فشل تجهيز Terraria للاستضافة: " + safeMessage(t),
                    versionName, versionCode);
        }
    }

    // PairIP may initialize from Java bootstrap code rather than DT_NEEDED in libunity.
    // Detect its known package/library markers and reject the build; never patch or bypass it.
    private static String findPairIpBootstrapEvidence(ApplicationInfo app) throws Exception {
        List<String> evidence = new ArrayList<>();
        File nativeDirectory = new File(app.nativeLibraryDir == null ? "" : app.nativeLibraryDir);
        if (new File(nativeDirectory, "libpairipcore.so").isFile()) {
            evidence.add("libpairipcore.so موجودة في مجلد المكتبات المثبتة");
        }

        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(app.sourceDir);
        if (app.splitSourceDirs != null) {
            for (String split : app.splitSourceDirs) if (split != null) apkPaths.add(split);
        }

        final String[] markers = {
                "com/pairip/VMRunner",
                "com.pairip.VMRunner",
                "com/pairip/SignatureCheck",
                "com/pairip/StartupLauncher",
                "libpairipcore.so"
        };
        for (String apk : apkPaths) {
            try (ZipFile zip = new ZipFile(apk)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith("/libpairipcore.so") ||
                            name.equals("lib/arm64-v8a/libpairipcore.so")) {
                        evidence.add(apk + " يحتوي " + name);
                        continue;
                    }
                    if (!name.matches("classes([0-9]+)?\\.dex")) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        String marker = streamFindMarker(input, markers);
                        if (marker != null) {
                            evidence.add(apk + " / " + name + " يحتوي مكوّناً: " + marker);
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // Unreadable splits are handled later by the library extractor.
            }
        }

        if (evidence.isEmpty()) return null;
        StringBuilder report = new StringBuilder();
        for (String item : evidence) {
            if (report.length() > 0) report.append("؛ ");
            report.append(item);
        }
        return report.toString();
    }

    private static String streamFindMarker(InputStream input, String[] markers) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        String tail = "";
        int count;
        int keep = 64;
        while ((count = input.read(buffer)) != -1) {
            String part = tail + new String(buffer, 0, count, StandardCharsets.ISO_8859_1);
            for (String marker : markers) {
                if (part.contains(marker)) return marker;
                keep = Math.max(keep, marker.length());
            }
            tail = part.substring(Math.max(0, part.length() - keep));
        }
        return null;
    }

    private static File extractLibraries(Context context, ApplicationInfo app) throws Exception {
        File root = new File(context.getFilesDir(), "terraria-runtime/" + TARGET_VERSION_CODE + "/" + ABI);
        if (!root.exists() && !root.mkdirs()) throw new Exception("تعذر إنشاء مجلد ملفات اللعبة");
        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(app.sourceDir);
        if (app.splitSourceDirs != null) {
            for (String split : app.splitSourceDirs) if (split != null) apkPaths.add(split);
        }

        for (String library : LIBRARIES) {
            File destination = new File(root, library);
            if (destination.isFile() && destination.length() > 1024 * 1024) continue;
            File temporary = new File(root, library + ".tmp");
            if (temporary.exists()) temporary.delete();
            boolean copied = false;

            // Some Android builds expose the already extracted native libraries.
            File nativeDirectory = new File(app.nativeLibraryDir == null ? "" : app.nativeLibraryDir);
            File extracted = new File(nativeDirectory, library);
            if (extracted.isFile() && extracted.canRead()) {
                try (InputStream input = new FileInputStream(extracted);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    copyLimited(input, output);
                    output.getFD().sync();
                    copied = true;
                }
            }

            // Play installs may put arm64 libraries in a config split rather than base.apk.
            if (!copied) {
                for (String apk : apkPaths) {
                    try (ZipFile zip = new ZipFile(apk)) {
                        ZipEntry entry = zip.getEntry("lib/" + ABI + "/" + library);
                        if (entry == null) continue;
                        try (InputStream input = zip.getInputStream(entry);
                             FileOutputStream output = new FileOutputStream(temporary)) {
                            copyLimited(input, output);
                            output.getFD().sync();
                            copied = true;
                        }
                        if (copied) break;
                    } catch (java.io.IOException ignored) {
                        // Continue to the next split APK.
                    }
                }
            }

            if (!copied || !temporary.isFile() || temporary.length() < 1024 * 1024) {
                temporary.delete();
                throw new Exception("ملف اللعبة مفقود أو غير قابل للقراءة: " + library);
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete();
                throw new Exception("تعذر استبدال " + library);
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new Exception("تعذر تثبيت ملف " + library + " في مساحة التطبيق");
            }
        }
        return root;
    }

    private static void copyLimited(InputStream input, FileOutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_LIBRARY_BYTES) throw new Exception("ملف مكتبة اللعبة أكبر من الحد المسموح");
            output.write(buffer, 0, count);
        }
    }

    // Read ELF64 DT_NEEDED records. This is a compatibility gate, not a patcher.
    private static boolean dependsOnPairIp(File library) throws Exception {
        byte[] bytes;
        try (InputStream input = new FileInputStream(library);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_LIBRARY_BYTES) throw new Exception("libunity.so يتجاوز الحجم المتوقع");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length < 64 || bytes[0] != 0x7f || bytes[1] != 'E' ||
                bytes[2] != 'L' || bytes[3] != 'F' || bytes[4] != 2 || bytes[5] != 1) {
            throw new Exception("libunity.so ليس ELF64 little-endian صالحاً");
        }

        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long phoff = data.getLong(32);
        int phentsize = data.getShort(54) & 0xffff;
        int phnum = data.getShort(56) & 0xffff;
        if (phentsize < 56 || phnum == 0 || phnum > 4096 ||
                phoff < 0 || phoff + (long) phentsize * phnum > bytes.length) {
            throw new Exception("جدول ELF program headers غير صالح");
        }

        long dynamicOffset = -1, dynamicSize = 0, stringTableAddress = -1;
        List<long[]> loadSegments = new ArrayList<>();
        for (int i = 0; i < phnum; i++) {
            int p = (int) (phoff + (long) i * phentsize);
            long type = Integer.toUnsignedLong(data.getInt(p));
            long offset = data.getLong(p + 8);
            long vaddr = data.getLong(p + 16);
            long fileSize = data.getLong(p + 32);
            long memSize = data.getLong(p + 40);
            if (offset < 0 || fileSize < 0 || offset + fileSize > bytes.length) continue;
            if (type == 1) loadSegments.add(new long[]{vaddr, memSize, offset});
            if (type == 2) { dynamicOffset = offset; dynamicSize = fileSize; }
        }
        if (dynamicOffset < 0 || dynamicSize < 16) {
            String all = new String(bytes, StandardCharsets.ISO_8859_1);
            return all.contains("libpairipcore.so");
        }

        List<Long> needed = new ArrayList<>();
        for (long cursor = dynamicOffset; cursor + 16 <= dynamicOffset + dynamicSize; cursor += 16) {
            int p = (int) cursor;
            long tag = data.getLong(p);
            long value = data.getLong(p + 8);
            if (tag == 0) break;
            if (tag == 1) needed.add(value);
            if (tag == 5) stringTableAddress = value;
        }

        if (stringTableAddress >= 0) {
            long stringOffset = -1;
            for (long[] segment : loadSegments) {
                long vaddr = segment[0], memSize = segment[1], offset = segment[2];
                if (stringTableAddress >= vaddr && stringTableAddress - vaddr < memSize) {
                    stringOffset = offset + (stringTableAddress - vaddr);
                    break;
                }
            }
            if (stringOffset >= 0 && stringOffset < bytes.length) {
                for (Long nameOffset : needed) {
                    long position = stringOffset + nameOffset;
                    if (position < 0 || position >= bytes.length) continue;
                    int end = (int) position;
                    while (end < bytes.length && bytes[end] != 0) end++;
                    String name = new String(bytes, (int) position,
                            end - (int) position, StandardCharsets.UTF_8);
                    if ("libpairipcore.so".equals(name)) return true;
                }
            }
        }
        // Conservative fallback for unusual ELF layouts.
        return new String(bytes, StandardCharsets.ISO_8859_1).contains("libpairipcore.so");
    }

    private static String safeMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
