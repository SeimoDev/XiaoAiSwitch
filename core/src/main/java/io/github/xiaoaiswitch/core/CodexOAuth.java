package io.github.xiaoaiswitch.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

public final class CodexOAuth {
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String ISSUER = "https://auth.openai.com";
    private final String issuer;
    public record Device(String id, String code, String url, int interval, long expiresAt) {}
    public record Tokens(String accessToken, String refreshToken, String idToken, String accountId, String plan, String email, long expiresAt) {
        public JSONObject json() { return new JSONObject().put("accessToken", accessToken).put("refreshToken", refreshToken).put("idToken", idToken)
                .put("accountId", accountId).put("plan", plan).put("email", email).put("expiresAt", expiresAt); }
        public static Tokens stored(JSONObject json) { return new Tokens(json.getString("accessToken"), json.getString("refreshToken"), json.optString("idToken"),
                json.getString("accountId"), json.optString("plan"), json.optString("email"), json.getLong("expiresAt")); }
    }

    public CodexOAuth() { this(ISSUER); }
    public CodexOAuth(String issuer) { this.issuer = issuer.replaceAll("/+$", ""); }

    public Device start() throws IOException {
        JSONObject result = json("/api/accounts/deviceauth/usercode", new JSONObject().put("client_id", CLIENT_ID));
        int interval = Math.max(1, Integer.parseInt(String.valueOf(result.opt("interval") == null ? "5" : result.get("interval"))));
        return new Device(result.getString("device_auth_id"), result.optString("user_code", result.optString("usercode")),
                issuer + "/codex/device", interval, System.currentTimeMillis() + 15 * 60 * 1000);
    }

    public Tokens poll(Device device) throws IOException {
        if (System.currentTimeMillis() >= device.expiresAt()) throw new IOException("Codex 设备代码已过期，请重新登录");
        Reply reply = send("/api/accounts/deviceauth/token", "application/json", new JSONObject().put("device_auth_id", device.id()).put("user_code", device.code()).toString());
        if (reply.status() == 403 || reply.status() == 404) return null;
        JSONObject result = success(reply);
        String body = form("grant_type", "authorization_code", "client_id", CLIENT_ID, "code", result.getString("authorization_code"),
                "redirect_uri", issuer + "/deviceauth/callback", "code_verifier", result.getString("code_verifier"));
        return tokens(success(send("/oauth/token", "application/x-www-form-urlencoded", body)), null);
    }

    public Tokens refresh(Tokens previous) throws IOException {
        return tokens(success(send("/oauth/token", "application/x-www-form-urlencoded",
                form("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", previous.refreshToken()))), previous);
    }

    private Tokens tokens(JSONObject response, Tokens previous) throws IOException {
        String access = response.getString("access_token");
        String id = response.optString("id_token", previous == null ? "" : previous.idToken());
        JSONObject claims = claims(id.isEmpty() ? access : id);
        JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
        String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
        if (account.isEmpty() && previous != null) account = previous.accountId();
        if (account.isEmpty()) throw new IOException("登录结果缺少 ChatGPT 账户标识");
        return new Tokens(access, response.optString("refresh_token", previous == null ? "" : previous.refreshToken()), id, account,
                auth == null ? "" : auth.optString("chatgpt_plan_type", ""), claims.optString("email", ""),
                System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000);
    }

    private JSONObject claims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return new JSONObject();
        return new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private record Reply(int status, String body) {}

    private JSONObject json(String path, JSONObject body) throws IOException { return success(send(path, "application/json", body.toString())); }

    private Reply send(String path, String type, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(issuer + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", type);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "XiaoAiSwitch Codex OAuth");
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return new Reply(status, "");
            try (InputStream input = stream) { return new Reply(status, new String(ModelClient.read(input, new ModelClient.Cancellation()), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }

    private JSONObject success(Reply reply) throws IOException {
        if (reply.status() < 200 || reply.status() >= 300) throw new IOException("Codex 登录 HTTP " + reply.status());
        return new JSONObject(reply.body());
    }

    private String form(String... pairs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < pairs.length; index += 2) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(pairs[index], "UTF-8")).append('=').append(URLEncoder.encode(pairs[index + 1], "UTF-8"));
        }
        return body.toString();
    }
}
