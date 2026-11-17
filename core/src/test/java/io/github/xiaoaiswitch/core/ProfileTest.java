package io.github.xiaoaiswitch.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProfileTest {
    @Test public void disabledByDefault() {
        Profile profile = Profile.parse(new JSONObject());
        assertFalse(profile.enabled());
        assertEquals("", profile.model());
    }

    @Test public void readsExplicitSettingsWithoutChangingPrompt() {
        Profile profile = Profile.parse(new JSONObject()
                .put("enabled", true).put("endpoint", "https://HOST/v1/")
                .put("model", "MODEL").put("token", "TOKEN")
                .put("systemPrompt", "  中文\n第二行  "));
        assertEquals("https://HOST/v1/chat/completions", profile.chatUrl());
        assertEquals("https://HOST/v1/images/generations", profile.imageUrl());
        assertEquals("MODEL", profile.model());
        assertEquals("TOKEN", profile.token());
        assertEquals("  中文\n第二行  ", profile.systemPrompt());
        assertTrue(profile.enabled());
    }

    @Test public void fullChatEndpointIsNotDuplicated() {
        Profile profile = Profile.parse(new JSONObject().put("endpoint", "https://HOST/custom/chat/completions"));
        assertEquals("https://HOST/custom/chat/completions", profile.chatUrl());
    }

    @Test public void enabledProfileRequiresEndpointAndModel() {
        assertThrows(IllegalArgumentException.class,
                () -> Profile.parse(new JSONObject().put("enabled", true)));
        assertThrows(IllegalArgumentException.class,
                () -> Profile.parse(new JSONObject().put("enabled", true)
                        .put("endpoint", "file:///TARGET").put("model", "MODEL")));
        assertThrows(IllegalArgumentException.class,
                () -> Profile.parse(new JSONObject().put("enabled", true)
                        .put("endpoint", "https://HOST").put("model", " ")));
    }

    @Test public void localHttpIsSupportedAndWrongTypesAreRejected() {
        assertEquals("http://HOST:8000/v1/chat/completions", Profile.parse(new JSONObject()
                .put("enabled", true).put("endpoint", "http://HOST:8000/v1").put("model", "MODEL")).chatUrl());
        assertThrows(IllegalArgumentException.class,
                () -> Profile.parse(new JSONObject().put("enabled", "true")));
        assertThrows(IllegalArgumentException.class,
                () -> Profile.parse(new JSONObject().put("model", 4)));
    }
}
