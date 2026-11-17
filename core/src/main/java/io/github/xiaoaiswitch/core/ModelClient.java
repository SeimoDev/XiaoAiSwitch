package io.github.xiaoaiswitch.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

public final class ModelClient {
    private ModelClient() {}

    public record Image(byte[] bytes, String mime) {}
    public interface Transport {
        JSONObject chat(Profile profile, JSONObject request, Cancellation cancellation) throws Exception;
    }
    public static JSONObject chat(Profile profile, JSONObject request, Cancellation cancellation) throws Exception {
        return post(profile, profile.chatUrl(), compatible(request), cancellation);
    }

    /** Emits actual SSE text deltas on the reading thread; JSON fallback returns without callbacks. */
    public static JSONObject chat(Profile profile, JSONObject request, Cancellation cancellation, Consumer<String> onDelta) throws IOException {
        JSONObject body = compatible(request).put("stream", true);
        return send(profile, profile.chatUrl(), "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8),
                cancellation, java.util.Objects.requireNonNull(onDelta));
    }

    private static JSONObject compatible(JSONObject request) {
        JSONObject compatible = new JSONObject(request.toString());
        JSONArray messages = compatible.getJSONArray("messages");
        for (int index = 0; index < messages.length(); index++) messages.getJSONObject(index).remove("codex_output");
        return compatible;
    }

