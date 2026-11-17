package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AivsProtocolTest {
    @Test public void responsePreservesDialogAndNativeLifecycle() {
        JSONArray response = AivsProtocol.textResponse("DIALOG", "问题", "回答");
        assertEquals(5, response.length());
        assertEquals("StartAnswer", response.getJSONObject(0).getJSONObject("header").getString("name"));
        assertEquals("EventACK", response.getJSONObject(1).getJSONObject("header").getString("name"));
        assertEquals(0, response.getJSONObject(1).getJSONObject("payload").getInt("status"));
        assertEquals("Toast", response.getJSONObject(2).getJSONObject("header").getString("name"));
        assertEquals("回答", response.getJSONObject(2).getJSONObject("payload").getString("text"));
        assertEquals("问题", response.getJSONObject(2).getJSONObject("payload").getString("query"));
        for (int index = 0; index < response.length(); index++) {
            JSONObject header = response.getJSONObject(index).getJSONObject("header");
            assertEquals("DIALOG", header.getString("dialog_id"));
            assertFalse(header.getString("id").isEmpty());
        }
    }

    @Test public void markdownOwnershipPersistsInToastIdWithoutChangingContentOrOtherInstructions() {
        String text = "# 标题\n\n**粗体**与列表\n- 第一项\n\n```java\nSystem.out.println(1);\n```";
        JSONArray response = AivsProtocol.textResponse("DIALOG", "QUERY", text);
        assertEquals(text, response.getJSONObject(2).getJSONObject("payload").getString("text"));
        for (int index = 0; index < response.length(); index++) {
            JSONObject header = response.getJSONObject(index).getJSONObject("header");
            String id = header.getString("id");
            if (index == 2) {
                assertTrue(id.startsWith("xiaoaiswitch-markdown-"));
                java.util.UUID.fromString(id.substring("xiaoaiswitch-markdown-".length()));
            } else {
                assertFalse(id.startsWith("xiaoaiswitch-markdown-"));
                java.util.UUID.fromString(id);
            }
            assertEquals("DIALOG", header.getString("dialog_id"));
        }
    }
}
