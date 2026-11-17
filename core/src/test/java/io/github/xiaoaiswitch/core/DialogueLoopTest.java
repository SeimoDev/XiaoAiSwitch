package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DialogueLoopTest {
    @Test public void duplicateSystemCallIsNotDispatchedAndFailureGoesBackToTheModel() throws Exception {
        Profile profile = Profile.parse(new JSONObject().put("model", "DIALOGUE"));
        DialogueProtocol.Tools tools = new DialogueProtocol.Tools(false, false, true);
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "打开不存在的应用", List.of(), tools);
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        DialogueLoop.Result result = DialogueLoop.run(profile, request, tools, task -> {
            actions.incrementAndGet();
            assertEquals(new SystemTools.Launch("不存在的应用"), task.systemAction());
            return new DialogueLoop.Result("没有找到应用", List.of(), false);
        }, () -> true, new ModelClient.Cancellation(), (active, input, cancel) -> {
            int call = requests.incrementAndGet();
            if (call > 1) {
                JSONArray messages = input.getJSONArray("messages");
                JSONObject last = new JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"));
                assertFalse(last.getBoolean("ok"));
                assertEquals("没有找到应用", last.getString("message"));
            }
            return call == 3 ? new JSONObject("{\"choices\":[{\"message\":{\"content\":\"没有找到应用\"}}]}")
                    : DialogueProtocolTest.response("launch_app", new JSONObject().put("app_name", "不存在的应用"));
        });
        assertEquals(1, actions.get());
        assertEquals("没有找到应用", result.text());
    }

    @Test public void delegationMayContinuePastFourModelCallsUntilFinalAnswer() throws Exception {
        Profile profile = Profile.parse(new JSONObject().put("model", "PLANNER"));
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "生成图像", "", true, false);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        DialogueLoop.Result result = DialogueLoop.run(profile, request, true, false, task ->
                new DialogueLoop.Result("完成", List.of("https://HOST/image-" + images.incrementAndGet() + ".png")),
                () -> true, new ModelClient.Cancellation(), (active, input, cancel) -> {
                    if (calls.incrementAndGet() == 7) {
                        return new JSONObject("{\"choices\":[{\"message\":{\"content\":\"全部完成\"}}]}");
                    }
                    return DialogueProtocolTest.response("generate_image", new JSONObject().put("prompt", "猫"));
                });
        assertEquals(7, calls.get());
        assertEquals(6, result.images().size());
        assertEquals("全部完成", result.text());
    }

    @Test public void cancellationAfterTheOldLimitDiscardsThePendingToolCall() {
        Profile profile = Profile.parse(new JSONObject().put("model", "PLANNER"));
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "打开设置", "", false, true);
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        assertThrows(CancellationException.class, () -> DialogueLoop.run(profile, request, false, true, task -> {
            actions.incrementAndGet();
            return new DialogueLoop.Result("完成", List.of());
        }, () -> true, cancellation, (active, input, cancel) -> {
            if (calls.incrementAndGet() == 6) cancel.cancel();
            return DialogueProtocolTest.response("control_phone", new JSONObject().put("task", "打开设置"));
        }));
        assertEquals(6, calls.get());
        assertEquals(5, actions.get());
    }

    @Test public void permissionDenialCannotTriggerADialogueRetry() {
        Profile profile = Profile.parse(new JSONObject().put("model", "PLANNER"));
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "打开设置", "", false, true);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(SecurityException.class, () -> DialogueLoop.run(profile, request, false, true, task -> {
            throw new SecurityException("denied");
        }, () -> true, new ModelClient.Cancellation(), (active, input, cancel) -> {
            calls.incrementAndGet();
            return DialogueProtocolTest.response("control_phone", new JSONObject().put("task", "打开设置"));
        }));
        assertEquals(1, calls.get());
    }

    @Test public void phoneStepLimitEndsDelegationInsteadOfStartingAnotherControlTask() {
        Profile profile = Profile.parse(new JSONObject().put("model", "PLANNER"));
        JSONObject request = DialogueProtocol.request(profile, new JSONArray(), "打开设置", "", false, true);
        JSONObject controlAnswer = new JSONObject("""
                {"choices":[{"message":{"tool_calls":[
                    {"id":"SCREEN","function":{"name":"screenshot","arguments":"{}"}}
                ]}}]}
                """);
        AtomicInteger calls = new AtomicInteger();
        ControlLoop.Environment phone = new ControlLoop.Environment() {
            @Override public String screenshot() { return "screen"; }
            @Override public JSONObject execute(ControlProtocol.Action action) { return new JSONObject().put("ok", true); }
        };
        assertThrows(ControlLoop.StepLimit.class, () -> DialogueLoop.run(profile, request, false, true, task -> {
            ControlLoop.run(profile, task.text(), phone, 1, () -> true, new ModelClient.Cancellation(), ignored -> {},
                    (active, input, cancel) -> controlAnswer);
            return new DialogueLoop.Result("完成", List.of());
        }, () -> true, new ModelClient.Cancellation(), (active, input, cancel) -> {
            if (calls.incrementAndGet() > 1) throw new AssertionError("Retried after reaching the user step limit");
            return DialogueProtocolTest.response("control_phone", new JSONObject().put("task", "打开设置"));
        }));
        assertEquals(1, calls.get());
    }
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
