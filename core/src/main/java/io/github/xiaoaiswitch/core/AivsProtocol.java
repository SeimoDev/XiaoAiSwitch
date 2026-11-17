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

    public static JSONArray streamStart(String dialog) {
        return new JSONArray()
                .put(instruction(dialog, "Nlp", "StartStream", new JSONObject()))
                .put(instruction(dialog, "Nlp", "EventACK", new JSONObject().put("status", 0)));
    }

    public static JSONArray streamDelta(String dialog, String delta) {
        // The assistant reserves this exact packet as a stream terminator.
        return new JSONArray().put(instruction(dialog, "Template", "ToastStream",
                new JSONObject().put("markdown_text", delta.equals("<FINAL>") ? "&lt;FINAL&gt;" : delta)));
    }

    public static JSONArray streamEnd(String dialog, String query, List<String> sources) {
        JSONArray result = new JSONArray().put(instruction(dialog, "Nlp", "FinishStream", new JSONObject()));
        if (!sources.isEmpty()) result.put(gallery(dialog, query, sources));
        return result.put(instruction(dialog, "Dialog", "Finish", new JSONObject()));
    }

    public static JSONObject instruction(String dialog, String namespace, String name, JSONObject payload) {
        return new JSONObject().put("header", new JSONObject().put("id",
                        (namespace.equals("Template") && name.equals("Toast") ? MARKDOWN_ID_PREFIX : "") + UUID.randomUUID())
                .put("namespace", namespace).put("name", name).put("dialog_id", dialog)).put("payload", payload);
    }

    public static JSONArray imageResponse(String dialog, String query, List<String> sources) {
        JSONArray response = textResponse(dialog, query, "绘图结果");
        return response.put(2, gallery(dialog, query, sources));
    }

    private static JSONObject gallery(String dialog, String query, List<String> sources) {
        JSONArray items = new JSONArray();
        for (String source : sources) {
            items.put(new JSONObject().put("img", new JSONObject().put("description", query)
                    .put("sources", new JSONArray().put(new JSONObject().put("url", source)))
                    .put("launcher", new JSONObject().put("url", source).put("intent", new JSONObject()
                            .put("type", "activity").put("pkg_name", "com.miui.voiceassist")
                            .put("uri", "xiaoaiswitch-image://preview")))));
        }
        return instruction(dialog, "Template", "ImageContentGallery",
                new JSONObject().put("title", "绘图结果").put("items", items));
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
