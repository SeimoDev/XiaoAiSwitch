package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImageEditTest {
    @Test public void endpointsNormalizeGenerationAndEditingPaths() {
        for (String endpoint : List.of("https://HOST/v1/", "https://HOST/v1/images/generations", "https://HOST/v1/images/edits")) {
            Profile profile = Profile.parse(new JSONObject().put("endpoint", endpoint));
            assertEquals("https://HOST/v1/images/generations", profile.imageUrl());
            assertEquals("https://HOST/v1/images/edits", profile.imageEditUrl());
        }
    }

    @Test public void sendsActualImageBytesModelPromptAndAuthorizationAsMultipart() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        server.createContext("/v1/images/edits", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] result = "{\"data\":[{\"url\":\"https://HOST/edited.png\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = Profile.parse(new JSONObject().put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .put("model", "EDIT_MODEL").put("token", "TOKEN").put("systemPrompt", "图片风格"));
            JSONObject result = ModelClient.edit(profile, "变蓝", List.of(new ModelClient.Image("ORIGINAL_BYTES".getBytes(StandardCharsets.UTF_8), "image/png")), new ModelClient.Cancellation());
            assertEquals("https://HOST/edited.png", result.getJSONArray("data").getJSONObject(0).getString("url"));
            assertTrue(type.get().startsWith("multipart/form-data; boundary="));
            assertEquals("Bearer TOKEN", auth.get());
            assertTrue(body.get().contains("name=\"model\"\r\n\r\nEDIT_MODEL"));
            assertTrue(body.get().contains("图片风格\n\n变蓝"));
            assertTrue(body.get().contains("name=\"image\"; filename=\"image0.png\""));
            assertTrue(body.get().contains("ORIGINAL_BYTES"));
        } finally {
            server.stop(0);
        }
    }
}
