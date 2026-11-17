package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CodexImagesTest {
    private final CodexOAuth.Tokens tokens = new CodexOAuth.Tokens("ACCESS", "REFRESH", "ID", "ACCOUNT", "", "", Long.MAX_VALUE);

    @Test public void generationUsesImageModelAndPromptPrefixWithoutChatMessages() {
        Profile profile = new Profile(true, CodexProtocol.BASE_URL, "IGNORED_API_TOKEN", "gpt-image-2", "IMAGE_SYSTEM", "codex_subscription");
        JSONObject body = CodexImages.request(profile, "画一只猫", List.of());
        assertEquals("gpt-image-2", body.getString("model"));
        assertEquals("IMAGE_SYSTEM\n\n画一只猫", body.getString("prompt"));
        assertEquals("auto", body.getString("quality"));
        assertEquals("auto", body.getString("size"));
        assertEquals("opaque", body.getString("background"));
        assertEquals(1, body.getInt("n"));
        assertFalse(body.has("images"));
        assertFalse(body.has("messages"));
    }

    @Test public void editUsesJsonInlineImagesWithExactOriginalBytesAndMimeTypes() {
        Profile profile = new Profile(true, CodexProtocol.BASE_URL, "", "gpt-image-2", "", "codex_subscription");
        JSONObject body = CodexImages.request(profile, "变成蓝色", List.of(
                new ModelClient.Image(new byte[]{1, 2, 3}, "image/png"), new ModelClient.Image(new byte[]{4, 5, 6}, "image/jpeg")));
        assertEquals("变成蓝色", body.getString("prompt"));
        assertEquals("data:image/png;base64,AQID", body.getJSONArray("images").getJSONObject(0).getString("image_url"));
        assertEquals("data:image/jpeg;base64,BAUG", body.getJSONArray("images").getJSONObject(1).getString("image_url"));
        assertThrows(IllegalArgumentException.class, () -> CodexImages.request(profile, "EDIT", Collections.nCopies(6,
                new ModelClient.Image(new byte[]{1}, "image/png"))));
    }

    @Test public void actualGenerationAndEditUseSubscriptionAuthAndOfficialJsonPaths() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JSONObject> request = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> account = new AtomicReference<>();
        AtomicReference<String> turn = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        server.createContext("/images/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            request.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            account.set(exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            turn.set(exchange.getRequestHeaders().getFirst("x-codex-image-turn-id"));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = "{\"data\":[{\"b64_json\":\"AQID\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "NOT_AN_API_KEY", "gpt-image-2", "", "codex_subscription");
            JSONObject result = CodexClient.images(profile, "DRAW", List.of(), tokens, new ModelClient.Cancellation());
            assertEquals("/images/generations", path.get());
            assertEquals("Bearer ACCESS", auth.get());
            assertEquals("ACCOUNT", account.get());
            assertNotNull(turn.get());
            assertEquals("application/json", type.get());
            assertEquals("data:image/png;base64,AQID", ImageProtocol.sources(result).get(0));
            CodexClient.images(profile, "EDIT", List.of(new ModelClient.Image(new byte[]{1, 2, 3}, "image/png")), tokens, new ModelClient.Cancellation());
            assertEquals("/images/edits", path.get());
            assertTrue(request.get().getJSONArray("images").getJSONObject(0).getString("image_url")
                    .endsWith(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        } finally { server.stop(0); }
    }

    @Test public void authorizationFailuresAndCancellationDoNotBecomeImageSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.start();
        try {
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "", "gpt-image-2", "", "codex_subscription");
            assertEquals(401, assertThrows(ModelClient.HttpError.class,
                    () -> CodexClient.images(profile, "DRAW", List.of(), tokens, new ModelClient.Cancellation())).status());
            ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
            cancellation.cancel();
            assertThrows(CancellationException.class, () -> CodexClient.images(profile, "DRAW", List.of(), tokens, cancellation));
        } finally { server.stop(0); }
    }
}
