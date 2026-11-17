package io.github.xiaoaiswitch.core;

import org.json.JSONObject;

/** Independent default/thinking configurations for dialogue channels, with legacy fallback. */
public final class ModeProfiles {
    private ModeProfiles() {}

    public static Profile resolve(JSONObject config, String route, boolean thinking) {
        JSONObject normal = config.optJSONObject(route);
        if (normal == null) return Profile.parse(new JSONObject());
        if (thinking && (route.equals("chat") || route.equals("voice"))) {
            JSONObject override = normal.optJSONObject("thinking");
            if (override != null) {
                Profile profile = AiProviders.resolve(config, override);
                if (profile.enabled()) return profile;
            }
        }
        return AiProviders.resolve(config, normal);
    }
}
