package io.github.xiaoaiswitch.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AiProvidersTest {
    @Test public void importsAndDeduplicatesLegacySourcesWithoutChangingModelsOrInput() {
        JSONObject api = new JSONObject().put("enabled", true).put("provider", "openai_compatible")
                .put("endpoint", "https://HOST/v1").put("token", "TOKEN").put("model", "DEFAULT").put("systemPrompt", "NORMAL_PROMPT");
        JSONObject source = new JSONObject().put("enabled", true).put("chat", api)
                .put("voice", new JSONObject(api.toString()).put("model", "VOICE"))
                .put("image", new JSONObject().put("enabled", true).put("provider", "codex_subscription").put("model", "gpt-image-2"));
        api.put("thinking", new JSONObject(api.toString()).put("model", "THINKING").put("systemPrompt", "THINK_PROMPT"));
        JSONObject config = AiProviders.normalize(source);
        assertEquals(2, AiProviders.entries(config).size());
        assertFalse(source.has("providers"));
        assertFalse(config.getJSONObject("chat").has("token"));
        assertFalse(config.getJSONObject("chat").has("endpoint"));
        assertFalse(config.getJSONObject("chat").has("provider"));
        assertEquals(config.getJSONObject("chat").getString("providerId"), config.getJSONObject("voice").getString("providerId"));
        assertEquals("DEFAULT", ModeProfiles.resolve(config, "chat", false).model());
        assertEquals("THINKING", ModeProfiles.resolve(config, "chat", true).model());
        assertEquals("THINK_PROMPT", ModeProfiles.resolve(config, "chat", true).systemPrompt());
        assertEquals("TOKEN", ModeProfiles.resolve(config, "voice", false).token());
        assertEquals("codex_subscription", ModeProfiles.resolve(config, "image", false).provider());
        assertEquals(config.toString(), AiProviders.normalize(config).toString());
    }

    @Test public void providerEditsApplyToEveryReferenceWithoutOverwritingModelSettings() {
        JSONObject config = AiProviders.put(new JSONObject(), "API", entry("My API", "openai_compatible", "https://HOST/v1", "TOKEN"));
        config.put("chat", new JSONObject().put("enabled", true).put("providerId", "API").put("model", "MODEL").put("systemPrompt", "SYSTEM")
                .put("endpoint", "https://STALE/v1").put("token", "STALE"));
        JSONObject changed = AiProviders.put(config, "API", entry("Renamed", "openai_compatible", "https://NEW-HOST/v1", "NEW_TOKEN"));
        assertEquals("https://NEW-HOST/v1", ModeProfiles.resolve(changed, "chat", false).endpoint());
        assertEquals("NEW_TOKEN", ModeProfiles.resolve(changed, "chat", false).token());
        assertEquals("MODEL", ModeProfiles.resolve(changed, "chat", false).model());
        assertEquals("SYSTEM", ModeProfiles.resolve(changed, "chat", false).systemPrompt());
        assertEquals("TOKEN", ModeProfiles.resolve(config, "chat", false).token());
    }

    @Test public void referencesKeepDefaultAndThinkingSourcesIndependent() {
        JSONObject config = AiProviders.put(new JSONObject(), "NORMAL", entry("Default", "openai_compatible", "https://HOST/v1", "NORMAL_TOKEN"));
        config = AiProviders.put(config, "THINK", entry("Thinking", "codex_subscription", "https://IGNORED", "IGNORED"));
        config.put("chat", new JSONObject().put("enabled", true).put("providerId", "NORMAL").put("model", "NORMAL")
                .put("thinking", new JSONObject().put("enabled", true).put("providerId", "THINK").put("model", "THINK")));
        assertEquals("NORMAL_TOKEN", ModeProfiles.resolve(config, "chat", false).token());
        assertEquals("", ModeProfiles.resolve(config, "chat", true).token());
        assertEquals(CodexProtocol.BASE_URL, ModeProfiles.resolve(config, "chat", true).endpoint());
        assertNotEquals(config.getJSONObject("chat").getString("providerId"), config.getJSONObject("chat").getJSONObject("thinking").getString("providerId"));
    }

    @Test public void validatesNamesKindsAndEndpointsAtProviderSave() {
        JSONObject config = AiProviders.put(new JSONObject(), "ONE", entry("Existing", "openai_compatible", "https://HOST/v1", "TOKEN"));
        assertThrows(IllegalArgumentException.class, () -> AiProviders.put(config, "TWO", entry("existing", "openai_compatible", "https://HOST/v1", "TOKEN")));
        assertThrows(IllegalArgumentException.class, () -> AiProviders.put(config, "TWO", entry("", "openai_compatible", "https://HOST/v1", "TOKEN")));
        assertThrows(IllegalArgumentException.class, () -> AiProviders.put(config, "TWO", entry("Unknown", "unknown", "https://HOST/v1", "TOKEN")));
        assertThrows(IllegalArgumentException.class, () -> AiProviders.put(config, "TWO", entry("Bad", "openai_compatible", "https://user:secret@HOST/v1?token=TOKEN", "TOKEN")));
        assertEquals(1, AiProviders.entries(config).size());
    }

    @Test public void deletingAnUnusedSourceClearsOnlyInactiveReferences() {
        JSONObject config = AiProviders.put(new JSONObject(), "ONE", entry("Source", "codex_subscription", "", ""));
        config.put("chat", new JSONObject().put("enabled", true).put("providerId", "ONE").put("model", "MODEL"));
        assertTrue(AiProviders.used(config, "ONE"));
        JSONObject active = config;
        assertThrows(IllegalArgumentException.class, () -> AiProviders.remove(active, "ONE"));
        config.getJSONObject("chat").put("enabled", false);
        JSONObject deleted = AiProviders.remove(config, "ONE");
        assertFalse(AiProviders.used(deleted, "ONE"));
        assertEquals(0, AiProviders.entries(deleted).size());
        assertFalse(deleted.getJSONObject("chat").has("providerId"));
        assertEquals("MODEL", deleted.getJSONObject("chat").getString("model"));
        assertEquals(1, AiProviders.entries(config).size());
    }

    @Test public void missingReferencesAreNotSilentlyReplacedByStaleInlineSecrets() {
        JSONObject config = new JSONObject().put("providers", new JSONObject());
        config.put("chat", new JSONObject().put("enabled", true).put("endpoint", "https://STALE/v1").put("token", "STALE").put("model", "MODEL"));
        assertThrows(IllegalArgumentException.class, () -> ModeProfiles.resolve(config, "chat", false));
        config.getJSONObject("chat").put("providerId", "MISSING");
        assertThrows(IllegalArgumentException.class, () -> ModeProfiles.resolve(config, "chat", false));
        config.getJSONObject("chat").remove("providerId");
        config.getJSONObject("chat").put("enabled", false);
        assertFalse(ModeProfiles.resolve(config, "chat", false).enabled());
        assertEquals(0, AiProviders.entries(AiProviders.normalize(config)).size());
    }

    @Test public void preservesLegacyApiDraftBesideSubscriptionInTheCentralRegistry() {
        JSONObject legacy = new JSONObject().put("chat", new JSONObject().put("provider", "codex_subscription")
                .put("endpoint", "https://HOST/v1").put("token", "OLD_API_KEY").put("model", "CODEX_MODEL"));
        JSONObject migrated = AiProviders.normalize(legacy);
        assertEquals(2, AiProviders.entries(migrated).size());
        assertTrue(AiProviders.entries(migrated).stream().anyMatch(value -> value.token().equals("OLD_API_KEY") && value.endpoint().equals("https://HOST/v1")));
        assertFalse(migrated.getJSONObject("chat").has("token"));
        assertEquals("codex_subscription", ModeProfiles.resolve(migrated, "chat", false).provider());
    }

    @Test public void deletionAlsoProtectsActiveReferencesDuringLegacyMigration() {
        JSONObject legacy = new JSONObject().put("chat", new JSONObject().put("enabled", true).put("provider", "codex_subscription").put("model", "MODEL"));
        String id = AiProviders.normalize(legacy).getJSONObject("chat").getString("providerId");
        assertThrows(IllegalArgumentException.class, () -> AiProviders.remove(legacy, id));
        assertFalse(legacy.has("providers"));
    }

    private static JSONObject entry(String name, String provider, String endpoint, String token) {
        return new JSONObject().put("name", name).put("provider", provider).put("endpoint", endpoint).put("token", token);
    }
}
