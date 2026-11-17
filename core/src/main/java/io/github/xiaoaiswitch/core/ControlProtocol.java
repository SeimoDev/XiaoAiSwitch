package io.github.xiaoaiswitch.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Module-owned tool protocol; this is not the Xiaomi cloud's private agent protocol. */
public final class ControlProtocol {
    private ControlProtocol() {}

    public interface Action { String id(); }
    public record OpenApp(String id, String packageName) implements Action {}
    public record Tap(String id, double x, double y, int duration) implements Action {}
    public record Swipe(String id, double x, double y, double x2, double y2, int duration) implements Action {}
    public record TypeText(String id, String text) implements Action {}
    public record PressKey(String id, String key) implements Action {}
    public record Screenshot(String id) implements Action {}

    public static JSONArray initialMessages(Profile profile, String text, String screen) {
        JSONArray messages = new JSONArray();
        if (!profile.systemPrompt().isEmpty()) messages.put(ChatProtocol.message("system", profile.systemPrompt()));
        if (screen.isEmpty()) return messages.put(ChatProtocol.message("user", text));
        return messages.put(visualMessage(text, screen));
    }

    public static JSONObject request(Profile profile, JSONArray messages) {
        JSONObject point = new JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1);
        JSONObject duration = new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5000);
        JSONArray tools = new JSONArray()
                .put(function("open_app", "打开指定 Android 包名的应用。", new JSONObject()
                        .put("package", new JSONObject().put("type", "string")), "package"))
                .put(function("tap", "点击当前屏幕坐标；x、y 为从左上角起的 0 到 1 比例。duration_ms 可用于长按。", new JSONObject()
                        .put("x", point).put("y", point).put("duration_ms", duration), "x", "y"))
                .put(function("swipe", "在当前屏幕滑动，起点终点均为 0 到 1 比例坐标。", new JSONObject()
                        .put("x", point).put("y", point).put("x2", point).put("y2", point).put("duration_ms", duration), "x", "y", "x2", "y2"))
                .put(function("type_text", "向已获得焦点的输入框输入文本。", new JSONObject()
                        .put("text", new JSONObject().put("type", "string")), "text"))
                .put(function("press_key", "按系统导航键或回车。", new JSONObject().put("key", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("back").put("home").put("recents").put("enter"))), "key"))
                .put(function("screenshot", "读取当前屏幕截图以判断下一步；操作结果不等同于任务已完成。", new JSONObject()));
        return new JSONObject().put("model", profile.model()).put("stream", false).put("messages", messages)
                .put("tools", tools).put("tool_choice", "auto");
    }

    public static List<Action> actions(JSONObject response) {
        if (response.has("error")) throw new IllegalArgumentException("控制模型返回错误");
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray calls = message.optJSONArray("tool_calls");
        List<Action> result = new ArrayList<>();
        if (calls == null) return result;
        for (int index = 0; index < calls.length(); index++) {
            JSONObject call = calls.getJSONObject(index);
            String id = string(call, "id");
            JSONObject function = call.getJSONObject("function");
            JSONObject arguments = new JSONObject(string(function, "arguments"));
            result.add(switch (string(function, "name")) {
                case "open_app" -> {
                    String app = string(arguments, "package");
                    if (!app.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                        throw new IllegalArgumentException("应用包名格式错误");
                    }
                    yield new OpenApp(id, app);
                }
                case "tap" -> new Tap(id, number(arguments, "x", 0, 1), number(arguments, "y", 0, 1), duration(arguments, 100));
                case "swipe" -> new Swipe(id, number(arguments, "x", 0, 1), number(arguments, "y", 0, 1),
                        number(arguments, "x2", 0, 1), number(arguments, "y2", 0, 1), duration(arguments, 350));
                case "type_text" -> new TypeText(id, string(arguments, "text"));
                case "press_key" -> {
                    String key = string(arguments, "key");
                    if (!key.equals("back") && !key.equals("home") && !key.equals("recents") && !key.equals("enter")) {
                        throw new IllegalArgumentException("不支持的导航键");
                    }
                    yield new PressKey(id, key);
                }
                case "screenshot" -> new Screenshot(id);
                default -> throw new IllegalArgumentException("未知手机控制工具");
            });
        }
        return result;
    }

    public static JSONObject toolResult(String id, JSONObject result) {
        return new JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result.toString());
    }

    public static JSONObject visualMessage(String text, String screen) {
        return new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", text))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", screen))));
    }

    private static JSONObject function(String name, String description, JSONObject properties, String... required) {
        JSONArray fields = new JSONArray();
        for (String key : required) fields.put(key);
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)
                .put("description", description).put("parameters", new JSONObject().put("type", "object")
                        .put("properties", properties).put("required", fields).put("additionalProperties", false)));
    }

    private static double number(JSONObject object, String key, double min, double max) {
        Object value = object.opt(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max) {
            throw new IllegalArgumentException(key + " 数值范围错误");
        }
        return number.doubleValue();
    }

    private static int duration(JSONObject arguments, int fallback) {
        if (!arguments.has("duration_ms")) return fallback;
        double value = number(arguments, "duration_ms", 1, 5000);
        if (value != (int) value) throw new IllegalArgumentException("duration_ms 必须是整数");
        return (int) value;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof String text && !text.isEmpty()) return text;
        throw new IllegalArgumentException(key + " 需要非空字符串");
    }
}
