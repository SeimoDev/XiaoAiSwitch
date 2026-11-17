package io.github.xiaoaiswitch.core;

import java.net.URI;
import org.json.JSONObject;

public record Profile(boolean enabled, String endpoint, String token, String model, String systemPrompt, String provider) {
    public static Profile parse(JSONObject json) {
        Object active = json.opt("enabled");
        if (active != null && !(active instanceof Boolean)) {
            throw new IllegalArgumentException("enabled 必须是布尔值");
        }
        String provider = json.has("provider") ? string(json, "provider") : "openai_compatible";
        if (!provider.equals("openai_compatible") && !provider.equals("codex_subscription")) throw new IllegalArgumentException("未知 Provider");
        Profile profile = new Profile(Boolean.TRUE.equals(active), provider.equals("codex_subscription") ? CodexProtocol.BASE_URL : string(json, "endpoint").trim(),
                string(json, "token"), string(json, "model").trim(), string(json, "systemPrompt"), provider);
        if (profile.enabled()) {
            requireEndpoint(profile.endpoint());
            if (profile.model().isEmpty()) throw new IllegalArgumentException("请填写模型名称");
        }
        return profile;
    }

    public String chatUrl() {
        String base = endpoint.replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    public String imageUrl() {
        return imageBase() + "/images/generations";
    }

    public String imageEditUrl() { return imageBase() + "/images/edits"; }

    private String imageBase() { return endpoint.replaceAll("/+$", "").replaceFirst("/images/(generations|edits)$", ""); }

    static void requireEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("Provider Endpoint 需要 HTTP(S) 地址，密钥请填入独立字段");
        }
    }

    static String string(JSONObject json, String key) {
        Object value = json.opt(key);
        if (value == null) return "";
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(key + " 必须是字符串");
    }
}
