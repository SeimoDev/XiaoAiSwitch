package io.github.xiaoaiswitch.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DialogueProtocol {
    private DialogueProtocol() {}

    public record Task(String id, String route, String text, String image) {}

    public static JSONObject request(Profile profile, JSONArray history, String text, String image, boolean images, boolean control) {
        JSONObject request = ChatProtocol.request(profile, history, text);
        if (!image.isEmpty()) {
            JSONArray messages = request.getJSONArray("messages");
            messages.put(messages.length() - 1, ControlProtocol.visualMessage(text, image));
        }
        JSONArray tools = new JSONArray();
        if (images) tools.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "generate_image").put("description", "当用户要求画图或修改图片时调用绘图模型。input 表示修改本次附件，previous 表示修改上次生成的图，none 表示文生图。")
                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("prompt", new JSONObject().put("type", "string"))
                        .put("image", new JSONObject().put("type", "string").put("enum", new JSONArray().put("none").put("input").put("previous"))))
                        .put("required", new JSONArray().put("prompt")).put("additionalProperties", false))));
        if (control) tools.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "control_phone").put("description", "用户要求实际操作手机或应用时，把完整任务交给手机控制模型执行；普通知识问答不需要此工具。")
                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("task", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("task")).put("additionalProperties", false))));
        if (tools.length() > 0) request.put("tools", tools).put("tool_choice", "auto");
        return request;
    }

    public static List<Task> tasks(JSONObject response, boolean images, boolean control) {
        if (response.has("error")) throw new IllegalArgumentException("对话模型返回错误");
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray calls = message.optJSONArray("tool_calls");
        List<Task> result = new ArrayList<>();
        if (calls == null) return result;
        for (int index = 0; index < calls.length(); index++) {
            JSONObject call = calls.getJSONObject(index);
            String id = string(call, "id");
            JSONObject function = call.getJSONObject("function");
            JSONObject arguments = new JSONObject(string(function, "arguments"));
            String name = string(function, "name");
            if (name.equals("generate_image") && images) {
                String image = arguments.has("image") ? string(arguments, "image") : "none";
                if (!image.equals("none") && !image.equals("input") && !image.equals("previous")) {
                    throw new IllegalArgumentException("未知图片引用");
                }
                result.add(new Task(id, "image", string(arguments, "prompt"), image));
                continue;
            }
            if (name.equals("control_phone") && control) {
                result.add(new Task(id, "control", string(arguments, "task"), "none"));
                continue;
            }
            throw new IllegalArgumentException("对话模型请求了未知或未启用的工具");
        }
        return result;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof String text && !text.trim().isEmpty()) return text;
        throw new IllegalArgumentException(key + " 需要非空字符串");
    }
}
