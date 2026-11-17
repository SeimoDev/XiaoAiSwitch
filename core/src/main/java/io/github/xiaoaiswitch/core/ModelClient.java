package io.github.xiaoaiswitch.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
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
        JSONObject compatible = new JSONObject(request.toString());
        JSONArray messages = compatible.getJSONArray("messages");
        for (int index = 0; index < messages.length(); index++) messages.getJSONObject(index).remove("codex_output");
        return post(profile, profile.chatUrl(), compatible, cancellation);
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
        while ((count = input.read(buffer)) != -1) {
            if (cancellation.isCancelled()) throw new CancellationException();
            if (bytes.size() + count > 16 * 1024 * 1024) throw new IOException("模型数据超过 16 MiB");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static void field(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject send(Profile profile, String url, String contentType, byte[] body, Cancellation cancellation) throws IOException, JSONException {
        if (cancellation.isCancelled()) throw new CancellationException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        cancellation.attach(connection);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        if (!profile.token().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + profile.token());
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(read(input, cancellation), StandardCharsets.UTF_8));
            }
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
    }
}
