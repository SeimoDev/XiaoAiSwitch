package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ChatProtocolTest {
    private final Profile profile = Profile.parse(new JSONObject()
            .put("enabled", true).put("endpoint", "https://HOST/v1")
            .put("model", "MODEL").put("systemPrompt", "SYSTEM"));

    @Test public void promptIsSystemRoleAndHistoryIsPreserved() {
        JSONArray history = new JSONArray().put(new JSONObject().put("role", "user").put("content", "上一问"))
                .put(new JSONObject().put("role", "assistant").put("content", "上一答"));
        JSONObject request = ChatProtocol.request(profile, history, "当前问题");
        assertEquals("MODEL", request.getString("model"));
        assertFalse(request.getBoolean("stream"));
        assertEquals(4, request.getJSONArray("messages").length());
        assertEquals("system", request.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("SYSTEM", request.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("当前问题", request.getJSONArray("messages").getJSONObject(3).getString("content"));
        assertEquals(2, history.length());
    }

    @Test public void emptySystemPromptDoesNotInventOne() {
        Profile empty = Profile.parse(new JSONObject().put("model", "MODEL"));
        assertEquals(1, ChatProtocol.request(empty, new JSONArray(), "文本").getJSONArray("messages").length());
    }

    @Test public void readsTextAndContentParts() {
        assertEquals("回答", ChatProtocol.answer(new JSONObject("{\"choices\":[{\"message\":{\"content\":\"回答\"}}]}")));
        assertEquals("甲乙", ChatProtocol.answer(new JSONObject("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"甲\"},{\"type\":\"text\",\"text\":\"乙\"}]}}]}")));
    }

    @Test public void rejectsProviderErrorsAndEmptyAnswers() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatProtocol.answer(new JSONObject().put("error", new JSONObject().put("message", "TOKEN"))));
        assertThrows(IllegalArgumentException.class, () -> ChatProtocol.answer(new JSONObject().put("choices", new JSONArray())));
        assertThrows(IllegalArgumentException.class,
                () -> ChatProtocol.answer(new JSONObject("{\"choices\":[{\"message\":{\"content\":null}}]}")));
    }

    @Test public void imageRequestUsesImageModelAndPrompt() {
        JSONObject request = ChatProtocol.imageRequest(profile, "画一只猫");
        assertEquals("MODEL", request.getString("model"));
        assertEquals("SYSTEM\n\n画一只猫", request.getString("prompt"));
        assertEquals(1, request.getInt("n"));
    }
}
