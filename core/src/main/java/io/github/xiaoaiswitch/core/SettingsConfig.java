package io.github.xiaoaiswitch.core;

import java.util.List;
import org.json.JSONObject;

/** Validates an editable UI draft only when it is committed to runtime configuration. */
public final class SettingsConfig {
    private SettingsConfig() {}
    public static final List<String> ROUTES = List.of("chat", "voice", "image", "control", "vision");

    public static JSONObject save(JSONObject draft, long revision) {
        JSONObject result = new JSONObject(draft.toString());
        result.put("enabled", flag(draft, "enabled", false)).put("autoRoute", flag(draft, "autoRoute", true));
        result.put("systemTools", flag(draft, "systemTools", false));
        String route = draft.optString("defaultRoute", "chat");
        if (!ROUTES.contains(route)) throw new IllegalArgumentException("请选择有效的默认通道");
        Object steps = draft.opt("controlSteps");
        if (steps != null && (!(steps instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 1)) {
            throw new IllegalArgumentException("控制调用上限需为 1 到 2147483647 的整数");
        }
        for (String name : ROUTES) {
            JSONObject channel = result.optJSONObject(name);
            if (channel == null) channel = new JSONObject();
            Profile profile = AiProviders.resolve(result, channel);
            channel.put("model", profile.model()).put("systemPrompt", profile.systemPrompt());
            if ((name.equals("chat") || name.equals("voice")) && channel.has("thinking")) {
                JSONObject thinking = channel.optJSONObject("thinking");
                if (thinking == null) throw new IllegalArgumentException("思考模式配置格式错误");
                Profile thought = AiProviders.resolve(result, thinking);
                thinking.put("model", thought.model()).put("systemPrompt", thought.systemPrompt());
            }
            result.put(name, channel);
        }
        return result.put("defaultRoute", route).put("controlSteps", steps == null ? 12 : ((Number) steps).intValue())
                .put("revision", revision);
    }

    private static boolean flag(JSONObject json, String key, boolean fallback) {
        Object value = json.opt(key);
        if (value == null) return fallback;
        if (value instanceof Boolean active) return active;
        throw new IllegalArgumentException(key + " 必须是布尔值");
    }
}
