package io.github.xiaoaiswitch.core;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ControlLoop {
    private ControlLoop() {}

    public static final int RETRY_LIMIT = 3;
    public static final int RETRY_DELAY_MS = 5000;

    public static final class StepLimit extends IllegalStateException {
        private StepLimit(int limit) { super("手机控制达到 " + limit + " 步上限"); }
    }

    public static final class TransientRead extends SecurityException {
        public TransientRead(String message) { super(message); }
    }

    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

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
        return run(profile, text, environment, limit, current, cancellation, progress, transport, ignored -> {});
    }

    public static String run(Profile profile, String text, Environment environment, int limit, BooleanSupplier current,
                             ModelClient.Cancellation cancellation, IntConsumer progress, ModelClient.Transport transport,
                             IntConsumer retrying) throws Exception {
        return run(profile, text, environment, limit, current, cancellation, progress, transport, retrying, Thread::sleep);
    }

    static String run(Profile profile, String text, Environment environment, int limit, BooleanSupplier current,
                      ModelClient.Cancellation cancellation, IntConsumer progress, ModelClient.Transport transport,
                      IntConsumer retrying, Sleeper sleeper) throws Exception {
        if (limit < 1) throw new IllegalArgumentException("控制调用上限需为正整数");
        Callable<String> capture = () -> retry(environment::screenshot, error -> error instanceof TransientRead,
                current, cancellation, retrying, sleeper);
        String screen = capture.call();
        JSONArray messages = ControlProtocol.initialMessages(profile, text, screen);
        for (int step = 0; step < limit; step++) {
            check(current, cancellation);
            progress.accept(step + 1);
            JSONObject request = ControlProtocol.request(profile, messages, !screen.isEmpty());
            JSONObject response = retry(() -> transport.chat(profile, request, cancellation), ControlLoop::temporaryNetworkError,
                    current, cancellation, retrying, sleeper);
            check(current, cancellation);
            List<ControlProtocol.Action> actions = ControlProtocol.actions(response);
            if (actions.isEmpty()) return ChatProtocol.answer(response);
            messages.put(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "assistant"));
            boolean refreshRequired = false;
            for (ControlProtocol.Action action : actions) {
                check(current, cancellation);
                if (refreshRequired || (screen.isEmpty() && !(action instanceof ControlProtocol.OpenApp))) {
                    String error = refreshRequired ? "screen_refresh_required" : "no_authorized_screen";
                    messages.put(ControlProtocol.toolResult(action.id(), new JSONObject().put("ok", false).put("error", error)));
                    continue;
                }
                JSONObject result;
                try {
                    result = environment.execute(action);
                } catch (CancellationException | SecurityException error) {
                    throw error;
                } catch (Exception error) {
                    result = new JSONObject().put("ok", false).put("error", error.getClass().getSimpleName());
                }
                refreshRequired = action instanceof ControlProtocol.OpenApp;
                messages.put(ControlProtocol.toolResult(action.id(), result));
            }
            check(current, cancellation);
            if (step + 1 < limit) {
                screen = capture.call();
                if (screen.isEmpty()) messages.put(ChatProtocol.message("user", "当前没有可用于操作的授权画面。"));
                else messages.put(ControlProtocol.visualMessage("执行后的当前屏幕。请依据实际状态判断下一步，完成后用文字回答。", screen));
            }
        }
        throw new StepLimit(limit);
    }

    private static boolean temporaryNetworkError(Exception error) {
        if (error instanceof ModelClient.HttpError http) return http.status() == 408 || http.status() == 429 || http.status() >= 500;
        return error instanceof IOException && !(error instanceof CodexClient.LoginRequired)
                && !(error instanceof java.net.MalformedURLException);
    }

    private static <T> T retry(Callable<T> operation, Predicate<Exception> temporary, BooleanSupplier current,
                               ModelClient.Cancellation cancellation, IntConsumer retrying, Sleeper sleeper) throws Exception {
        for (int attempt = 0; ; attempt++) {
            check(current, cancellation);
            try {
                return operation.call();
            } catch (Exception error) {
                check(current, cancellation);
                if (attempt == RETRY_LIMIT || !temporary.test(error)) throw error;
                retrying.accept(attempt + 1);
                for (int waited = 0; waited < RETRY_DELAY_MS; waited += 100) {
                    check(current, cancellation);
                    sleeper.sleep(100);
                }
            }
        }
    }

    private static void check(BooleanSupplier current, ModelClient.Cancellation cancellation) {
        if (!current.getAsBoolean() || cancellation.isCancelled()) throw new CancellationException();
    }
}
