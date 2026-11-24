package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ControlLoopTest {
    @Test public void retriesTemporaryNetworkFailureThreeTimesFiveSecondsApart() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong waited = new AtomicLong();
        List<Integer> retries = new ArrayList<>();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) { throw new AssertionError("No input before a valid result"); }
        };
        assertThrows(ModelClient.HttpError.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                12, () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, request, cancel) -> {
                    calls.incrementAndGet();
                    throw new ModelClient.HttpError(503);
                }, retries::add, waited::addAndGet));
        assertEquals(4, calls.get());
        assertEquals(List.of(1, 2, 3), retries);
        assertEquals(15000, waited.get());
    }

    @Test public void staleLaunchFrameIsRetriedWithoutRelaunching() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong waited = new AtomicLong();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() {
                int call = captures.getAndIncrement();
                if (call == 0) return "BEFORE";
                if (call < 3) throw new ControlLoop.TransientRead("目标应用仍在启动");
                return "AFTER";
            }
            @Override public JSONObject execute(ControlProtocol.Action action) {
                assertTrue(action instanceof ControlProtocol.OpenApp);
                launches.incrementAndGet();
                return new JSONObject().put("ok", true);
            }
        };
        String finalText = ControlLoop.run(Profile.parse(new JSONObject()), "task", environment, 2,
                () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, input, cancel) -> {
                    if (calls.incrementAndGet() == 1) {
                        return DialogueProtocolTest.response("open_app", new JSONObject().put("package", "io.github.xiaoaiswitch"));
                    }
                    assertTrue(input.toString().contains("AFTER"));
                    return new JSONObject("{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}");
                }, ignored -> {}, waited::addAndGet);
        assertEquals("完成", finalText);
        assertEquals(1, launches.get());
        assertEquals(10000, waited.get());
        assertEquals(2, calls.get());
    }

    @Test public void permanentErrorsAreNotRetriedAndStoppingInterruptsBackoff() {
        AtomicInteger calls = new AtomicInteger();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) { throw new AssertionError("Test must not execute any operation"); }
        };
        assertThrows(ModelClient.HttpError.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                3, () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, input, cancel) -> {
                    throw new ModelClient.HttpError(400);
                }, ignored -> { throw new AssertionError("Permanent HTTP error must not be retried"); },
                ignored -> { throw new AssertionError("Permanent HTTP error must not wait"); }));
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
        assertThrows(CancellationException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                3, () -> true, cancellation, ignored -> {}, (profile, input, cancel) -> {
                    calls.incrementAndGet();
                    throw new java.net.SocketTimeoutException();
                }, ignored -> {}, ignored -> cancellation.cancel()));
        assertEquals(1, calls.get());
    }

    @Test public void protectedInitialScreenAndAppLaunchNeverUseOldCoordinates() throws Exception {
        assertLaunchRefreshesFrame(true);
        assertLaunchRefreshesFrame(false);
    }

    private void assertLaunchRefreshesFrame(boolean opened) throws Exception {
        AtomicInteger screenshots = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<ControlProtocol.Action> executed = new ArrayList<>();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { return screenshots.incrementAndGet() == 1 ? "" : "NEW_SCREEN"; }
            @Override public JSONObject execute(ControlProtocol.Action action) {
                executed.add(action);
                return new JSONObject().put("ok", opened);
            }
        };
        JSONObject openAndTap = DialogueProtocolTest.response("open_app", new JSONObject().put("package", "io.github.xiaoaiswitch"));
        JSONArray tools = openAndTap.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls");
        tools.getJSONObject(0).put("id", "OPEN");
        tools.put(new JSONObject().put("id", "OLD").put("function", new JSONObject().put("name", "tap")
                .put("arguments", new JSONObject().put("x", 0.5).put("y", 0.5).toString())));
        assertEquals("完成", ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                3, () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, input, cancel) -> {
                    if (calls.getAndIncrement() == 0) {
                        assertEquals(1, input.getJSONArray("tools").length());
                        assertEquals("open_app", input.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));
                        return openAndTap;
                    }
                    JSONArray history = input.getJSONArray("messages");
                    JSONObject skipped = new JSONObject(history.getJSONObject(3).getString("content"));
                    assertEquals("screen_refresh_required", skipped.getString("error"));
                    assertEquals("NEW_SCREEN", history.getJSONObject(4).getJSONArray("content").getJSONObject(1)
                            .getJSONObject("image_url").getString("url"));
                    return new JSONObject("{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}");
                }));
        assertEquals(List.of(new ControlProtocol.OpenApp("OPEN", "io.github.xiaoaiswitch")), executed);
    }
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
            assertThrows(ControlLoop.StepLimit.class, () -> ControlLoop.run(profile, "task", environment, 35,
                    () -> true, new ModelClient.Cancellation(), ignored -> {}));
            assertEquals(35, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test public void permissionDenialStopsBeforeAnyOtherActionOrScreenshot() {
        AtomicInteger screenshots = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        ControlLoop.Environment environment = new ControlLoop.Environment() {
            @Override public String screenshot() { screenshots.incrementAndGet(); return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) {
                attempts.incrementAndGet();
                throw new SecurityException("denied");
            }
        };
        JSONObject request = new JSONObject("""
                {"choices":[{"message":{"tool_calls":[
                    {"id":"FIRST","function":{"name":"screenshot","arguments":"{}"}},
                    {"id":"SECOND","function":{"name":"screenshot","arguments":"{}"}}
                ]}}]}
                """);
        assertThrows(SecurityException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                1, () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, input, cancel) -> request));
        assertEquals(1, attempts.get());
        assertEquals(1, screenshots.get());
    }

    @Test public void deniedScreenAccessNeverSendsARequestOrRetries() {
        var captures = new AtomicInteger();
        var requests = new AtomicInteger();
        var retries = new AtomicInteger();
        var environment = new ControlLoop.Environment() {
            @Override public String screenshot() { captures.incrementAndGet(); throw new SecurityException("declined"); }
            @Override public JSONObject execute(ControlProtocol.Action action) { throw new AssertionError("No action expected"); }
        };
        assertThrows(SecurityException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                3, () -> true, new ModelClient.Cancellation(), ignored -> {}, (profile, request, cancel) -> {
                    requests.incrementAndGet(); return new JSONObject();
                }, ignored -> retries.incrementAndGet()));
        assertEquals(1, captures.get());
        assertEquals(0, requests.get());
        assertEquals(0, retries.get());
    }

    @Test public void lateModelResponseDoesNotActAfterStop() {
        var cancellation = new ModelClient.Cancellation();
        var actions = new AtomicInteger();
        var environment = new ControlLoop.Environment() {
            @Override public String screenshot() { return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) { actions.incrementAndGet(); return new JSONObject(); }
        };
        assertThrows(CancellationException.class, () -> ControlLoop.run(Profile.parse(new JSONObject()), "task", environment,
                3, () -> true, cancellation, ignored -> {}, (profile, request, cancel) -> {
                    cancellation.cancel();
                    return new JSONObject("""
                            {"choices":[{"message":{"tool_calls":[{"id":"LATE","function":{"name":"screenshot","arguments":"{}"}}]}}]}
                            """);
                }));
        assertEquals(0, actions.get());
    }
}
