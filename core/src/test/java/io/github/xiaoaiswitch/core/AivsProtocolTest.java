package io.github.xiaoaiswitch.core;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AivsProtocolTest {
    @Test public void queryPreludeUsesNativeQueryOperationWithOneBubblePerReply() {
        JSONArray before = AivsProtocol.queryPrelude("DIALOG_B", "用户输入原文");
        assertEquals(4, before.length());
        assertEquals("StartPreStream", before.getJSONObject(0).getJSONObject("header").getString("name"));
        assertEquals("ExpectStream", before.getJSONObject(1).getJSONObject("header").getString("name"));
        JSONObject query = before.getJSONObject(2);
        assertEquals("Template", query.getJSONObject("header").getString("namespace"));
        assertEquals("Query", query.getJSONObject("header").getString("name"));
        assertEquals("用户输入原文", query.getJSONObject("payload").getString("text"));
        assertEquals("FinishPreStream", before.getJSONObject(3).getJSONObject("header").getString("name"));
        for (int index = 0; index < before.length(); index++) {
            assertEquals("DIALOG_B", before.getJSONObject(index).getJSONObject("header").getString("dialog_id"));
        }
    }
    @Test public void streamingUsesNativeLifecycleAndOnlySendsEachDeltaOnce() {
        JSONArray start = AivsProtocol.streamStart("DIALOG");
        JSONArray first = AivsProtocol.streamDelta("DIALOG", "第一段");
        JSONArray second = AivsProtocol.streamDelta("DIALOG", "第二段");
        JSONArray finish = AivsProtocol.streamEnd("DIALOG", "问题", List.of());
        assertEquals("StartStream", start.getJSONObject(0).getJSONObject("header").getString("name"));
        assertEquals("EventACK", start.getJSONObject(1).getJSONObject("header").getString("name"));
        assertEquals("ToastStream", first.getJSONObject(0).getJSONObject("header").getString("name"));
        assertEquals("第一段", first.getJSONObject(0).getJSONObject("payload").getString("markdown_text"));
        assertEquals("第二段", second.getJSONObject(0).getJSONObject("payload").getString("markdown_text"));
        assertFalse(first.getJSONObject(0).getJSONObject("header").getString("id")
                .equals(second.getJSONObject(0).getJSONObject("header").getString("id")));
        assertEquals("FinishStream", finish.getJSONObject(0).getJSONObject("header").getString("name"));
        assertEquals("Dialog", finish.getJSONObject(1).getJSONObject("header").getString("namespace"));
        for (JSONArray batch : List.of(start, first, second, finish)) {
            for (int i = 0; i < batch.length(); i++) {
                assertEquals("DIALOG", batch.getJSONObject(i).getJSONObject("header").getString("dialog_id"));
            }
        }
        assertFalse(AivsProtocol.streamDelta("DIALOG", "<FINAL>").getJSONObject(0).getJSONObject("payload")
                .getString("markdown_text").equals("<FINAL>"));
        assertEquals("ImageContentGallery", AivsProtocol.streamEnd("DIALOG", "问题", List.of("https://HOST/image.png"))
                .getJSONObject(1).getJSONObject("header").getString("name"));
    }
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
