package io.github.xiaoaiswitch;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/** Uses the installed assistant's UIAgent PAG renderer and asset without taking over its singleton. */
final class StockControlEffect implements AutoCloseable {
    private final Context context;
    private final ClassLoader loader;
    private View view;

    StockControlEffect(Context context, ClassLoader loader) {
        this.context = context;
        this.loader = loader;
    }

    void start() {
        view = (View) XposedHelpers.newInstance(XposedHelpers.findClass("org.libpag.PAGImageView", loader), context);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setTag("XiaoAiSwitch Stock Control Effect");
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(view, "setPath", "assets://PAG/uiagent_progressing.pag"))) {
            throw new IllegalStateException("原厂控制光效素材未加载");
        }
        XposedHelpers.callMethod(view, "setRepeatCount", -1);
        XposedHelpers.callMethod(view, "setScaleMode", 1);
        int type = (Integer) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.xiaomi.voiceassistant.utils.k8", loader), "getWindowType");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // The native capture helper excludes this exact stock screen-mask layer role.
        params.setTitle("UIAgentScreenMask");
        XposedHelpers.setIntField(params, "miuiFlags", XposedHelpers.getIntField(params, "miuiFlags") | 32 | 64);
        XposedHelpers.callMethod(params, "setTrustedOverlay");
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        context.getSystemService(WindowManager.class).addView(view, params);
        XposedHelpers.callMethod(view, "play");
        Log.i("XiaoAiSwitch", "StockControlEffect started renderer=org.libpag.PAGImageView asset=PAG/uiagent_progressing.pag loaded=true playing="
                + XposedHelpers.callMethod(view, "isPlaying"));
    }

    boolean owns(View candidate) { return candidate == view; }

    @Override public void close() {
        View current = view;
        view = null;
        if (current == null) return;
        try {
            XposedHelpers.callMethod(current, "pause");
        } finally {
            if (current.isAttachedToWindow()) context.getSystemService(WindowManager.class).removeViewImmediate(current);
            Log.i("XiaoAiSwitch", "StockControlEffect stopped");
        }
    }
}
