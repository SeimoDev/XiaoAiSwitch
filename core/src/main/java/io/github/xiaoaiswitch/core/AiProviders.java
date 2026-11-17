package io.github.xiaoaiswitch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared transports; model names, prompts and enabled state remain per use case. */
public final class AiProviders {
    private AiProviders() {}
    public record Entry(String id, String name, String provider, String endpoint, String token) {
        public JSONObject json() {
            return new JSONObject().put("name", name).put("provider", provider).put("endpoint", endpoint).put("token", token);
        }
        public Profile transport() { return new Profile(false, endpoint, token, "", "", provider); }
    }
    private record Transport(String provider, String endpoint, String token) {}

    public static List<Entry> entries(JSONObject config) {
        JSONObject registry = registry(config);
        JSONArray names = registry.names();
        if (names == null) return Collections.emptyList();
        List<Entry> result = new ArrayList<>();
        for (int index = 0; index < names.length(); index++) {
            String id = names.getString(index);
            result.add(entry(id, registry.getJSONObject(id)));
        }
        result.sort(Comparator.comparing(Entry::name).thenComparing(Entry::id));
        return result;
    }

    public static JSONObject normalize(JSONObject source) {
        JSONObject result = new JSONObject(source.toString());
        if (result.has("providers")) {
            registry(result);
            for (String route : SettingsConfig.ROUTES) {
                JSONObject normal = result.optJSONObject(route);
                if (normal == null) continue;
                clearInlineTransport(normal);
                JSONObject thinking = normal.optJSONObject("thinking");
                if ((route.equals("chat") || route.equals("voice")) && thinking != null) clearInlineTransport(thinking);
            }
            return result;
        }
        JSONObject registry = new JSONObject();
        Map<Transport, String> imported = new LinkedHashMap<>();
        for (String route : SettingsConfig.ROUTES) {
            JSONObject profile = result.optJSONObject(route);
            if (profile == null) continue;
            importProfile(profile, registry, imported);
            JSONObject thinking = profile.optJSONObject("thinking");
            if ((route.equals("chat") || route.equals("voice")) && thinking != null) importProfile(thinking, registry, imported);
        }
        return result.put("providers", registry);
    }

    public static Profile resolve(JSONObject config, JSONObject profile) {
        String id = profile.optString("providerId", "");
        if (id.isEmpty()) {
            if (config.has("providers") && profile.optBoolean("enabled")) throw new IllegalArgumentException("请先选择 AI Provider");
            return Profile.parse(profile);
        }
        JSONObject value = registry(config).optJSONObject(id);
        if (value == null) throw new IllegalArgumentException("AI Provider 已移除，请重新选择");
        Entry source = entry(id, value);
        return Profile.parse(new JSONObject(profile.toString()).put("provider", source.provider())
                .put("endpoint", source.endpoint()).put("token", source.token()));
    }

    public static JSONObject put(JSONObject config, String id, JSONObject value) {
        JSONObject result = normalize(config);
        Entry source = entry(id, value);
        if (source.provider().equals("openai_compatible")) Profile.requireEndpoint(source.endpoint());
        if (entries(result).stream().anyMatch(existing -> !existing.id().equals(id) && existing.name().equalsIgnoreCase(source.name()))) {
            throw new IllegalArgumentException("Provider 名称已存在，请换一个名称");
        }
        registry(result).put(id, source.json());
        return result;
    }

    public static boolean used(JSONObject config, String id) {
        for (String route : SettingsConfig.ROUTES) {
            JSONObject normal = config.optJSONObject(route);
            if (normal == null) continue;
            if (normal.optBoolean("enabled") && id.equals(normal.optString("providerId"))) return true;
            JSONObject thinking = normal.optJSONObject("thinking");
            if (thinking != null && thinking.optBoolean("enabled") && id.equals(thinking.optString("providerId"))) return true;
        }
        return false;
    }

    public static JSONObject remove(JSONObject config, String id) {
        JSONObject result = normalize(config);
        if (used(result, id)) throw new IllegalArgumentException("请先为正在使用的模型配置更换 Provider");
        registry(result).remove(id);
        for (String route : SettingsConfig.ROUTES) {
            JSONObject normal = result.optJSONObject(route);
            if (normal == null) continue;
            if (id.equals(normal.optString("providerId"))) normal.remove("providerId");
            JSONObject thinking = normal.optJSONObject("thinking");
            if (thinking != null && id.equals(thinking.optString("providerId"))) thinking.remove("providerId");
        }
        return result;
    }

    private static void importProfile(JSONObject profile, JSONObject registry, Map<Transport, String> imported) {
        String kind = profile.optString("provider", "openai_compatible");
        String endpoint = kind.equals("codex_subscription") ? CodexProtocol.BASE_URL : profile.optString("endpoint", "").trim();
        String token = kind.equals("codex_subscription") ? "" : profile.optString("token", "");
        if (kind.equals("codex_subscription") && !profile.optString("token").isEmpty()) {
            String draft = profile.optString("endpoint", "").trim();
            importTransport(registry, imported, new Transport("openai_compatible", draft.equals(CodexProtocol.BASE_URL) ? "" : draft, profile.optString("token")));
        }
        if (kind.equals("codex_subscription") || !endpoint.isEmpty() || !token.isEmpty()) {
            profile.put("providerId", importTransport(registry, imported, new Transport(kind, endpoint, token)));
        }
        clearInlineTransport(profile);
    }

    private static String importTransport(JSONObject registry, Map<Transport, String> imported, Transport key) {
        String id = imported.get(key);
        if (id == null) {
            id = "imported-" + (imported.size() + 1);
            imported.put(key, id);
            registry.put(id, new Entry(id, key.provider().equals("codex_subscription") ? "Codex 订阅" : "API " + imported.size(),
                    key.provider(), key.endpoint(), key.token()).json());
        }
        return id;
    }

    private static void clearInlineTransport(JSONObject profile) {
        profile.remove("provider");
        profile.remove("endpoint");
        profile.remove("token");
    }

    private static Entry entry(String id, JSONObject value) {
        String name = Profile.string(value, "name").trim();
        String provider = value.has("provider") ? Profile.string(value, "provider") : "openai_compatible";
        if (name.isEmpty()) throw new IllegalArgumentException("请填写 Provider 名称");
        if (!provider.equals("openai_compatible") && !provider.equals("codex_subscription")) throw new IllegalArgumentException("未知 Provider 类型");
        return new Entry(id, name, provider, provider.equals("codex_subscription") ? CodexProtocol.BASE_URL : Profile.string(value, "endpoint").trim(),
                provider.equals("codex_subscription") ? "" : Profile.string(value, "token"));
    }

    private static JSONObject registry(JSONObject config) {
        if (!config.has("providers")) return new JSONObject();
        JSONObject result = config.optJSONObject("providers");
        if (result == null) throw new IllegalArgumentException("AI Provider 配置格式错误");
        return result;
    }
}
