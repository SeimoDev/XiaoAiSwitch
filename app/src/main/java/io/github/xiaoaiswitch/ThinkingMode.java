package io.github.xiaoaiswitch;

import de.robv.android.xposed.XposedHelpers;
import java.util.List;

/** Reads the target APK's real request mode; never infers mode from the user's words. */
final class ThinkingMode {
    private ThinkingMode() {}

    static boolean isThinking(Object event, ClassLoader loader, String dialogContext) {
        Object contexts = event == null ? null : XposedHelpers.callMethod(event, "getContexts");
        if (contexts instanceof List<?> list) {
            for (Object context : list) {
                if (!"Dialog.DialogState".equals(XposedHelpers.callMethod(context, "getFullName"))) continue;
                Boolean selected = flag(XposedHelpers.callMethod(context, "getPayload"));
                if (selected != null) return selected;
            }
        }
        // Native duplex requests may upload DialogState separately. Its generator also
        // honors the one-shot "deep-think again" and "answer immediately" overrides.
        if (dialogContext == null) return false;
        Object context = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(dialogContext, loader), "generateDialogStateContext");
        return Boolean.TRUE.equals(flag(XposedHelpers.callMethod(context, "getPayload")));
    }

    private static Boolean flag(Object payload) {
        Object value = XposedHelpers.callMethod(payload, "isDeepseekSwitch");
        return Boolean.TRUE.equals(XposedHelpers.callMethod(value, "isPresent"))
                ? (Boolean) XposedHelpers.callMethod(value, "get") : null;
    }
}
