package io.github.xiaoaiswitch.core;

import java.util.UUID;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AivsProtocol {
    private AivsProtocol() {}
    public static final String MARKDOWN_ID_PREFIX = "xiaoaiswitch-markdown-";

    public static JSONArray textResponse(String dialog, String query, String text) {
        return new JSONArray()
                .put(instruction(dialog, "Nlp", "StartAnswer", new JSONObject()))
                .put(instruction(dialog, "Nlp", "EventACK", new JSONObject().put("status", 0)))
                .put(instruction(dialog, "Template", "Toast", new JSONObject().put("text", text).put("query", query)))
                .put(instruction(dialog, "Nlp", "FinishAnswer", new JSONObject()))
                .put(instruction(dialog, "Dialog", "Finish", new JSONObject()));
    }

    public static JSONObject instruction(String dialog, String namespace, String name, JSONObject payload) {
        return new JSONObject().put("header", new JSONObject().put("id",
                        (namespace.equals("Template") && name.equals("Toast") ? MARKDOWN_ID_PREFIX : "") + UUID.randomUUID())
                .put("namespace", namespace).put("name", name).put("dialog_id", dialog)).put("payload", payload);
    }

    public static JSONArray imageResponse(String dialog, String query, List<String> sources) {
        JSONArray items = new JSONArray();
        for (String source : sources) {
            items.put(new JSONObject().put("img", new JSONObject().put("description", query)
                    .put("sources", new JSONArray().put(new JSONObject().put("url", source)))
                    .put("launcher", new JSONObject().put("url", source).put("intent", new JSONObject()
                            .put("type", "activity").put("pkg_name", "com.miui.voiceassist")
                            .put("uri", "xiaoaiswitch-image://preview")))));
        }
        JSONArray response = textResponse(dialog, query, "绘图结果");
        response.put(2, instruction(dialog, "Template", "ImageContentGallery",
                new JSONObject().put("title", "绘图结果").put("items", items)));
        return response;
    }

    public static JSONArray richResponse(String dialog, String query, String text, List<String> sources) {
        JSONArray response = imageResponse(dialog, query, sources);
        JSONArray result = new JSONArray();
        for (int index = 0; index < response.length(); index++) {
            if (index == 3) result.put(instruction(dialog, "Template", "Toast", new JSONObject().put("text", text).put("query", query)));
            result.put(response.getJSONObject(index));
        }
        return result;
    }
}
