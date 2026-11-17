package io.github.xiaoaiswitch.core;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ControlProtocolTest {
    @Test public void requestKeepsConfiguredModelSystemPromptAndVisualState() {
        Profile profile = Profile.parse(new JSONObject().put("model", "CONTROL_MODEL").put("systemPrompt", "CONTROL_SYSTEM"));
        JSONArray messages = ControlProtocol.initialMessages(profile, "打开设置", "data:image/jpeg;base64,AQID");
        JSONObject request = ControlProtocol.request(profile, messages);
        assertEquals("CONTROL_MODEL", request.getString("model"));
        assertEquals("CONTROL_SYSTEM", request.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("打开设置", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals("data:image/jpeg;base64,AQID", messages.getJSONObject(1).getJSONArray("content")
                .getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertEquals(6, request.getJSONArray("tools").length());
    }

    @Test public void parsesTypedToolActions() {
        assertEquals(List.of(new ControlProtocol.OpenApp("CALL", "com.android.settings")),
                ControlProtocol.actions(response("open_app", new JSONObject().put("package", "com.android.settings"))));
        assertEquals(List.of(new ControlProtocol.Tap("CALL", 0.1, 0.2, 100)),
                ControlProtocol.actions(response("tap", new JSONObject().put("x", 0.1).put("y", 0.2))));
        assertEquals(List.of(new ControlProtocol.Swipe("CALL", 0.1, 0.2, 0.8, 0.9, 350)),
                ControlProtocol.actions(response("swipe", new JSONObject().put("x", 0.1).put("y", 0.2).put("x2", 0.8).put("y2", 0.9))));
        assertEquals(List.of(new ControlProtocol.TypeText("CALL", "中文输入")),
                ControlProtocol.actions(response("type_text", new JSONObject().put("text", "中文输入"))));
        assertEquals(List.of(new ControlProtocol.PressKey("CALL", "back")),
                ControlProtocol.actions(response("press_key", new JSONObject().put("key", "back"))));
        assertEquals(List.of(new ControlProtocol.Screenshot("CALL")), ControlProtocol.actions(response("screenshot", new JSONObject())));
    }

    @Test public void rejectsUnrecognizedActionsAndInvalidCoordinatesAtBoundary() {
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("shell", new JSONObject())));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("tap", new JSONObject().put("x", -1).put("y", 0))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("tap", new JSONObject().put("x", "0.5").put("y", 0))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("tap", new JSONObject().put("x", 0).put("y", 1.01))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("tap", new JSONObject().put("x", 0).put("y", 0).put("duration_ms", 99999))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("press_key", new JSONObject().put("key", "unknown"))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("open_app", new JSONObject().put("package", "not a package"))));
    }

    @Test public void distinguishesFinalTextFromCallsAndKeepsToolCorrelation() {
        assertTrue(ControlProtocol.actions(new JSONObject("{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}")).isEmpty());
        assertEquals("CALL", ControlProtocol.toolResult("CALL", new JSONObject().put("ok", true)).getString("tool_call_id"));
        assertEquals("tool", ControlProtocol.toolResult("CALL", new JSONObject()).getString("role"));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(new JSONObject().put("error", "provider failure")));
    }

    @Test public void supportsTextOnlyInitialStateAndValidExplicitDurations() {
        assertEquals(1, ControlProtocol.initialMessages(Profile.parse(new JSONObject()), "任务", "").length());
        assertEquals(List.of(new ControlProtocol.Tap("CALL", 0, 1, 500)),
                ControlProtocol.actions(response("tap", new JSONObject().put("x", 0).put("y", 1).put("duration_ms", 500))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("tap", new JSONObject().put("x", 0).put("y", 1).put("duration_ms", 1.5))));
        assertThrows(IllegalArgumentException.class, () -> ControlProtocol.actions(response("type_text", new JSONObject().put("text", ""))));
    }

    private JSONObject response(String name, JSONObject arguments) {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject()
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "CALL").put("type", "function")
                        .put("function", new JSONObject().put("name", name).put("arguments", arguments.toString())))))));
    }
}
