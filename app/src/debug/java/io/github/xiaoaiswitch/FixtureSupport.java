package io.github.xiaoaiswitch;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import org.json.JSONObject;

/** Calls real production entrypoints for device tests; excluded from release. */
final class FixtureSupport {
    private FixtureSupport() {}

    static void install(Context context, ClassLoader loader, AssistantVersion adapter) {
        XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.SpeechQueryService", loader, "onStartCommand",
                Intent.class, int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent == null || !"io.github.xiaoaiswitch.FIXTURE".equals(intent.getAction())) return;
                Bundle data = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
                JSONObject config = new JSONObject(data == null ? "{}" : data.getString("json", "{}"));
                String nonce = config.optString("fixtureNonce", "");
                if (nonce.isEmpty() || !nonce.equals(intent.getStringExtra("nonce"))) return;
                param.setResult(2);
                String mode = intent.getStringExtra("mode");
                if ("clear".equals(mode)) {
                    Object speech = XposedHelpers.callStaticMethod(XposedHelpers.findClass(adapter.engine().session(), loader), "getInstance");
                    XposedHelpers.callMethod(speech, "clearSession");
                    return;
                }
                if ("thinking_mode".equals(mode)) {
                    Object helper = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(adapter.thinkingState(), loader), "a");
                    boolean before = Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isDeepThinking"));
                    if (intent.hasExtra("thinking")) {
                        XposedHelpers.callMethod(helper, "setDeepThinking", intent.getBooleanExtra("thinking", false), true);
                    }
                    JSONObject state = new JSONObject().put("request", intent.getStringExtra("request")).put("before", before)
                            .put("current", Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isDeepThinking")))
                            .put("effective", ThinkingMode.isThinking(null, loader, adapter.dialogContext()));
                    HookEntry.report(context, "THINKING_MODE_FIXTURE " + state);
                    return;
                }
                if (!"web_image".equals(mode) || !config.optBoolean("enabled", false)
                        || config.optJSONObject("image") == null || !config.getJSONObject("image").optBoolean("enabled", false)) return;
                Object producer = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(adapter.images().producer(), loader), "a");
                String id = java.util.UUID.randomUUID().toString();
                XposedHelpers.callMethod(producer, "saveImageLocalPath", id, intent.getStringExtra("path"));
                XposedHelpers.callMethod(producer, "sendWebImageGenEvent", intent.getStringExtra("query"), id, "{\"biz\":\"FIXTURE\",\"params\":{}}");
            }
        });
    }
}
