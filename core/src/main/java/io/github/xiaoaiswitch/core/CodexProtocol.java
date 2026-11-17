package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CodexProtocol {
    private CodexProtocol() {}
    public static final String BASE_URL = "https://chatgpt.com/backend-api/codex";

    public static JSONObject request(JSONObject chat) {
        JSONArray input = new JSONArray();
        StringBuilder instructions = new StringBuilder();
        JSONArray messages = chat.getJSONArray("messages");
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.getJSONObject(index);
            String role = message.getString("role");
            JSONArray original = message.optJSONArray("codex_output");
            if (role.equals("assistant") && original != null) {
                for (int item = 0; item < original.length(); item++) input.put(original.getJSONObject(item));
                continue;
            }
            if (role.equals("system") || role.equals("developer")) {
                if (instructions.length() > 0) instructions.append("\n\n");
                instructions.append(message.optString("content", ""));
                continue;
            }
            if (role.equals("tool")) {
                input.put(new JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id"))
                        .put("output", message.getString("content")));
                continue;
            }
            JSONArray content = new JSONArray();
            Object value = message.opt("content");
            if (value instanceof String text && !text.isEmpty()) {
                content.put(new JSONObject().put("type", role.equals("assistant") ? "output_text" : "input_text").put("text", text));
            }
            if (value instanceof JSONArray parts) {
                for (int part = 0; part < parts.length(); part++) {
                    JSONObject item = parts.getJSONObject(part);
                    if (item.getString("type").equals("text")) content.put(new JSONObject().put("type", "input_text").put("text", item.getString("text")));
                    if (item.getString("type").equals("image_url")) content.put(new JSONObject().put("type", "input_image")
                            .put("image_url", item.getJSONObject("image_url").getString("url")));
                }
            }
            if (content.length() > 0) input.put(new JSONObject().put("role", role).put("content", content));
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null) for (int call = 0; call < calls.length(); call++) {
                JSONObject item = calls.getJSONObject(call);
                JSONObject function = item.getJSONObject("function");
                input.put(new JSONObject().put("type", "function_call").put("call_id", item.getString("id"))
                        .put("name", function.getString("name")).put("arguments", function.getString("arguments")));
            }
        }
        JSONObject request = new JSONObject().put("model", chat.getString("model")).put("instructions", instructions.toString())
                .put("input", input).put("stream", true).put("store", false)
                .put("include", new JSONArray().put("reasoning.encrypted_content"));
        JSONArray supplied = chat.optJSONArray("tools");
        if (supplied != null && supplied.length() > 0) {
            JSONArray tools = new JSONArray();
            for (int index = 0; index < supplied.length(); index++) {
                JSONObject function = supplied.getJSONObject(index).getJSONObject("function");
                tools.put(new JSONObject().put("type", "function").put("name", function.getString("name"))
                        .put("description", function.optString("description", "")).put("parameters", function.getJSONObject("parameters")));
            }
            request.put("tools", tools).put("tool_choice", "auto");
        }
        return request;
    }

    public static JSONObject completion(JSONObject response) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) throw new IllegalArgumentException("Codex 响应缺少 output");
        StringBuilder text = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (int index = 0; index < output.length(); index++) {
            JSONObject item = output.getJSONObject(index);
            if (item.optString("type").equals("function_call")) calls.put(new JSONObject().put("id", item.getString("call_id"))
                    .put("type", "function").put("function", new JSONObject().put("name", item.getString("name")).put("arguments", item.getString("arguments"))));
            if (item.optString("type").equals("message")) {
                JSONArray parts = item.optJSONArray("content");
                if (parts != null) for (int part = 0; part < parts.length(); part++) {
                    JSONObject value = parts.getJSONObject(part);
                    if (value.optString("type").equals("output_text")) text.append(value.optString("text", ""));
                }
            }
        }
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.length() == 0 ? JSONObject.NULL : text.toString());
        message.put("codex_output", output);
        if (calls.length() > 0) message.put("tool_calls", calls);
        if (text.length() == 0 && calls.length() == 0) throw new IllegalArgumentException("Codex 返回空结果");
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", message)
                .put("finish_reason", calls.length() == 0 ? "stop" : "tool_calls")));
    }
}
