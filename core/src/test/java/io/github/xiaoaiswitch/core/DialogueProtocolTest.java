package io.github.xiaoaiswitch.core;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DialogueProtocolTest {
    @Test public void visionRequestPreservesEveryAttachedImageForCompatibleAndCodexProviders() {
        Profile profile = Profile.parse(new JSONObject().put("model", "VISION_MODEL").put("systemPrompt", "VISION_ONLY"));
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "比较两张图", List.of(
                "data:image/png;base64,AQID", "data:image/jpeg;base64,BAUG"), false, false);
        JSONArray messages = request.getJSONArray("messages");
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("VISION_ONLY", messages.getJSONObject(0).getString("content"));
        assertEquals("比较两张图", content.getJSONObject(0).getString("text"));
        assertEquals("data:image/jpeg;base64,BAUG", content.getJSONObject(2).getJSONObject("image_url").getString("url"));
        JSONArray codex = CodexProtocol.request(request).getJSONArray("input").getJSONObject(0).getJSONArray("content");
        assertEquals("input_image", codex.getJSONObject(1).getString("type"));
        assertEquals("data:image/jpeg;base64,BAUG", codex.getJSONObject(2).getString("image_url"));
    }

    private final Profile profile = Profile.parse(new JSONObject().put("model", "CHAT").put("systemPrompt", "MY_SYSTEM"));

    @Test public void delegatesUsingToolsWithoutReplacingConfiguredSystemPrompt() {
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "帮我画猫", "", true, true);
        assertEquals("MY_SYSTEM", request.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals(2, request.getJSONArray("tools").length());
        assertEquals("generate_image", request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));
        assertEquals("control_phone", request.getJSONArray("tools").getJSONObject(1).getJSONObject("function").getString("name"));
        assertFalse(DialogueProtocol.request(profile, new JSONArray(), "你好", "", false, false).has("tools"));
    }

    @Test public void addsOnlyEnabledSystemToolsAndKeepsUserPrompt() {
        DialogueProtocol.Tools system = new DialogueProtocol.Tools(false, false, true);
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "设一个五分钟计时", List.of(), system);
        String prompt = request.getJSONArray("messages").getJSONObject(0).getString("content");
        assertTrue(prompt.startsWith("MY_SYSTEM\n\n"));
        assertTrue(prompt.contains("start_timer"));
        assertTrue(prompt.contains("根据工具结果"));
        assertEquals(prompt, CodexProtocol.request(request).getString("instructions"));
        assertEquals(3, request.getJSONArray("tools").length());
        assertEquals("start_timer", request.getJSONArray("tools").getJSONObject(1).getJSONObject("function").getString("name"));
    }

    @Test public void parsesBoundedTypedSystemToolsAndRejectsUnexpectedParameters() {
        DialogueProtocol.Tools system = new DialogueProtocol.Tools(false, false, true);
        JSONObject timer = response("start_timer", new JSONObject().put("seconds", 300));
        assertEquals(new SystemTools.Timer(300, ""), DialogueProtocol.tasks(timer, system).get(0).systemAction());
        assertEquals(new SystemTools.Alarm(7, 30, "起床"), DialogueProtocol.tasks(response("set_alarm",
                new JSONObject().put("hour", 7).put("minute", 30).put("label", "起床")), system).get(0).systemAction());
        assertEquals(new SystemTools.Launch("时钟"), DialogueProtocol.tasks(response("launch_app",
                new JSONObject().put("app_name", " 时钟 ")), system).get(0).systemAction());
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(timer, false, false));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("set_alarm", new JSONObject().put("hour", 24).put("minute", 0)), system));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("start_timer", new JSONObject().put("seconds", 2.5)), system));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("start_timer", new JSONObject().put("seconds", 86401)), system));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("launch_app",
                new JSONObject().put("app_name", "时钟").put("uri", "https://example.com")), system));
    }

    @Test public void includesAttachedImageAsAnImagePartRatherThanAnOpaqueCloudFileId() {
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "改成蓝色", "data:image/png;base64,AQID", true, false);
        JSONArray content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("改成蓝色", content.getJSONObject(0).getString("text"));
        assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test public void validatesDelegationTargetsAndImageChoice() {
        assertEquals("image", DialogueProtocol.tasks(response("generate_image", new JSONObject().put("prompt", "猫").put("image", "input")), true, false).get(0).route());
        assertEquals("input", DialogueProtocol.tasks(response("generate_image", new JSONObject().put("prompt", "猫").put("image", "input")), true, false).get(0).image());
        assertEquals("control", DialogueProtocol.tasks(response("control_phone", new JSONObject().put("task", "打开设置")), false, true).get(0).route());
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("control_phone", new JSONObject().put("task", "打开设置")), true, false));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("shell", new JSONObject().put("task", "TARGET")), true, true));
        assertThrows(IllegalArgumentException.class, () -> DialogueProtocol.tasks(response("generate_image", new JSONObject().put("prompt", "猫").put("image", "file:///TARGET")), true, true));
        assertTrue(DialogueProtocol.tasks(new JSONObject("{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}"), true, true).isEmpty());
    }

    @Test public void historyTrimsOnUserBoundariesAndPreservesToolPairs() {
        JSONArray sent = new JSONArray().put(ChatProtocol.message("system", "SYSTEM"));
        for (int index = 0; index < 12; index++) {
            sent.put(ChatProtocol.message("user", "问" + index));
            sent.put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(new JSONObject().put("id", "CALL" + index))));
            sent.put(new JSONObject().put("role", "tool").put("tool_call_id", "CALL" + index).put("content", "完成"));
        }
        JSONArray history = ChatProtocol.history(new JSONObject().put("messages", sent), "最终回答");
        assertEquals("问2", history.getJSONObject(0).getString("content"));
        assertEquals("最终回答", history.getJSONObject(history.length() - 1).getString("content"));
        assertEquals(31, history.length());
    }

    static JSONObject response(String name, JSONObject arguments) {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("role", "assistant")
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "DELEGATE").put("type", "function")
                        .put("function", new JSONObject().put("name", name).put("arguments", arguments.toString())))))));
    }
}
