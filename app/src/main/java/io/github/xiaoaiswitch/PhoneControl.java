package io.github.xiaoaiswitch;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.ControlLoop;
import io.github.xiaoaiswitch.core.ControlProtocol;
import io.github.xiaoaiswitch.core.ControlViewport;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Uses the assistant's already-granted input/capture capabilities, not a shell command channel. */
final class PhoneControl implements ControlLoop.Environment, AutoCloseable {
    private record SavedWindow(View view, WindowManager.LayoutParams params) {}
    private record BoardInput(View view, int originalFlags) {}
    private static final int BLOCK_BOARD_INPUT = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

    private final Context context;
    private final ClassLoader loader;
    private final BooleanSupplier current;
    private final Consumer<String> report;
    private final Runnable stop;
    private final NativePhoneAccess nativeAccess;
    private final String windowUtilities;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<SavedWindow> windows = new ArrayList<>();
    private final List<BoardInput> boardInputs = new ArrayList<>();
    private Button button;
    private ControlStopNotification stopCard;
    private Object global;
    private StockControlEffect effect;
    private Object board;
    private View boardRoot;
    private boolean fullScreenMoved;
    private int width;
    private int height;

    PhoneControl(Context context, ClassLoader loader, AssistantVersion.ControlBackend mapping,
                 BooleanSupplier current, Consumer<String> report, Runnable stop) {
        this.context = context;
        this.loader = loader;
        this.current = current;
        this.report = report;
        this.stop = stop;
        nativeAccess = mapping == null ? null : new NativePhoneAccess(context, loader, mapping, this::check, report);
        windowUtilities = mapping == null ? "com.xiaomi.voiceassistant.utils.k8" : mapping.window();
        width = context.getResources().getDisplayMetrics().widthPixels;
        height = context.getResources().getDisplayMetrics().heightPixels;
    }

    static boolean available(Context context, ClassLoader loader, AssistantVersion.ControlBackend mapping) {
        return mapping == null || NativePhoneAccess.available(context, loader, mapping);
    }

    void begin() throws Exception {
        ui(() -> {
            check();
            if (nativeAccess == null) {
                global = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.view.WindowManagerGlobal", loader), "getInstance");
                hideWindows();
            } else {
                Object manager = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isFullScreenInForeground"))) {
                    Object full = XposedHelpers.callMethod(manager, "getFullScreenManager");
                    Object tabs = XposedHelpers.callMethod(full, "getTabHostManager");
                    Object current = XposedHelpers.callMethod(tabs, "getTabHostMainActivity");
                    if (!(current instanceof Activity activity) || !activity.hasWindowFocus()
                            || !activity.getClass().getName().equals("com.xiaomi.voiceassistant.TabHostMainActivity")
                            || !activity.moveTaskToBack(true)) {
                        throw new SecurityException("小爱全屏窗口尚未完成退场");
                    }
                    fullScreenMoved = true;
                }
                suspendBoard(manager);
            }
            effect = new StockControlEffect(context, loader, windowUtilities);
            try {
                effect.start();
            } catch (Throwable error) {
                effect.close();
                effect = null;
                Log.w("XiaoAiSwitch", "StockControlEffect start failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                report.accept("原厂控制光效启动失败：" + error.getClass().getSimpleName());
            }
            if (Build.VERSION.SDK_INT >= 33 && nativeAccess != null) {
                stopCard = ControlStopNotification.start(context, loader, this::stopFromUser);
                if (stopCard != null) return null;
            }
            attachStopButton();
            return null;
        });
        // OEM visibility changes are published on the next input-window transaction.
        if (nativeAccess != null) pause(650);
        if (Build.VERSION.SDK_INT >= 33 && stopCard != null) {
            ControlStopNotification card = stopCard;
            long until = SystemClock.uptimeMillis() + 400;
            while (!card.isPosted() && SystemClock.uptimeMillis() < until) pause(50);
            if (!card.isPosted()) ui(() -> {
                check();
                card.close();
                stopCard = null;
                attachStopButton();
                return null;
            });
        }
    }

    private void attachStopButton() {
        button = new Button(context);
        button.setText("停止控制");
        button.setContentDescription("停止自定义手机控制");
        button.setOnClickListener(view -> stopFromUser());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setTitle("XiaoAiSwitch Control Stop");
        params.x = 16;
        params.y = 80;
        context.getSystemService(WindowManager.class).addView(button, params);
    }

