package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModelClientTest {
    private HttpServer server;
    private Profile profile;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        profile = Profile.parse(new JSONObject().put("enabled", true).put("model", "MODEL")
                .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").put("token", "TOKEN"));
        server.start();
    }

    @After public void stop() { server.stop(0); }

    @Test public void postsActualUtf8JsonAndAuthorization() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"text\":\"真实回答\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        assertEquals("真实回答", ModelClient.post(profile, profile.chatUrl(), new JSONObject().put("query", "中文问题")).getString("text"));
        assertEquals("Bearer TOKEN", auth.get());
        assertEquals("POST", method.get());
        assertEquals("中文问题", new JSONObject(body.get()).getString("query"));
    }

    @Test public void compatibleApiReceivesVisibleHistoryWithoutCodexPrivateMetadata() throws Exception {
        AtomicReference<JSONObject> received = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        JSONObject history = new JSONObject().put("model", "MODEL").put("messages", new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", "VISIBLE_HISTORY")
                        .put("codex_output", new JSONArray().put(new JSONObject().put("encrypted_content", "PRIVATE_METADATA")))
                        .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "CALL")))));
        ModelClient.chat(profile, history, new ModelClient.Cancellation());
        assertFalse(received.get().getJSONArray("messages").getJSONObject(0).has("codex_output"));
        assertEquals("VISIBLE_HISTORY", received.get().getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("CALL", received.get().getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertTrue(history.getJSONArray("messages").getJSONObject(0).has("codex_output"));
    }

    @Test public void discoversModelsWithProviderAuthorizationAndFullEndpointNormalization() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"data\":[{\"id\":\"MODEL_A\"},{\"id\":\"MODEL_B\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        Profile full = new Profile(false, profile.chatUrl(), "TOKEN", "", "", "openai_compatible");
        assertEquals(java.util.List.of("MODEL_A", "MODEL_B"), ModelClient.models(full));
        assertEquals("Bearer TOKEN", auth.get());
    }

    @Test public void doesNotSendAnEmptyBearerToken() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/empty", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        Profile empty = Profile.parse(new JSONObject());
        ModelClient.post(empty, profile.endpoint().replace("/v1", "/empty"), new JSONObject());
        assertNull(auth.get());
    }

    @Test public void httpErrorDoesNotExposeResponseSecrets() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(401, 5);
            exchange.getResponseBody().write("TOKEN".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        Exception error = assertThrows(java.io.IOException.class,
                () -> ModelClient.post(profile, profile.chatUrl(), new JSONObject()));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("TOKEN"));
    }

    @Test public void redirectIsNotFollowedAndMalformedJsonFails() {
        AtomicReference<Boolean> followed = new AtomicReference<>(false);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> { followed.set(true); exchange.close(); });
        assertThrows(java.io.IOException.class, () -> ModelClient.post(profile, profile.chatUrl(), new JSONObject()));
        assertFalse(followed.get());
        server.createContext("/invalid", exchange -> {
            exchange.sendResponseHeaders(200, 4);
            exchange.getResponseBody().write("oops".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        assertThrows(org.json.JSONException.class,
                () -> ModelClient.post(profile, profile.endpoint().replace("/v1", "/invalid"), new JSONObject()));
    }

    @Test public void oversizedResponseFailsBeforeParsing() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 16 * 1024 * 1024 + 1);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                byte[] block = new byte[8192];
                for (int index = 0; index < 2048; index++) output.write(block);
                output.write(0);
            }
            exchange.close();
        });
        assertThrows(java.io.IOException.class, () -> ModelClient.post(profile, profile.chatUrl(), new JSONObject()));
    }

    @Test public void cancelledRequestDoesNotReachServer() {
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ModelClient.post(profile, profile.chatUrl(), new JSONObject(), cancellation));
    }
}
