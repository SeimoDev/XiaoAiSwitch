package io.github.xiaoaiswitch;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Only the explicitly mapped default-display UIAgent path; no unrestricted or asynchronous input fallback. */
final class NativePhoneAccess {
    private static final String INJECT_EVENTS = "android.permission.INJECT_EVENTS";
    private static final int DISPLAY = Display.DEFAULT_DISPLAY;
    private record Target(String pkg, int uid, int task, int rotation, int width, int height) {}

    private final Context context;
    private final ClassLoader loader;
    private final AssistantVersion.ControlBackend mapping;
    private final Runnable check;
    private final Consumer<String> report;
    private Target snapshot;
    private String launched;

    NativePhoneAccess(Context context, ClassLoader loader, AssistantVersion.ControlBackend mapping,
                      Runnable check, Consumer<String> report) {
        this.context = context;
        this.loader = loader;
        this.mapping = mapping;
        this.check = check;
        this.report = report;
    }

    static boolean available(Context context, ClassLoader loader, AssistantVersion.ControlBackend mapping) {
        if (Build.VERSION.SDK_INT < 29) return false;
        if (context.checkSelfPermission(INJECT_EVENTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("XiaoAiSwitch", "8.2 control: platform input grant absent");
            return false;
        }
        try {
            Class<?> permissions = XposedHelpers.findClass(mapping.permissions(), loader);
            Class<?> scope = XposedHelpers.findClass(mapping.scope(), loader);
            Class<?> callback = XposedHelpers.findClass(mapping.callback(), loader);
            Class<?> result = XposedHelpers.findMethodExact(permissions, "hasAccessScreenContentPermission",
                    Context.class, scope, List.class, boolean.class, boolean.class, callback).getReturnType();
            Class<?> board = XposedHelpers.findMethodExact("com.xiaomi.voiceassistant.UiManager", loader, "getFloatManager").getReturnType();
            return callback.isInterface()
                    && XposedHelpers.findMethodExact(callback, "accept", Object.class).getReturnType() == void.class
                    && result == XposedHelpers.findMethodExact(permissions, "hasAccessScreenControlPermission",
                    Context.class, List.class, boolean.class, boolean.class, callback).getReturnType()
                    && XposedHelpers.findMethodExact(result, "getBool").getReturnType() == boolean.class
                    && XposedHelpers.findMethodExact(result, "getCode").getReturnType().isEnum()
                    && XposedHelpers.findMethodExact(mapping.tasks(), loader, "getTopTaskOfDisplay", int.class)
                    .getReturnType() == ActivityManager.RunningTaskInfo.class
                    && XposedHelpers.findMethodExact(mapping.capture(), loader, "takeScreenshotAndExcludeSelf",
                    int.class, int.class, Display.class, boolean.class, boolean.class).getReturnType() == Bitmap.class
                    && XposedHelpers.findMethodExact(InputManager.class, "injectInputEvent",
                    InputEvent.class, int.class, int.class).getReturnType() == boolean.class
                    && android.view.View.class.isAssignableFrom(XposedHelpers.findMethodExact(board, "getRootLayout").getReturnType())
                    && XposedHelpers.findMethodExact(board, "setRootViewVisible", boolean.class, boolean.class).getReturnType() == void.class
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(permissions, "a"), "isSupport", context));
        } catch (XposedHelpers.ClassNotFoundError | XposedHelpers.InvocationTargetError | LinkageError | RuntimeException error) {
            Log.w("XiaoAiSwitch", "8.2 control requirements: " + error.getClass().getSimpleName());
            return false;
        }
    }

    Bitmap capture() throws Exception {
        Target next = foreground();
        if (launched != null) {
            long until = SystemClock.uptimeMillis() + 5000;
            while (!next.pkg().equals(launched)) {
                if (SystemClock.uptimeMillis() >= until) {
                    throw new SecurityException("已请求打开目标应用，但它没有在 5 秒内进入前台");
                }
                Thread.sleep(100);
                next = foreground();
            }
            launched = null;
        } else if (snapshot != null && !snapshot.equals(next)) {
            throw new SecurityException("授权的前台窗口或显示尺寸已变化");
        }
        if (!permitted(next)) {
            if (snapshot == null) return null;
            throw new SecurityException("当前属于受保护的系统窗口，未读取或操作");
        }
        authorize(next);
        Bitmap bitmap = (Bitmap) XposedHelpers.callMethod(singleton(mapping.capture()), "takeScreenshotAndExcludeSelf",
                next.width(), next.height(), display(), true, false);
        if (bitmap == null) throw new SecurityException("原厂未返回屏幕内容");
        try {
            if (bitmap.getWidth() != next.width() || bitmap.getHeight() != next.height() || !next.equals(foreground())) {
                throw new SecurityException("屏幕和授权窗口不一致");
            }
            snapshot = next;
            return bitmap;
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    void requireAction() throws Exception {
        if (snapshot == null || !snapshot.equals(foreground())) throw new SecurityException("目标窗口已变化，控制已停止");
        authorize(snapshot);
    }

    void requireLaunch() {
        Target current = foreground();
        if (snapshot != null && !snapshot.equals(current)) throw new SecurityException("启动前前台窗口已变化，请重新发起");
    }

    void opened(String pkg) {
        snapshot = null;
        launched = pkg;
    }

    void inject(InputEvent event) {
        if (!(event instanceof MotionEvent motion) || motion.getActionMasked() != MotionEvent.ACTION_CANCEL) check.run();
        try {
            if (snapshot == null || context.checkSelfPermission(INJECT_EVENTS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("没有系统输入权限");
            }
            XposedHelpers.callMethod(event, "setDisplayId", DISPLAY);
            InputManager manager = context.getSystemService(InputManager.class);
            if (manager == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "injectInputEvent",
                    event, 2, snapshot.uid()))) { // WAIT_FOR_FINISH: requires the exact target UID.
                throw new SecurityException("触点没有命中正在授权操作的应用，已停止本次操作");
            }
        } catch (XposedHelpers.InvocationTargetError | LinkageError error) {
            throw new SecurityException("系统拒绝了这次输入，已停止本次操作", error);
        }
    }

    void text(String text) { XposedHelpers.callMethod(singleton(mapping.input()), "insertFocusText", text); }

    private Target foreground() {
        check.run();
        if (Build.VERSION.SDK_INT < 29) throw new SecurityException("系统版本不支持此控制方式");
        try {
            if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new SecurityException("请先解锁手机");
            Object task = XposedHelpers.callMethod(singleton(mapping.tasks()), "getTopTaskOfDisplay", DISPLAY);
            if (!(task instanceof ActivityManager.RunningTaskInfo info) || info.topActivity == null) {
                throw new SecurityException("系统未返回默认屏幕前台");
            }
            String pkg = info.topActivity.getPackageName();
            int uid = context.getPackageManager().getPackageUid(pkg, 0);
            Display display = display();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return new Target(pkg, uid, info.taskId, display.getRotation(),
                    metrics.widthPixels, metrics.heightPixels);
        } catch (PackageManager.NameNotFoundException | XposedHelpers.InvocationTargetError | XposedHelpers.ClassNotFoundError | LinkageError error) {
            throw new SecurityException("系统未返回默认屏幕前台", error);
        }
    }

    private boolean permitted(Target target) {
        return target.uid() >= android.os.Process.FIRST_APPLICATION_UID && !target.pkg().equals(context.getPackageName())
                && !target.pkg().equals("com.miui.securitycenter") && !target.pkg().equals("com.android.systemui");
    }

    private Display display() {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(DISPLAY);
        if (display == null || display.getState() != Display.STATE_ON) throw new SecurityException("默认屏幕不可用");
        return display;
    }

    private void authorize(Target target) throws Exception {
        request(true, target);
        if (!target.equals(foreground())) throw new SecurityException("授权期间前台窗口已变化");
        request(false, target);
        if (!target.equals(foreground())) throw new SecurityException("授权期间前台窗口已变化");
    }

    private void request(boolean content, Target target) throws Exception {
        check.run();
        String label = content ? "屏幕内容" : "屏幕操控";
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        Class<?> consumer = XposedHelpers.findClass(mapping.callback(), loader);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "XiaoAiSwitchPermissionCallback";
                case "accept":
                    try {
                        decision.complete(granted(args[0]));
                    } catch (XposedHelpers.InvocationTargetError | LinkageError | RuntimeException error) {
                        decision.complete(false);
                    }
                    return null;
                default: return null;
            }
        });
        Object result = permission(content, callback);
        if (granted(result)) return;
        Object code = XposedHelpers.callMethod(result, "getCode");
        if (!(code instanceof Enum<?> value) || !value.name().equals("NoPermission")) {
            throw new SecurityException("系统未授权" + label);
        }
        report.accept("等待系统确认" + label + "权限");
        long deadline = SystemClock.uptimeMillis() + 30000;
        while (!decision.isDone() && SystemClock.uptimeMillis() < deadline) {
            check.run();
            Thread.sleep(100);
        }
        if (!decision.getNow(false)) throw new SecurityException("系统未授权" + label);
        Thread.sleep(300);
        if (!target.equals(foreground())) throw new SecurityException("授权期间前台窗口已变化");
        // The callback might only confirm the OEM global switch.
        if (!granted(permission(content, null))) throw new SecurityException("请完成" + label + "授权后重新发起");
    }

    private Object permission(boolean content, Object callback) {
        try {
            Object permissions = singleton(mapping.permissions());
            // The OEM resolves every visible window UID for content and input checks.
            Object result = content ? XposedHelpers.callMethod(permissions, "hasAccessScreenContentPermission", context,
                    XposedHelpers.getStaticObjectField(XposedHelpers.findClass(mapping.scope(), loader), "VisibleWindow"),
                    null, false, false, callback)
                    : XposedHelpers.callMethod(permissions, "hasAccessScreenControlPermission", context, null, false, false, callback);
            if (result != null) return result;
        } catch (XposedHelpers.ClassNotFoundError | XposedHelpers.InvocationTargetError | LinkageError | RuntimeException error) {
            throw new SecurityException("原厂屏幕权限检查未完成", error);
        }
        throw new SecurityException("原厂屏幕权限检查未完成");
    }

    private static boolean granted(Object result) {
        return result != null && Boolean.TRUE.equals(XposedHelpers.callMethod(result, "getBool"))
                && XposedHelpers.callMethod(result, "getCode") instanceof Enum<?> code && code.name().equals("HasPermission");
    }

    private Object singleton(String type) { return XposedHelpers.getStaticObjectField(XposedHelpers.findClass(type, loader), "a"); }
}
