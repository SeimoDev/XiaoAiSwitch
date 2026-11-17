package io.github.xiaoaiswitch.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DialogueLoop {
    private DialogueLoop() {}

    public record Result(String text, List<String> images) {}
    public interface Environment { Result execute(DialogueProtocol.Task task) throws Exception; }

    public static Result run(Profile profile, JSONObject request, boolean images, boolean control, Environment environment,
                             BooleanSupplier current, ModelClient.Cancellation cancellation) throws Exception {
        return run(profile, request, images, control, environment, current, cancellation, ModelClient::chat);
    }

    public static Result run(Profile profile, JSONObject request, boolean images, boolean control, Environment environment,
                             BooleanSupplier current, ModelClient.Cancellation cancellation, ModelClient.Transport transport) throws Exception {
        List<String> output = new ArrayList<>();
        JSONArray messages = request.getJSONArray("messages");
        while (true) {
            check(current, cancellation);
            JSONObject response = transport.chat(profile, request, cancellation);
            check(current, cancellation);
            List<DialogueProtocol.Task> tasks = DialogueProtocol.tasks(response, images, control);
            if (tasks.isEmpty()) return new Result(ChatProtocol.answer(response), output);
            messages.put(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "assistant"));
            for (DialogueProtocol.Task task : tasks) {
                check(current, cancellation);
                JSONObject result;
                try {
                    Result value = environment.execute(task);
                    output.addAll(value.images());
                    result = new JSONObject().put("ok", true).put("message", value.text()).put("image_count", value.images().size());
                } catch (CancellationException | SecurityException | ControlLoop.StepLimit error) {
                    throw error;
                } catch (Exception error) {
                    result = new JSONObject().put("ok", false).put("error", error.getClass().getSimpleName());
                }
                messages.put(ControlProtocol.toolResult(task.id(), result));
            }
        }
    }

    private static void check(BooleanSupplier current, ModelClient.Cancellation cancellation) {
        if (!current.getAsBoolean() || cancellation.isCancelled()) throw new CancellationException();
    }
}
