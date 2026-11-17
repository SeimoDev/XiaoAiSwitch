package io.github.xiaoaiswitch.core;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ControlLoop {
    private ControlLoop() {}

    public static final class StepLimit extends IllegalStateException {
        private StepLimit(int limit) { super("手机控制达到 " + limit + " 次模型调用上限"); }
    }

    public interface Environment {
        String screenshot() throws Exception;
        JSONObject execute(ControlProtocol.Action action) throws Exception;
    }

    public static String run(Profile profile, String text, Environment environment, int limit, BooleanSupplier current,
                             ModelClient.Cancellation cancellation, IntConsumer progress) throws Exception {
        return run(profile, text, environment, limit, current, cancellation, progress, ModelClient::chat);
    }

    public static String run(Profile profile, String text, Environment environment, int limit, BooleanSupplier current,
                             ModelClient.Cancellation cancellation, IntConsumer progress, ModelClient.Transport transport) throws Exception {
        if (limit < 1 || limit > 30) throw new IllegalArgumentException("控制步数应在 1 到 30 之间");
        check(current, cancellation);
        JSONArray messages = ControlProtocol.initialMessages(profile, text, environment.screenshot());
        for (int step = 1; step <= limit; step++) {
            check(current, cancellation);
            progress.accept(step);
            JSONObject response = transport.chat(profile, ControlProtocol.request(profile, messages), cancellation);
            check(current, cancellation);
            List<ControlProtocol.Action> actions = ControlProtocol.actions(response);
            if (actions.isEmpty()) return ChatProtocol.answer(response);
            messages.put(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "assistant"));
            for (ControlProtocol.Action action : actions) {
                check(current, cancellation);
                JSONObject result;
                try {
                    result = environment.execute(action);
                } catch (CancellationException error) {
                    throw error;
                } catch (Exception error) {
                    result = new JSONObject().put("ok", false).put("error", error.getClass().getSimpleName());
                }
                messages.put(ControlProtocol.toolResult(action.id(), result));
            }
            check(current, cancellation);
            messages.put(ControlProtocol.visualMessage("执行后的当前屏幕。请依据实际状态判断下一步，完成后用文字回答。", environment.screenshot()));
        }
        throw new StepLimit(limit);
    }

    private static void check(BooleanSupplier current, ModelClient.Cancellation cancellation) {
        if (!current.getAsBoolean() || cancellation.isCancelled()) throw new CancellationException();
    }
}
