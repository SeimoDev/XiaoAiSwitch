package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ControlLoopTest {
    @Test public void actualHttpLoopCarriesToolResultAndUpdatedScreen() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JSONObject> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = (requests.size() == 1
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"OPEN\",\"type\":\"function\",\"function\":{\"name\":\"open_app\",\"arguments\":\"{\\\"package\\\":\\\"com.android.settings\\\"}\"}}]}}]}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已打开设置\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AtomicBoolean opened = new AtomicBoolean();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { return opened.get() ? "AFTER" : "BEFORE"; }
            @Override public JSONObject execute(ControlProtocol.Action action) {
                assertEquals(new ControlProtocol.OpenApp("OPEN", "com.android.settings"), action);
                opened.set(true);
                return new JSONObject().put("ok", true);
            }
        };
        try {
            Profile profile = Profile.parse(new JSONObject().put("model", "MODEL").put("systemPrompt", "SYSTEM")
                    .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
            assertEquals("已打开设置", ControlLoop.run(profile, "打开设置", environment, 3, () -> true,
                    new ModelClient.Cancellation(), ignored -> {}));
            assertTrue(opened.get());
            assertEquals(2, requests.size());
            JSONArray messages = requests.get(1).getJSONArray("messages");
            assertEquals("SYSTEM", messages.getJSONObject(0).getString("content"));
            assertEquals("OPEN", messages.getJSONObject(3).getString("tool_call_id"));
            assertTrue(new JSONObject(messages.getJSONObject(3).getString("content")).getBoolean("ok"));
            assertEquals("AFTER", messages.getJSONObject(4).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"));
        } finally {
            server.stop(0);
        }
    }

    @Test public void cancelledTaskDoesNotCaptureOrAct() {
        AtomicInteger calls = new AtomicInteger();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { calls.incrementAndGet(); return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) { calls.incrementAndGet(); return new JSONObject(); }
        };
        assertThrows(CancellationException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                2, () -> false, new ModelClient.Cancellation(), ignored -> {}));
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                0, () -> true, new ModelClient.Cancellation(), ignored -> {}));
    }

    @Test public void boundedLoopStopsEvenWhenModelKeepsCallingTools() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"SCREEN\",\"function\":{\"name\":\"screenshot\",\"arguments\":\"{}\"}}]}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = Profile.parse(new JSONObject().put("model", "MODEL")
                    .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
            ControlLoop.Environment environment = new ControlLoop.Environment() {
                @Override public String screenshot() { return "screen"; }
                @Override public JSONObject execute(ControlProtocol.Action action) { return new JSONObject().put("ok", true); }
            };
            assertThrows(ControlLoop.StepLimit.class, () -> ControlLoop.run(profile, "task", environment, 1,
                    () -> true, new ModelClient.Cancellation(), ignored -> {}));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
