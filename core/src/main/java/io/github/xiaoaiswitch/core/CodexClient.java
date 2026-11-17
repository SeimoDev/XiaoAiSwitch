package io.github.xiaoaiswitch.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CodexClient {
    private CodexClient() {}
    public static final class LoginRequired extends IOException {
        public LoginRequired() { super("请在模块设置中登录或重新登录 Codex 订阅"); }
    }

    public static JSONObject chat(Profile profile, JSONObject chat, CodexOAuth.Tokens tokens, ModelClient.Cancellation cancellation) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(profile.endpoint() + "/responses").openConnection();
        cancellation.attach(connection);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + tokens.accessToken());
        connection.setRequestProperty("ChatGPT-Account-Id", tokens.accountId());
        connection.setRequestProperty("originator", "xiaoai_switch");
        connection.setRequestProperty("User-Agent", "XiaoAiSwitch Codex Provider");
        connection.setRequestProperty("session_id", UUID.randomUUID().toString());
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(CodexProtocol.request(chat).toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ModelClient.HttpError(status);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return readEvents(reader, cancellation);
            }
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
    }

    public static JSONObject models(CodexOAuth.Tokens tokens) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(CodexProtocol.BASE_URL + "/models?client_version=0.169.0").openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", "Bearer " + tokens.accessToken());
        connection.setRequestProperty("ChatGPT-Account-Id", tokens.accountId());
        connection.setRequestProperty("User-Agent", "XiaoAiSwitch Codex Provider");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ModelClient.HttpError(status);
            try (java.io.InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(ModelClient.read(input, new ModelClient.Cancellation()), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    public static JSONObject images(Profile profile, String prompt, List<ModelClient.Image> originals,
                                    CodexOAuth.Tokens tokens, ModelClient.Cancellation cancellation) throws IOException {
        byte[] body = CodexImages.request(profile, prompt, originals).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(originals.isEmpty() ? profile.imageUrl() : profile.imageEditUrl()).openConnection();
        cancellation.attach(connection);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(300000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokens.accessToken());
        connection.setRequestProperty("ChatGPT-Account-Id", tokens.accountId());
        connection.setRequestProperty("originator", "xiaoai_switch");
        connection.setRequestProperty("User-Agent", "XiaoAiSwitch Codex Provider");
        connection.setRequestProperty("x-codex-image-turn-id", UUID.randomUUID().toString());
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ModelClient.HttpError(status);
            try (java.io.InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(ModelClient.read(input, cancellation), StandardCharsets.UTF_8));
            }
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }
    }

    public static JSONObject readEvents(BufferedReader reader, ModelClient.Cancellation cancellation) throws IOException {
        JSONArray done = new JSONArray();
        String line;
        int size = 0;
        while ((line = reader.readLine()) != null) {
            if (cancellation.isCancelled()) throw new CancellationException();
            size += line.length();
            if (size > 16 * 1024 * 1024) throw new IOException("Codex 响应超过 16 MiB");
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) continue;
            JSONObject event = new JSONObject(data);
            String type = event.optString("type");
            if (type.equals("response.output_item.done")) done.put(event.getJSONObject("item"));
            if (type.equals("response.completed")) {
                JSONObject response = event.getJSONObject("response");
                JSONArray output = response.optJSONArray("output");
                if ((output == null || output.length() == 0) && done.length() > 0) response.put("output", done);
                return CodexProtocol.completion(response);
            }
            if (type.equals("response.failed") || type.equals("error")) throw new IOException("Codex 返回失败事件");
        }
        throw new IOException("Codex 响应未完成");
    }
}
