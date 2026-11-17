package io.github.xiaoaiswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import io.github.xiaoaiswitch.core.ControlViewport;

/** Reads display geometry without adding a window or changing system UI visibility. */
final class ControlDisplayViewport {
    private final Context context;
    private WindowManager windows;

    ControlDisplayViewport(Context context) { this.context = context; }

    static Bitmap crop(Bitmap bitmap, ControlViewport viewport) {
        return Bitmap.createBitmap(bitmap, viewport.left(), viewport.top(), viewport.width(), viewport.height());
    }

    ControlViewport read(Display display, int width, int height) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (windows == null) {
                    windows = context.createDisplayContext(display).createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).getSystemService(WindowManager.class);
                }
                WindowMetrics metrics = windows.getMaximumWindowMetrics();
                Rect bounds = metrics.getBounds();
                if (bounds.left != 0 || bounds.top != 0 || bounds.right != width || bounds.bottom != height) {
                    throw new SecurityException("系统内容区与默认屏幕尺寸不一致");
                }
                WindowInsets windowInsets = metrics.getWindowInsets();
                Insets bars = windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets gestures = windowInsets.getInsets(WindowInsets.Type.mandatorySystemGestures());
                return ControlViewport.fromDisplay(width, height, Math.max(bars.left, gestures.left),
                        Math.max(bars.top, gestures.top), Math.max(bars.right, gestures.right), Math.max(bars.bottom, gestures.bottom));
            }
            if (Build.VERSION.SDK_INT == 29) {
                Object manager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
                Rect stable = new Rect();
                Class.forName("android.view.IWindowManager").getMethod("getStableInsets", int.class, Rect.class)
                        .invoke(manager, display.getDisplayId(), stable);
                DisplayCutout cutout = display.getCutout();
                if (cutout == null) return ControlViewport.fromDisplay(width, height, stable.left, stable.top, stable.right, stable.bottom);
                return ControlViewport.fromDisplay(width, height, Math.max(stable.left, cutout.getSafeInsetLeft()),
                        Math.max(stable.top, cutout.getSafeInsetTop()), Math.max(stable.right, cutout.getSafeInsetRight()),
                        Math.max(stable.bottom, cutout.getSafeInsetBottom()));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (error instanceof SecurityException denied) throw denied;
            throw new SecurityException("系统内容区暂不可核验，已停止屏幕操作", error);
        }
        throw new SecurityException("本机系统没有提供可核验的内容区");
    }
}
