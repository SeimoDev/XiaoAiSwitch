package io.github.xiaoaiswitch.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SettingsConfigTest {
    @Test public void savesAllChannelsWithoutChangingSourceOrUnknownConfiguration() {
        JSONObject draft = new JSONObject().put("fixtureNonce", "LOCAL_NONCE").put("enabled", false)
                .put("chat", new JSONObject().put("enabled", true).put("provider", "codex_subscription")
                        .put("model", " CODEX_MODEL ").put("systemPrompt", "自定义指令"))
                .put("voice", new JSONObject().put("enabled", true).put("endpoint", "https://HOST/v1")
                        .put("token", "TOKEN").put("model", "VOICE_MODEL"));
        JSONObject saved = SettingsConfig.save(draft, 1234);
        assertFalse(saved.getBoolean("enabled"));
        assertTrue(saved.getBoolean("autoRoute"));
        assertEquals("chat", saved.getString("defaultRoute"));
        assertEquals(12, saved.getInt("controlSteps"));
        assertEquals(1234, saved.getLong("revision"));
        assertEquals("LOCAL_NONCE", saved.getString("fixtureNonce"));
        assertEquals("TOKEN", saved.getJSONObject("voice").getString("token"));
        assertEquals("CODEX_MODEL", saved.getJSONObject("chat").getString("model"));
        assertEquals("自定义指令", saved.getJSONObject("chat").getString("systemPrompt"));
        assertFalse(draft.has("revision"));
        assertEquals(" CODEX_MODEL ", draft.getJSONObject("chat").getString("model"));
    }

    @Test public void validatesInactiveAndActiveProfilesAtSaveBoundary() {
        JSONObject draft = new JSONObject().put("image", new JSONObject().put("enabled", true).put("model", "IMAGE"));
        assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(draft, 1));
        draft.getJSONObject("image").put("enabled", false);
        assertFalse(SettingsConfig.save(draft, 1).getJSONObject("image").getBoolean("enabled"));
        draft.put("enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(draft, 1));
    }

    @Test public void supportsSubscriptionImagesAndKeepsApiDraftWhenUsingSubscription() {
        JSONObject draft = new JSONObject().put("chat", new JSONObject().put("provider", "codex_subscription")
                .put("endpoint", "https://HOST/v1").put("token", "TOKEN"));
        JSONObject saved = SettingsConfig.save(draft, 1);
        assertEquals("https://HOST/v1", saved.getJSONObject("chat").getString("endpoint"));
        assertEquals(CodexProtocol.BASE_URL, Profile.parse(saved.getJSONObject("chat")).endpoint());
        draft.put("image", new JSONObject().put("provider", "codex_subscription").put("enabled", true).put("model", "gpt-image-2"));
        assertEquals("codex_subscription", SettingsConfig.save(draft, 1).getJSONObject("image").getString("provider"));
        assertEquals("gpt-image-2", SettingsConfig.save(draft, 1).getJSONObject("image").getString("model"));
    }

    @Test public void validatesDefaultRouteAndIntegerStepBudget() {
        assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(new JSONObject().put("defaultRoute", "unknown"), 1));
        for (Object steps : new Object[]{0, 31, 1.5, "2"}) {
            assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(new JSONObject().put("controlSteps", steps), 1));
        }
        assertEquals(1, SettingsConfig.save(new JSONObject().put("controlSteps", 1), 1).getInt("controlSteps"));
        assertEquals(30, SettingsConfig.save(new JSONObject().put("controlSteps", 30), 1).getInt("controlSteps"));
    }

    @Test public void savesIndependentThinkingProfileAndNormalizesItsModel() {
        JSONObject draft = new JSONObject().put("chat", new JSONObject().put("model", "NORMAL").put("systemPrompt", "DEFAULT_PROMPT")
                .put("thinking", new JSONObject().put("enabled", true).put("provider", "codex_subscription")
                        .put("model", " THINKING ").put("systemPrompt", "THINK_PROMPT")));
        JSONObject saved = SettingsConfig.save(draft, 1).getJSONObject("chat");
        assertEquals("NORMAL", saved.getString("model"));
        assertEquals("DEFAULT_PROMPT", saved.getString("systemPrompt"));
        assertEquals("THINKING", saved.getJSONObject("thinking").getString("model"));
        assertEquals("THINK_PROMPT", saved.getJSONObject("thinking").getString("systemPrompt"));
        assertEquals(" THINKING ", draft.getJSONObject("chat").getJSONObject("thinking").getString("model"));
    }

    @Test public void rejectsInvalidThinkingConfigurationWithoutRequiringItForLegacyProfiles() {
        JSONObject draft = new JSONObject().put("voice", new JSONObject().put("thinking", new JSONObject().put("enabled", true)));
        assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(draft, 1));
        draft.getJSONObject("voice").put("thinking", 1);
        assertThrows(IllegalArgumentException.class, () -> SettingsConfig.save(draft, 1));
        draft.getJSONObject("voice").put("thinking", new JSONObject().put("enabled", false));
        assertFalse(SettingsConfig.save(draft, 1).getJSONObject("voice").getJSONObject("thinking").getBoolean("enabled"));
        assertFalse(SettingsConfig.save(new JSONObject(), 1).getJSONObject("chat").has("thinking"));
    }
}
