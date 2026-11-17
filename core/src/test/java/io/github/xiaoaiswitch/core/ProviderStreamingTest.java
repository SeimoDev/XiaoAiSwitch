package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProviderStreamingTest {
    @Test public void codexControlReadsToolResponseWithoutALiveTextObserver() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"temporary\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\","
                    + "\"call_id\":\"CHECK\",\"name\":\"screenshot\",\"arguments\":\"{}\"}]}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "", "MODEL", "CONTROL", "codex_subscription");
            JSONObject request = ControlProtocol.request(profile, ControlProtocol.initialMessages(profile, "inspect", ""));
            JSONObject answer = CodexClient.chat(profile, request,
                    new CodexOAuth.Tokens("ACCESS", "REFRESH", "ID", "ACCOUNT", "plus", "", Long.MAX_VALUE),
                    new ModelClient.Cancellation(), null);
            assertEquals(List.of(new ControlProtocol.Screenshot("CHECK")), ControlProtocol.actions(answer));
        } finally { server.stop(0); }
    }

    @Test public void compatibleHttpEmitsBeforeServerCompletesAndRequestsRealStreaming() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch firstDelta = new CountDownLatch(1);
        AtomicBoolean incremental = new AtomicBoolean();
        AtomicReference<JSONObject> request = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            request.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk("你好", null).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { incremental.set(firstDelta.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.getResponseBody().write((chunk("世界", "stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            List<String> deltas = new ArrayList<>();
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "TOKEN", "MODEL", "", "openai_compatible");
            JSONObject original = ChatProtocol.request(profile, new JSONArray(), "问题");
            original.getJSONArray("messages").getJSONObject(0).put("codex_output", new JSONArray().put("PRIVATE_HISTORY"));
            JSONObject result = ModelClient.chat(profile, original, new ModelClient.Cancellation(), delta -> {
                deltas.add(delta);
                firstDelta.countDown();
            });
            assertTrue(incremental.get());
            assertEquals(List.of("你好", "世界"), deltas);
            assertEquals("你好世界", ChatProtocol.answer(result));
            assertTrue(request.get().getBoolean("stream"));
            assertEquals("text/event-stream", accept.get());
            assertFalse(request.get().getJSONArray("messages").getJSONObject(0).has("codex_output"));
            assertFalse(original.getBoolean("stream"));
            assertTrue(original.getJSONArray("messages").getJSONObject(0).has("codex_output"));
        } finally { server.stop(0); }
    }

    @Test public void compatibleFragmentsPreserveToolCallsUsageAndFinishReason() throws Exception {
        String events = "\ufeff: heartbeat\r\nevent: message\r\ndata: {\"id\":\"completion-id\",\"model\":\"MODEL\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"准备\",\"tool_calls\":[{\"index\":0,\"id\":\"CALL\",\"type\":\"function\",\"function\":{\"name\":\"open_\",\"arguments\":\"{\\\"app_\"}}]}}]}\r\n\r\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"app\",\"arguments\":\"name\\\":\\\"设置\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"total_tokens\":12}}\n\ndata: [DONE]\n\n";
        List<String> deltas = new ArrayList<>();
        JSONObject result = ModelClient.readEvents(reader(events), new ModelClient.Cancellation(), deltas::add);
        JSONObject first = result.getJSONArray("choices").getJSONObject(0);
        JSONObject call = first.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0);
        assertEquals(List.of("准备"), deltas);
        assertEquals("tool_calls", first.getString("finish_reason"));
        assertEquals("CALL", call.getString("id"));
        assertEquals("open_app", call.getJSONObject("function").getString("name"));
        assertEquals("设置", new JSONObject(call.getJSONObject("function").getString("arguments")).getString("app_name"));
        assertEquals(12, result.getJSONObject("usage").getInt("total_tokens"));
        assertEquals("completion-id", result.getString("id"));
    }

    @Test public void compatibleMultilineFramesAndIndexedChoicesKeepPrimaryDeltasSeparate() throws Exception {
        String events = "data:\n\nid: ignored\ndata: {\"choices\":[\ndata: {\"index\":1,\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"},\ndata: {\"index\":0,\"delta\":{\"content\":\"first\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n";
        List<String> deltas = new ArrayList<>();
        JSONObject result = ModelClient.readEvents(reader(events), new ModelClient.Cancellation(), deltas::add);
        assertEquals(List.of("first"), deltas);
        assertEquals("first", ChatProtocol.answer(result));
        assertEquals("length", result.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
        assertEquals("second", result.getJSONArray("choices").getJSONObject(1).getJSONObject("message").getString("content"));
    }

    @Test public void compatibleInterleavedToolsAndStructuredTextRetainTheirIndexesAndJsonBytes() throws Exception {
        JSONArray firstTools = new JSONArray().put(new JSONObject().put("index", 1).put("id", "SECOND").put("function", new JSONObject().put("name", "two").put("arguments", "[")))
                .put(new JSONObject().put("index", 0).put("id", "FIRST").put("type", JSONObject.NULL).put("function", new JSONObject().put("name", "one").put("arguments", "{")));
        JSONArray lastTools = new JSONArray().put(new JSONObject().put("index", 0).put("function", new JSONObject().put("arguments", "}")))
                .put(new JSONObject().put("index", 1).put("function", new JSONObject().put("arguments", "]")));
        String events = "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("delta",
                new JSONObject().put("role", JSONObject.NULL).put("content", "{\"result\":").put("tool_calls", firstTools)))) + "\n\n"
                + "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "tool_calls").put("delta",
                new JSONObject().put("content", "true}").put("tool_calls", lastTools)))) + "\n\ndata: [DONE]\n\n";
        List<String> deltas = new ArrayList<>();
        JSONObject result = ModelClient.readEvents(reader(events), new ModelClient.Cancellation(), deltas::add);
        JSONObject message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertEquals("{\"result\":true}", message.getString("content"));
        assertEquals(List.of("{\"result\":", "true}"), deltas);
        assertEquals("FIRST", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals("{}", message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"));
        assertEquals("[]", message.getJSONArray("tool_calls").getJSONObject(1).getJSONObject("function").getString("arguments"));
    }

    @Test public void compatibleJsonFallbackKeepsStructuredCompletionWithoutPretendingToStream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        JSONObject completion = new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"JSON fallback\",\"tool_calls\":[{\"id\":\"CALL\",\"type\":\"function\",\"function\":{\"name\":\"tool\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}" );
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = completion.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "", "MODEL", "", "openai_compatible");
            List<String> deltas = new ArrayList<>();
            JSONObject result = ModelClient.chat(profile, ChatProtocol.request(profile, new JSONArray(), "hi"), new ModelClient.Cancellation(), deltas::add);
            assertTrue(deltas.isEmpty());
            assertEquals(completion.toString(), result.toString());
        } finally { server.stop(0); }
    }

    @Test public void compatibleCancelledAndTruncatedStreamsCannotCompleteOrLeakProviderErrors() {
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
        List<String> deltas = new ArrayList<>();
        assertThrows(CancellationException.class, () -> ModelClient.readEvents(reader(chunk("first", null) + chunk("late", "stop")), cancellation, delta -> {
            deltas.add(delta);
            cancellation.cancel();
        }));
        assertEquals(List.of("first"), deltas);
        assertThrows(java.io.IOException.class, () -> ModelClient.readEvents(reader(chunk("partial", null)), new ModelClient.Cancellation(), delta -> {}));
        assertThrows(java.io.IOException.class, () -> ModelClient.readEvents(reader("data: [DONE]\n\n"), new ModelClient.Cancellation(), delta -> {}));
        Exception error = assertThrows(java.io.IOException.class, () -> ModelClient.readEvents(reader("data: {\"error\":{\"message\":\"SECRET\"}}\n\n"), new ModelClient.Cancellation(), delta -> {}));
        assertFalse(error.getMessage().contains("SECRET"));
    }

    @Test public void cancellationWhileWaitingForHeadersRemainsCancellationForBothProviders() throws Exception {
        for (boolean codex : List.of(false, true)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CountDownLatch reachedServer = new CountDownLatch(1);
            CountDownLatch releaseServer = new CountDownLatch(1);
            server.createContext(codex ? "/responses" : "/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                reachedServer.countDown();
                try { releaseServer.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.start();
            var executor = Executors.newSingleThreadExecutor();
            try {
                ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
                Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "", "MODEL", "", codex ? "codex_subscription" : "openai_compatible");
                var future = executor.submit(() -> codex ? CodexClient.chat(profile, ChatProtocol.request(profile, new JSONArray(), "hi"),
                        new CodexOAuth.Tokens("ACCESS", "REFRESH", "ID", "ACCOUNT", "plus", "", Long.MAX_VALUE), cancellation, delta -> {})
                        : ModelClient.chat(profile, ChatProtocol.request(profile, new JSONArray(), "hi"), cancellation, delta -> {}));
                assertTrue(reachedServer.await(3, TimeUnit.SECONDS));
                cancellation.cancel();
                ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof CancellationException);
            } finally {
                releaseServer.countDown();
                executor.shutdownNow();
                server.stop(0);
            }
        }
    }

    @Test public void totalSseSizeAndIncompleteFramesRemainBounded() {
        String comments = (":" + "中".repeat(2048) + "\n").repeat(2732);
        assertThrows(java.io.IOException.class, () -> ModelClient.readEvents(reader(comments), new ModelClient.Cancellation(), delta -> {}));
        assertThrows(java.io.IOException.class, () -> CodexClient.readEvents(reader(comments), new ModelClient.Cancellation(), delta -> {}));
        assertThrows(java.io.IOException.class, () -> ModelClient.readEvents(reader("data: {\"choices\":[]}"), new ModelClient.Cancellation(), delta -> {}));
    }

    @Test public void codexHttpStreamsBeforeCompletionAndPreservesOriginalStructuredOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch firstDelta = new CountDownLatch(1);
        AtomicBoolean incremental = new AtomicBoolean();
        JSONArray output = new JSONArray().put(new JSONObject().put("type", "reasoning").put("encrypted_content", "OPAQUE"))
                .put(new JSONObject().put("type", "message").put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "你好世界"))))
                .put(new JSONObject().put("type", "function_call").put("call_id", "CALL").put("name", "screenshot").put("arguments", "{}"));
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { incremental.set(firstDelta.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            String rest = "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{}\"}\n\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"世界\"}\n\n"
                    + "data: " + new JSONObject().put("type", "response.completed").put("response", new JSONObject().put("output", output)) + "\n\n";
            exchange.getResponseBody().write(rest.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            Profile profile = new Profile(true, "http://127.0.0.1:" + server.getAddress().getPort(), "", "MODEL", "", "codex_subscription");
            CodexOAuth.Tokens tokens = new CodexOAuth.Tokens("ACCESS", "REFRESH", "ID", "ACCOUNT", "plus", "", Long.MAX_VALUE);
            List<String> deltas = new ArrayList<>();
            JSONObject result = CodexClient.chat(profile, ChatProtocol.request(profile, new JSONArray(), "hi"), tokens, new ModelClient.Cancellation(), delta -> {
                deltas.add(delta);
                firstDelta.countDown();
            });
            assertTrue(incremental.get());
            assertEquals(List.of("你好", "世界"), deltas);
            JSONObject message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            assertEquals("你好世界", message.getString("content"));
            assertEquals(output.toString(), message.getJSONArray("codex_output").toString());
            assertEquals("CALL", message.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        } finally { server.stop(0); }
    }

    @Test public void codexMultilineFallbackCompletionAndCancellationKeepProtocolSemantics() throws Exception {
        String item = "data: {\"type\":\"response.output_item.done\",\n"
                + "data: \"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}}\n\n";
        List<String> deltas = new ArrayList<>();
        JSONObject result = CodexClient.readEvents(reader(item + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"), new ModelClient.Cancellation(), deltas::add);
        assertEquals("final", ChatProtocol.answer(result));
        assertTrue(deltas.isEmpty());
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
        assertThrows(CancellationException.class, () -> CodexClient.readEvents(reader("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n" + item), cancellation, delta -> cancellation.cancel()));
        assertThrows(java.io.IOException.class, () -> CodexClient.readEvents(reader("data: {\"type\":\"response.incomplete\"}\n\n"), new ModelClient.Cancellation(), delta -> {}));
    }

    private static BufferedReader reader(String value) { return new BufferedReader(new StringReader(value)); }
    private static String chunk(String content, String finish) {
        return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", content)).put("finish_reason", finish == null ? JSONObject.NULL : finish))) + "\n\n";
    }
}