    public static List<String> models(Profile profile) throws IOException {
        String base = profile.endpoint().replaceAll("/+$", "").replaceFirst("/(chat/completions|images/(generations|edits))$", "");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/models").openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        if (!profile.token().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + profile.token());
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpError(status);
            try (InputStream input = connection.getInputStream()) {
                JSONArray data = new JSONObject(new String(read(input, new Cancellation()), StandardCharsets.UTF_8)).optJSONArray("data");
                if (data == null) throw new IOException("Provider 未返回模型列表");
                List<String> result = new ArrayList<>();
                for (int index = 0; index < data.length(); index++) {
                    String name = data.getJSONObject(index).getString("id").trim();
                    if (!name.isEmpty() && !result.contains(name)) result.add(name);
                }
                return result;
            }
        } finally { connection.disconnect(); }
    }

    public static final class HttpError extends IOException {
        private final int status;
        HttpError(int status) { super("模型服务 HTTP " + status); this.status = status; }
        public int status() { return status; }
    }

    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        public boolean isCancelled() { return cancelled; }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                cancelled = true;
                active = connection;
            }
            if (active != null) active.disconnect();
        }

        synchronized void attach(HttpURLConnection value) {
            if (cancelled) throw new CancellationException();
            connection = value;
        }

        synchronized void detach(HttpURLConnection value) {
            if (connection == value) connection = null;
        }
    }

    public static JSONObject post(Profile profile, String url, JSONObject body) throws IOException, JSONException {
        return post(profile, url, body, new Cancellation());
    }

    public static JSONObject post(Profile profile, String url, JSONObject body, Cancellation cancellation) throws IOException, JSONException {
        return send(profile, url, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8), cancellation);
    }

    public static JSONObject edit(Profile profile, String prompt, List<Image> images, Cancellation cancellation) throws IOException, JSONException {
        if (images.isEmpty()) throw new IllegalArgumentException("图生图需要原图");
        String boundary = "XiaoAiSwitch-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", profile.model());
        field(body, boundary, "prompt", profile.systemPrompt().isEmpty() ? prompt : profile.systemPrompt() + "\n\n" + prompt);
        field(body, boundary, "n", "1");
        for (int index = 0; index < images.size(); index++) {
            Image image = images.get(index);
            String extension = image.mime().equals("image/jpeg") ? "jpg" : image.mime().equals("image/webp") ? "webp" : "png";
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + (images.size() == 1 ? "image" : "image[]")
                    + "\"; filename=\"image" + index + "." + extension + "\"\r\nContent-Type: " + image.mime() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(image.bytes());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(profile, profile.imageEditUrl(), "multipart/form-data; boundary=" + boundary, body.toByteArray(), cancellation);
    }

    public static byte[] downloadImage(String url, Cancellation cancellation) throws IOException {
        if (cancellation.isCancelled()) throw new CancellationException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        cancellation.attach(connection);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            try (InputStream input = connection.getInputStream()) { return read(input, cancellation); }
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
    }

    public static byte[] read(InputStream input, Cancellation cancellation) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while (true) {
            if (cancellation.isCancelled()) throw new CancellationException();
            try { count = input.read(buffer); }
            catch (IOException error) {
                if (cancellation.isCancelled()) throw new CancellationException();
                throw error;
            }
            if (cancellation.isCancelled()) throw new CancellationException();
            if (count == -1) break;
            if (bytes.size() + count > 16 * 1024 * 1024) throw new IOException("模型数据超过 16 MiB");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static JSONObject readEvents(BufferedReader reader, Cancellation cancellation, Consumer<String> onDelta) throws IOException {
        ServerSentEvents events = new ServerSentEvents(reader, cancellation);
        TreeMap<Integer, ChatChoice> choices = new TreeMap<>();
        JSONObject result = new JSONObject();
        String data;
        while ((data = events.next()) != null) {
            if (data.trim().isEmpty()) continue;
            if (data.trim().equals("[DONE]")) break;
            JSONObject chunk = new JSONObject(data);
            if (chunk.has("error")) throw new IOException("模型服务返回错误");
            for (String key : chunk.keySet()) if (!key.equals("choices")) result.put(key, chunk.get(key));
            JSONArray updates = chunk.optJSONArray("choices");
            if (updates == null) continue;
            for (int index = 0; index < updates.length(); index++) {
                JSONObject update = updates.getJSONObject(index);
                int choice = update.optInt("index", index);
                ChatChoice state = choices.computeIfAbsent(choice, key -> new ChatChoice());
                JSONObject delta = update.optJSONObject("delta");
                if (delta != null) state.append(delta, choice == 0 ? onDelta : text -> {});
                if (!update.isNull("finish_reason")) state.finish = update.getString("finish_reason");
            }
        }
        if (choices.isEmpty() || choices.values().stream().anyMatch(choice -> choice.finish == null)) throw new IOException("模型流式响应未完成");
        JSONArray completed = new JSONArray();
        choices.forEach((index, choice) -> completed.put(new JSONObject().put("index", index).put("message", choice.message()).put("finish_reason", choice.finish)));
        return result.put("object", "chat.completion").put("choices", completed);
    }

    private static final class ChatChoice {
        private String role = "assistant";
        private String finish;
        private final StringBuilder text = new StringBuilder();
        private final TreeMap<Integer, ToolCall> calls = new TreeMap<>();

        void append(JSONObject delta, Consumer<String> onDelta) {
            if (!delta.isNull("role")) role = delta.getString("role");
            Object content = delta.opt("content");
            if (content instanceof String value && !value.isEmpty()) {
                text.append(value);
                onDelta.accept(value);
            }
            JSONArray tools = delta.optJSONArray("tool_calls");
            if (tools == null) return;
            for (int index = 0; index < tools.length(); index++) {
                JSONObject tool = tools.getJSONObject(index);
                calls.computeIfAbsent(tool.optInt("index", index), key -> new ToolCall()).append(tool);
            }
        }

        JSONObject message() {
            JSONObject message = new JSONObject().put("role", role).put("content", text.length() == 0 ? JSONObject.NULL : text.toString());
            if (!calls.isEmpty()) {
                JSONArray tools = new JSONArray();
                calls.values().forEach(call -> tools.put(call.json()));
                message.put("tool_calls", tools);
            }
            return message;
        }
    }

    private static final class ToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private String type = "function";

        void append(JSONObject delta) {
            id.append(delta.optString("id", ""));
            if (!delta.isNull("type")) type = delta.getString("type");
            JSONObject function = delta.optJSONObject("function");
            if (function == null) return;
            name.append(function.optString("name", ""));
            arguments.append(function.optString("arguments", ""));
        }

        JSONObject json() {
            return new JSONObject().put("id", id.toString()).put("type", type).put("function",
                    new JSONObject().put("name", name.toString()).put("arguments", arguments.toString()));
        }
    }

    private static void field(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject send(Profile profile, String url, String contentType, byte[] body, Cancellation cancellation) throws IOException, JSONException {
        return send(profile, url, contentType, body, cancellation, null);
    }

    private static JSONObject send(Profile profile, String url, String contentType, byte[] body, Cancellation cancellation,
                                   Consumer<String> onDelta) throws IOException, JSONException {
        if (cancellation.isCancelled()) throw new CancellationException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        cancellation.attach(connection);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        if (onDelta != null) connection.setRequestProperty("Accept", "text/event-stream");
        if (!profile.token().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + profile.token());
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            try (InputStream input = connection.getInputStream()) {
                String responseType = connection.getContentType();
                if (onDelta != null && responseType != null && responseType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                    return readEvents(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)), cancellation, onDelta);
                }
                return new JSONObject(new String(read(input, cancellation), StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            if (cancellation.isCancelled()) throw new CancellationException();
            throw error;
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
    }
}
