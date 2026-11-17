package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DialogueLoopTest {
    @Test public void actualHttpDelegationReturnsToolResultBeforeFinalAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JSONObject> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            JSONObject response = requests.size() == 1 ? DialogueProtocolTest.response("generate_image", new JSONObject().put("prompt", "猫"))
                    : new JSONObject("{\"choices\":[{\"message\":{\"content\":\"画好了\"}}]}");
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = Profile.parse(new JSONObject().put("model", "PLANNER").put("systemPrompt", "PLANNER_SYSTEM")
                    .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
            JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "画猫", "", true, false);
            DialogueLoop.Result result = DialogueLoop.run(profile, request, true, false, task -> {
                assertEquals("image", task.route());
                assertEquals("猫", task.text());
                return new DialogueLoop.Result("已生成图片", List.of("https://HOST/image.png"));
            }, () -> true, new ModelClient.Cancellation());
            assertEquals("画好了", result.text());
            assertEquals(List.of("https://HOST/image.png"), result.images());
            JSONArray messages = requests.get(1).getJSONArray("messages");
            assertEquals("DELEGATE", messages.getJSONObject(3).getString("tool_call_id"));
            assertTrue(new JSONObject(messages.getJSONObject(3).getString("content")).getBoolean("ok"));
        } finally {
            server.stop(0);
        }
    }
}
