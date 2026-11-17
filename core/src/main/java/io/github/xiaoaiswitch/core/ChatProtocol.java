package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ChatProtocol {
    private ChatProtocol() {}

    public static JSONObject request(Profile profile, JSONArray history, String text) {
        return request(profile, history, text, profile.systemPrompt());
    }

    static JSONObject request(Profile profile, JSONArray history, String text, String prompt) {
        JSONArray messages = new JSONArray();
        if (!prompt.isEmpty()) messages.put(message("system", prompt));
        for (int index = 0; index < history.length(); index++) messages.put(history.getJSONObject(index));
        messages.put(message("user", text));
        return new JSONObject().put("model", profile.model()).put("stream", false).put("messages", messages);
    }

    public static String answer(JSONObject response) {
        if (response.has("error")) throw new IllegalArgumentException("模型服务返回错误");
        JSONArray choices = response.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        Object content = message == null ? null : message.opt("content");
        if (content instanceof String text && !text.trim().isEmpty()) return text;
        if (content instanceof JSONArray parts) {
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < parts.length(); index++) {
                JSONObject part = parts.optJSONObject(index);
                if (part != null && "text".equals(part.optString("type"))) result.append(part.optString("text"));
            }
            if (!result.toString().trim().isEmpty()) return result.toString();
        }
        throw new IllegalArgumentException("模型响应缺少 choices[0].message.content");
    }

    public static JSONObject imageRequest(Profile profile, String text) {
        return new JSONObject().put("model", profile.model()).put("n", 1)
                .put("prompt", profile.systemPrompt().isEmpty() ? text : profile.systemPrompt() + "\n\n" + text);
    }

    public static JSONObject message(String role, String text) {
        return new JSONObject().put("role", role).put("content", text);
    }

    public static JSONArray history(JSONObject request, String answer) {
        JSONArray sent = request.getJSONArray("messages");
        int users = 0;
        int start = 0;
        for (int index = sent.length() - 1; index >= 0; index--) {
            if ("user".equals(sent.getJSONObject(index).optString("role")) && ++users == 10) {
                start = index;
                break;
            }
        }
        JSONArray result = new JSONArray();
        for (int index = start; index < sent.length(); index++) {
            JSONObject message = sent.getJSONObject(index);
            if (!"system".equals(message.optString("role"))) result.put(message);
        }
        return result.put(message("assistant", answer));
    }
}
