package io.github.xiaoaiswitch.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModeProfilesTest {
    @Test public void legacyConfigurationUsesTheSameProfileForBothModes() {
        JSONObject config = new JSONObject().put("chat", base());
        assertEquals(Profile.parse(config.getJSONObject("chat")), ModeProfiles.resolve(config, "chat", false));
        assertEquals(ModeProfiles.resolve(config, "chat", false), ModeProfiles.resolve(config, "chat", true));
        assertFalse(ModeProfiles.resolve(new JSONObject(), "voice", true).enabled());
    }

    @Test public void thinkingHasIndependentProviderModelCredentialsAndPrompt() {
        JSONObject normal = base().put("thinking", new JSONObject().put("enabled", true).put("endpoint", "https://THINKING/v1")
                .put("token", "THINKING_TOKEN").put("model", "THINKING_MODEL").put("systemPrompt", "THINKING_SYSTEM"));
        JSONObject config = new JSONObject().put("chat", normal);
        Profile thinking = ModeProfiles.resolve(config, "chat", true);
        assertTrue(thinking.enabled());
        assertEquals("https://THINKING/v1", thinking.endpoint());
        assertEquals("THINKING_TOKEN", thinking.token());
        assertEquals("THINKING_MODEL", thinking.model());
        assertEquals("THINKING_SYSTEM", thinking.systemPrompt());
        assertEquals("DEFAULT_MODEL", ModeProfiles.resolve(config, "chat", false).model());
        assertEquals("DEFAULT_TOKEN", normal.getString("token"));
    }

    @Test public void aDisabledOverrideFallsBackToDefaultInsteadOfDisablingTheChannel() {
        JSONObject config = new JSONObject().put("chat", base().put("thinking", new JSONObject()
                .put("enabled", false).put("model", "UNUSED_MODEL")));
        assertEquals("DEFAULT_MODEL", ModeProfiles.resolve(config, "chat", true).model());
        assertTrue(ModeProfiles.resolve(config, "chat", true).enabled());
    }

    @Test public void voiceCanEnableOnlyThinkingAndDoesNotMergeDefaultSecrets() {
        JSONObject config = new JSONObject().put("voice", new JSONObject().put("enabled", false)
                .put("token", "DEFAULT_SECRET").put("thinking", new JSONObject().put("enabled", true)
                        .put("provider", "codex_subscription").put("model", "THINKING_MODEL")));
        assertFalse(ModeProfiles.resolve(config, "voice", false).enabled());
        Profile thinking = ModeProfiles.resolve(config, "voice", true);
        assertTrue(thinking.enabled());
        assertEquals("codex_subscription", thinking.provider());
        assertEquals(CodexProtocol.BASE_URL, thinking.endpoint());
        assertEquals("", thinking.token());
        assertEquals("", thinking.systemPrompt());
    }

    @Test public void thinkingDoesNotReplaceImageOrControlProfiles() {
        JSONObject config = new JSONObject().put("image", base().put("thinking", new JSONObject()
                .put("enabled", true).put("model", "NOT_AN_IMAGE_MODEL")))
                .put("control", base().put("thinking", new JSONObject().put("enabled", true).put("model", "NOT_A_CONTROL_MODEL")));
        assertEquals("DEFAULT_MODEL", ModeProfiles.resolve(config, "image", true).model());
        assertEquals("DEFAULT_MODEL", ModeProfiles.resolve(config, "control", true).model());
    }

    @Test public void activeThinkingConfigurationIsValidatedAtTheResolutionBoundary() {
        JSONObject config = new JSONObject().put("chat", base().put("thinking", new JSONObject()
                .put("enabled", true).put("endpoint", "https://THINKING/v1")));
        assertThrows(IllegalArgumentException.class, () -> ModeProfiles.resolve(config, "chat", true));
        assertEquals("DEFAULT_MODEL", ModeProfiles.resolve(config, "chat", false).model());
    }

    private JSONObject base() {
        return new JSONObject().put("enabled", true).put("endpoint", "https://DEFAULT/v1").put("token", "DEFAULT_TOKEN")
                .put("model", "DEFAULT_MODEL").put("systemPrompt", "DEFAULT_SYSTEM");
    }
}
