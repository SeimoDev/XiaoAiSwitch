package io.github.xiaoaiswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;

/** Delivers a generated test image through Xiaomi's actual 8.2 attachment event producer. */
final class NativeVisionFixture {
    private NativeVisionFixture() {}

    static void send(Context context, ClassLoader loader, AssistantVersion adapter, String nonce, String query) {
        if (!adapter.images().producer().equals("sj1.b") || !nonce.matches("[0-9a-f]{32}")) return;
        String path = "/sdcard/Download/xasw-native-vision-" + nonce + ".png";
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) throw new IllegalStateException("Fixture bitmap was not readable");
        try {
            Object file = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.o", loader), path, bitmap,
                    Math.toIntExact(new File(path).length()), 100);
            Object input = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.q", loader), file);
            Object media = XposedHelpers.newInstance(XposedHelpers.findClass("com.miui.voiceassist.data.entities.MultimodalMediaEntity", loader),
                    "fixture-" + nonce, null);
            Object attachment = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.p", loader), path, path, null, media, "");
            Object output = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.r", loader), attachment);
            Object result = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.l", loader), 0, "");
            Object chain = XposedHelpers.newInstance(XposedHelpers.findClass("ap1.k", loader), input, output, result, null);
            Class<?> uploader = XposedHelpers.findClass("dp1.d3", loader);
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context);
            Object floatManager = XposedHelpers.callMethod(manager, "getFloatManager");
            XposedHelpers.callMethod(XposedHelpers.newInstance(uploader, floatManager), "setMultiModalChain", chain);
            Object mode = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.xiaomi.ai.api.Application$UploadMode", loader), "MANUAL");
            Object submode = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.xiaomi.ai.api.Application$UploadSubMode", loader), "UNKNOWN");
            XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(uploader, "I"), "sendImageUnderStandEvent", chain, query, null, mode, false, submode);
        } finally {
            bitmap.recycle();
        }
    }
}