    private void stopFromUser() {
        if (closed.get() || !current.getAsBoolean()) return;
        try {
            stop.run();
        } finally {
            close();
        }
    }

    private boolean suspendBoard(Object manager) {
        Object floatManager = XposedHelpers.callMethod(manager, "getFloatManager");
        View root = (View) XposedHelpers.callMethod(floatManager, "getRootLayout");
        if (root == null || !root.isAttachedToWindow()) return false;
        boolean shown = root.isShown();
        if (shown) {
            XposedHelpers.callMethod(floatManager, "setRootViewVisible", false, false);
            board = floatManager;
            boardRoot = root;
        }
        // A new or reconfigured assistant root can keep an input window even after the view is hidden.
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams params)
                || !"voice_assist_root".contentEquals(params.getTitle())
                || (params.flags & BLOCK_BOARD_INPUT) == BLOCK_BOARD_INPUT) return shown;
        if (boardInputs.stream().noneMatch(saved -> saved.view() == root)) {
            boardInputs.add(new BoardInput(root, params.flags & BLOCK_BOARD_INPUT));
        }
        updateBoardInput(root, params, BLOCK_BOARD_INPUT);
        return true;
    }

    private void settleBoard() throws Exception {
        boolean changed = ui(() -> {
            check();
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
            return suspendBoard(manager);
        });
        if (changed) pause(120);
    }

    private void updateBoardInput(View root, WindowManager.LayoutParams params, int flags) {
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(params);
        copy.flags = (copy.flags & ~BLOCK_BOARD_INPUT) | flags;
        context.getSystemService(WindowManager.class).updateViewLayout(root, copy);
    }

    void progress(int step) {
        stopStatus("第 " + step + " 步");
    }

    void retrying(int attempt) {
        stopStatus("等待重试 " + attempt + "/" + ControlLoop.RETRY_LIMIT);
    }

    private void stopStatus(String status) {
        main.post(() -> {
            if (closed.get()) return;
            if (Build.VERSION.SDK_INT >= 33 && stopCard != null) {
                try {
                    stopCard.update(status);
                } catch (Exception error) {
                    Log.w("XiaoAiSwitch", "Phone stop notification update: " + error.getClass().getSimpleName());
                }
            }
            if (button != null) button.setText("停止控制 · " + status);
        });
    }

    void prepareResult() throws Exception {
        if (nativeAccess != null && !fullScreenMoved) return;
        ui(() -> {
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
            XposedHelpers.callMethod(manager, "startIdleState", "xiaoai_switch_control");
            return null;
        });
        pause(400);
    }

    @Override public String screenshot() throws Exception {
        check();
        if (nativeAccess == null) ui(() -> { hideWindows(); return null; });
        Bitmap bitmap;
        if (nativeAccess != null) {
            settleBoard();
            bitmap = nativeAccess.capture();
            if (bitmap == null) return "";
        }
        else {
            Object capture = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("c20.b", loader), "a");
            bitmap = (Bitmap) XposedHelpers.callMethod(capture, "takePlanBScreenShot");
        }
        if (bitmap == null) throw new ControlLoop.TransientRead("原厂截图接口暂未返回图像");
        width = bitmap.getWidth();
        height = bitmap.getHeight();
        double scale = Math.min(1, 1280.0 / Math.max(width, height));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)) throw new IllegalStateException("屏幕编码失败");
            return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
        }
    }

    @Override public JSONObject execute(ControlProtocol.Action action) throws Exception {
        check();
        if (nativeAccess == null) ui(() -> { hideWindows(); return null; });
        if (nativeAccess != null) {
            settleBoard();
            if (action instanceof ControlProtocol.OpenApp) nativeAccess.requireLaunch();
            else nativeAccess.requireAction();
        }
        else if (action instanceof ControlProtocol.Tap || action instanceof ControlProtocol.Swipe || action instanceof ControlProtocol.TypeText
                || action instanceof ControlProtocol.PressKey key && key.key().equals("back")) ensureInputPermission();
        if (action instanceof ControlProtocol.OpenApp open) {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(open.packageName());
            if (intent == null) return new JSONObject().put("ok", false).put("error", "app_not_launchable");
            if (nativeAccess != null) nativeAccess.requireLaunch();
            check();
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (nativeAccess != null) nativeAccess.opened(open.packageName());
        } else if (nativeAccess != null) {
            // The original permission sheet can resume the assistant window when it closes.
            settleBoard();
        }
        if (action instanceof ControlProtocol.Tap tap) gesture(tap.x(), tap.y(), tap.x(), tap.y(), tap.duration(), false);
        if (action instanceof ControlProtocol.Swipe swipe) gesture(swipe.x(), swipe.y(), swipe.x2(), swipe.y2(), swipe.duration(), true);
        if (action instanceof ControlProtocol.PressKey key) {
            int code = switch (key.key()) {
                case "back" -> KeyEvent.KEYCODE_BACK;
                case "home" -> KeyEvent.KEYCODE_HOME;
                case "recents" -> KeyEvent.KEYCODE_APP_SWITCH;
                case "enter" -> KeyEvent.KEYCODE_ENTER;
                default -> throw new IllegalArgumentException("未知导航键");
            };
            long now = SystemClock.uptimeMillis();
            inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD));
            inject(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD));
        }
        if (action instanceof ControlProtocol.TypeText text) {
            check();
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.xiaomi.aiinput.AIInputTextManager", loader), "getInstance", context);
            if (manager == null || XposedHelpers.callMethod(manager, "getAIInputTextClient") == null) {
                return new JSONObject().put("ok", false).put("error", "text_input_client_missing");
            }
            if (nativeAccess == null) XposedHelpers.callMethod(input(), "insertFocusText", text.text());
            else nativeAccess.text(text.text());
        }
        pause(600);
        return new JSONObject().put("ok", true).put("status", "submitted_verify_screen").put("width", width).put("height", height);
    }

    private void gesture(double x, double y, double x2, double y2, int duration, boolean swipe) throws Exception {
        // The stop control must not consume a model-directed tap underneath it.
        if (nativeAccess == null) ui(() -> { if (button != null) button.setVisibility(View.GONE); return null; });
        long down = SystemClock.uptimeMillis();
        boolean sent = false;
        boolean finished = false;
        try {
            motion(down, MotionEvent.ACTION_DOWN, x, y);
            sent = true;
            if (swipe) {
                int count = Math.max(1, duration / 16);
                for (int index = 1; index <= count; index++) {
                    pause(Math.max(1, duration / count));
                    double part = (double) index / count;
                    motion(down, MotionEvent.ACTION_MOVE, x + (x2 - x) * part, y + (y2 - y) * part);
                }
            }
            if (!swipe) pause(duration);
            motion(down, MotionEvent.ACTION_UP, x2, y2);
            finished = true;
        } finally {
            if (nativeAccess != null && sent && !finished) {
                try {
                    motion(down, MotionEvent.ACTION_CANCEL, x2, y2);
                } catch (SecurityException | CancellationException error) {
                    // Keep the original stop reason if the old gesture target is already gone.
                }
            }
            if (nativeAccess == null) {
                ui(() -> { if (!closed.get() && button != null) button.setVisibility(View.VISIBLE); return null; });
            }
        }
    }

    private void motion(long down, int action, double x, double y) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        ControlViewport.Point point = nativeAccess == null ? new ControlViewport.Point((float) (x * (width - 1)), (float) (y * (height - 1)))
                : nativeAccess.point(x, y);
        coords.x = point.x();
        coords.y = point.y();
        coords.pressure = 1;
        coords.size = 1;
        inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, new MotionEvent.PointerProperties[]{pointer},
                new MotionEvent.PointerCoords[]{coords}, 0, 0, 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
    }

    private Object input() { return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("m20.g0", loader), "a"); }

    private void inject(InputEvent event) {
        try {
            if (nativeAccess != null) {
                nativeAccess.inject(event);
                return;
            }
            check();
            XposedHelpers.callMethod(event, "setDisplayId", 0);
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.hardware.input.InputManager", loader), "getInstance");
            // WAIT_FOR_FINISH reports actual dispatch; the native helper's ASYNC true only means queue admission.
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "injectInputEvent", event, 2))) {
                throw new IllegalStateException("输入事件未被系统接受");
            }
        } finally {
            if (event instanceof MotionEvent motion) motion.recycle();
        }
    }

    private void ensureInputPermission() throws Exception {
        String target = context.getSystemService(ActivityManager.class).getRunningTasks(10).stream()
                .map(task -> task.topActivity).filter(activity -> activity != null && !activity.getPackageName().equals(context.getPackageName()))
                .map(activity -> activity.getPackageName()).findFirst().orElseThrow(() -> new IllegalStateException("未找到控制目标应用"));
        List<String> pair = Collections.singletonList(target + "~" + context.getPackageManager().getApplicationInfo(target, 0).uid);
        if (inputGranted(pair)) return;
        Object permission = XposedHelpers.callMethod(input(), "hasAccessScreenControlPermission", context, new String[]{target}, false, pair);
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(permission, "getSecond"))) throw new SecurityException("目标应用未开放屏幕控制");
        report.accept("等待系统确认对 " + target + " 的屏幕控制权限");
        long deadline = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < deadline) {
            check();
            if (inputGranted(pair)) { pause(300); return; }
            pause(300);
        }
        throw new SecurityException("屏幕控制权限尚未获得");
    }

    // MIUI's vendor service is not in the stock SDK @ServiceName constants.
    @SuppressLint("WrongConstant")
    private boolean inputGranted(List<String> pair) {
        Object granted = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("nn0.b$d", loader), "Granted");
        String code = String.valueOf(XposedHelpers.callMethod(granted, "getValue"));
        Map<?, ?> result = (Map<?, ?>) XposedHelpers.callMethod(context.getSystemService("security"),
                "accessAppsControlEnable", context.getPackageName(), Process.myUid(), pair);
        return !result.isEmpty() && result.values().stream().allMatch(value -> code.equals(String.valueOf(value)));
    }

    private void hideWindows() {
        List<?> views = new ArrayList<>((List<?>) XposedHelpers.getObjectField(global, "mViews"));
        for (Object value : views) {
            View view = (View) value;
            if (view == button || effect != null && effect.owns(view) || windows.stream().anyMatch(saved -> saved.view() == view)) continue;
            WindowManager.LayoutParams original = (WindowManager.LayoutParams) view.getLayoutParams();
            WindowManager.LayoutParams saved = new WindowManager.LayoutParams();
            saved.copyFrom(original);
            windows.add(new SavedWindow(view, saved));
            WindowManager.LayoutParams hidden = new WindowManager.LayoutParams();
            hidden.copyFrom(original);
            hidden.alpha = 0;
            hidden.flags = (hidden.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            XposedHelpers.callMethod(global, "updateViewLayout", view, hidden);
        }
    }

    private void pause(long milliseconds) throws InterruptedException {
        long until = SystemClock.uptimeMillis() + milliseconds;
        while (SystemClock.uptimeMillis() < until) {
            check();
            Thread.sleep(Math.max(1, Math.min(25, until - SystemClock.uptimeMillis())));
        }
    }

    private void check() { if (closed.get() || !current.getAsBoolean()) throw new CancellationException(); }

    private <T> T ui(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        main.post(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            ui(() -> {
                if (Build.VERSION.SDK_INT >= 33 && stopCard != null) {
                    try {
                        stopCard.close();
                    } catch (RuntimeException error) {
                        Log.w("XiaoAiSwitch", "Phone stop notification cleanup: " + error.getClass().getSimpleName());
                    }
                    stopCard = null;
                }
                try {
                    if (button != null && button.isAttachedToWindow()) context.getSystemService(WindowManager.class).removeView(button);
                } catch (RuntimeException error) {
                    Log.w("XiaoAiSwitch", "Phone stop button cleanup: " + error.getClass().getSimpleName());
                } finally {
                    button = null;
                }
                try {
                    if (effect != null) effect.close();
                    effect = null;
                } finally {
                    for (BoardInput saved : boardInputs) {
                        if (saved.view().isAttachedToWindow() && saved.view().getLayoutParams() instanceof WindowManager.LayoutParams params
                                && (params.flags & BLOCK_BOARD_INPUT) == BLOCK_BOARD_INPUT) {
                            updateBoardInput(saved.view(), params, saved.originalFlags());
                        }
                    }
                    boardInputs.clear();
                    if (board != null && boardRoot.isAttachedToWindow()
                            && XposedHelpers.callMethod(board, "getRootLayout") == boardRoot
                            && boardRoot.getVisibility() == View.GONE) {
                        XposedHelpers.callMethod(board, "setRootViewVisible", true, false);
                    }
                    for (SavedWindow saved : windows) {
                        if (saved.view().isAttachedToWindow()) XposedHelpers.callMethod(global, "updateViewLayout", saved.view(), saved.params());
                    }
                    windows.clear();
                }
                return null;
            });
        } catch (Exception error) {
            report.accept("控制界面恢复失败：" + error.getClass().getSimpleName());
        }
    }
}
