package com.freedomloader.terraria;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class GameActivity extends Activity {
    private static final String TAG = "FreedomGameHost";

    private Context gameContext;
    private ClassLoader gameClassLoader;
    private Object unityPlayer;
    private Method getViewMethod;
    private Method injectEventMethod;
    private Method windowFocusMethod;
    private Method configMethod;
    private Method newIntentMethod;
    private Method onStartMethod;
    private Method onResumeMethod;
    private Method onPauseMethod;
    private Method onStopMethod;
    private Method destroyMethod;
    private Method lowMemoryMethod;

    // Fields/methods queried by this Terraria/UnityPlayerActivity build.
    public boolean MouseInside = false;
    public int MouseMode = 0;
    public final boolean[] PressedStates = new boolean[330];
    public Object mUnityPlayer;

    @Override
    protected void onCreate(Bundle state) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        GameRuntime.Result runtime = GameRuntime.prepare(this);
        if (!runtime.ready) {
            showFailure(runtime.message);
            return;
        }

        gameContext = runtime.gameContext;
        gameClassLoader = runtime.gameClassLoader;

        try {
            // Load the customer's installed game's own native libraries into this process.
            System.load(new java.io.File(runtime.libraryDirectory, "libmain.so").getAbsolutePath());
            System.load(new java.io.File(runtime.libraryDirectory, "libunity.so").getAbsolutePath());
            System.load(new java.io.File(runtime.libraryDirectory, "libil2cpp.so").getAbsolutePath());
            Log.i(TAG, "Terraria native libraries loaded: " + runtime.versionName);

            // This read-only IL2CPP probe waits for il2cpp_init and logs discovered signatures.
            System.loadLibrary("freedom_runtime_inspector");
            Log.i(TAG, "Freedom Loader in-process inspector loaded");

            Class<?> unityClass = gameClassLoader.loadClass("com.unity3d.player.UnityPlayer");
            unityPlayer = constructUnityPlayer(unityClass);
            mUnityPlayer = unityPlayer;

            cacheUnityMethods(unityClass);
            View view = getUnityView();
            if (view == null) throw new IllegalStateException("UnityPlayer did not provide a View");
            setContentView(view);
            view.requestFocus();
            Log.i(TAG, "UnityPlayer is running inside the Freedom Loader process");
        } catch (Throwable error) {
            Log.e(TAG, "Failed to host Terraria", error);
            showFailure("تعذر تشغيل Terraria داخل Freedom Loader:\n" + describe(error));
        }
    }

    private Object constructUnityPlayer(Class<?> unityClass) throws Exception {
        Class<?> lifecycle = null;
        try {
            lifecycle = gameClassLoader.loadClass("com.unity3d.player.IUnityPlayerLifecycleEvents");
        } catch (ClassNotFoundException ignored) { }

        if (lifecycle != null) {
            final Class<?> lifecycleType = lifecycle;
            Object callbacks = Proxy.newProxyInstance(
                    gameClassLoader, new Class<?>[]{lifecycleType},
                    (proxy, method, args) -> {
                        Log.i(TAG, "Unity lifecycle: " + method.getName());
                        if ("onUnityPlayerQuitted".equals(method.getName())) {
                            runOnUiThread(this::finish);
                        }
                        return null;
                    });
            try {
                Constructor<?> constructor = unityClass.getConstructor(Context.class, lifecycleType);
                return constructor.newInstance(this, callbacks);
            } catch (NoSuchMethodException ignored) { }
        }

        Constructor<?> constructor = unityClass.getConstructor(Context.class);
        return constructor.newInstance(this);
    }

    private void cacheUnityMethods(Class<?> unityClass) {
        getViewMethod = findMethod(unityClass, "getView");
        injectEventMethod = findMethod(unityClass, "injectEvent", InputEvent.class);
        windowFocusMethod = findMethod(unityClass, "windowFocusChanged", boolean.class);
        configMethod = findMethod(unityClass, "configurationChanged", Configuration.class);
        newIntentMethod = findMethod(unityClass, "newIntent", android.content.Intent.class);
        onStartMethod = findMethod(unityClass, "onStart");
        onResumeMethod = findMethod(unityClass, "onResume");
        onPauseMethod = findMethod(unityClass, "onPause");
        onStopMethod = findMethod(unityClass, "onStop");
        destroyMethod = findMethod(unityClass, "destroy");
        lowMemoryMethod = findMethod(unityClass, "lowMemory");
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View getUnityView() throws Exception {
        if (getViewMethod != null) {
            Object value = getViewMethod.invoke(unityPlayer);
            if (value instanceof View) return (View) value;
        }
        return unityPlayer instanceof View ? (View) unityPlayer : null;
    }

    private void invoke(Method method, Object... args) {
        if (method == null || unityPlayer == null) return;
        try {
            method.invoke(unityPlayer, args);
        } catch (Throwable error) {
            Log.w(TAG, "Unity lifecycle call failed: " + error.getClass().getSimpleName());
        }
    }

    private boolean inject(InputEvent event) {
        if (injectEventMethod == null || unityPlayer == null) return false;
        try {
            Object result = injectEventMethod.invoke(unityPlayer, event);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable error) {
            Log.w(TAG, "Unity input forwarding failed", error);
            return false;
        }
    }

    private void showFailure(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setPadding(32, 32, 32, 32);
        view.setBackgroundColor(Color.rgb(25, 29, 37));
        view.setOnClickListener(v -> finish());
        setContentView(view);
    }

    private String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getClass().getSimpleName() +
                (cause.getMessage() == null ? "" : ": " + cause.getMessage()) +
                "\nاضغط لإغلاق شاشة اللعبة والعودة.";
    }

    public boolean IsKeyPressed(int code) {
        return code >= 0 && code < PressedStates.length && PressedStates[code];
    }

    public int GetUnityKeyCode(int androidKeyCode) {
        if (androidKeyCode >= KeyEvent.KEYCODE_A && androidKeyCode <= KeyEvent.KEYCODE_Z)
            return androidKeyCode - KeyEvent.KEYCODE_A + 'a';
        if (androidKeyCode >= KeyEvent.KEYCODE_1 && androidKeyCode <= KeyEvent.KEYCODE_9)
            return androidKeyCode - KeyEvent.KEYCODE_1 + '1';
        if (androidKeyCode >= KeyEvent.KEYCODE_F1 && androidKeyCode <= KeyEvent.KEYCODE_F12)
            return androidKeyCode - KeyEvent.KEYCODE_F1 + 282;
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK: return 27;
            case KeyEvent.KEYCODE_0: return '0';
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_ENTER: return 13;
            case KeyEvent.KEYCODE_DEL: return 8;
            case KeyEvent.KEYCODE_TAB: return 9;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 276;
            case KeyEvent.KEYCODE_DPAD_UP: return 273;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 275;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 274;
            default: return 0;
        }
    }

    @Override
    public AssetManager getAssets() {
        return gameContext == null ? super.getAssets() : gameContext.getAssets();
    }

    @Override
    public Resources getResources() {
        return gameContext == null ? super.getResources() : gameContext.getResources();
    }

    @Override
    public ClassLoader getClassLoader() {
        return gameClassLoader == null ? super.getClassLoader() : gameClassLoader;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return gameContext == null ? super.getApplicationInfo() : gameContext.getApplicationInfo();
    }

    @Override
    public String getPackageName() {
        return gameContext == null ? super.getPackageName() : gameContext.getPackageName();
    }

    @Override
    public String getPackageCodePath() {
        return gameContext == null ? super.getPackageCodePath() : gameContext.getPackageCodePath();
    }

    @Override
    public String getPackageResourcePath() {
        return gameContext == null ? super.getPackageResourcePath() : gameContext.getPackageResourcePath();
    }

    @Override
    protected void onStart() {
        super.onStart();
        invoke(onStartMethod);
    }

    @Override
    protected void onResume() {
        super.onResume();
        invoke(onResumeMethod);
    }

    @Override
    protected void onPause() {
        invoke(onPauseMethod);
        super.onPause();
    }

    @Override
    protected void onStop() {
        invoke(onStopMethod);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        invoke(destroyMethod);
        unityPlayer = null;
        mUnityPlayer = null;
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        invoke(lowMemoryMethod);
        super.onLowMemory();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        invoke(windowFocusMethod, hasFocus);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invoke(configMethod, newConfig);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        invoke(newIntentMethod, intent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() >= 0 && event.getKeyCode() < PressedStates.length) {
            PressedStates[event.getKeyCode()] = event.getAction() == KeyEvent.ACTION_DOWN;
        }
        return inject(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return inject(event) || super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return inject(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onBackPressed() {
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
        if (!inject(down) || !inject(up)) super.onBackPressed();
    }

    public SurfaceView getSurfaceView() {
        try {
            View view = getUnityView();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child instanceof SurfaceView) return (SurfaceView) child;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
