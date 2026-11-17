package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class CodexProtocolTest {
    @Test public void subscriptionProfileUsesPinnedEndpointAndKeepsApiProfilesCompatible() {
        Profile profile=Profile.parse(new JSONObject().put("provider","codex_subscription").put("enabled",true).put("model","CODEX_MODEL"));
        assertEquals("codex_subscription",profile.provider());
        assertEquals("https://chatgpt.com/backend-api/codex",profile.endpoint());
        assertEquals("openai_compatible",Profile.parse(new JSONObject()).provider());
        assertThrows(IllegalArgumentException.class,()->Profile.parse(new JSONObject().put("provider","unknown")));
    }

    @Test public void mapsSystemVisionAndFunctionHistoryToResponses() {
        JSONObject chat=new JSONObject().put("model","CODEX_MODEL").put("messages",new JSONArray()
                .put(ChatProtocol.message("system","MY_SYSTEM"))
                .put(ControlProtocol.visualMessage("看屏幕","data:image/jpeg;base64,AQID"))
                .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject()
                        .put("id","CALL").put("function",new JSONObject().put("name","tap").put("arguments","{\"x\":0.5,\"y\":0.5}")))))
                .put(ControlProtocol.toolResult("CALL",new JSONObject().put("ok",true))));
        chat.put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                .put("name","tap").put("description","点击").put("parameters",new JSONObject().put("type","object")))));
        JSONObject request=CodexProtocol.request(chat);
        assertEquals("MY_SYSTEM",request.getString("instructions"));
        assertFalse(request.getBoolean("store"));
        assertTrue(request.getBoolean("stream"));
        assertEquals("input_image",request.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"));
        assertEquals("function_call",request.getJSONArray("input").getJSONObject(1).getString("type"));
        assertEquals("CALL",request.getJSONArray("input").getJSONObject(2).getString("call_id"));
        assertEquals("tap",request.getJSONArray("tools").getJSONObject(0).getString("name"));
    }

    @Test public void convertsCompletedResponsesBackToExistingToolProtocol() {
        JSONObject response=new JSONObject().put("output",new JSONArray()
                .put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","收到"))))
                .put(new JSONObject().put("type","function_call").put("call_id","CALL").put("name","screenshot").put("arguments","{}")));
        JSONObject result=CodexProtocol.completion(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertEquals("收到",result.getString("content"));
        assertEquals("CALL",result.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
    }
}
