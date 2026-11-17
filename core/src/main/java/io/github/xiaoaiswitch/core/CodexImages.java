package io.github.xiaoaiswitch.core;

import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Codex's subscription Images endpoint uses JSON image references, not multipart. */
public final class CodexImages {
    private CodexImages() {}
    public static final String DEFAULT_MODEL = "gpt-image-2";

    public static JSONObject request(Profile profile, String prompt, List<ModelClient.Image> images) {
        if (images.size() > 5) throw new IllegalArgumentException("Codex 图生图最多使用 5 张原图");
        JSONObject body = ChatProtocol.imageRequest(profile, prompt).put("background", "opaque")
                .put("quality", "auto").put("size", "auto");
        if (images.isEmpty()) return body;
        JSONArray originals = new JSONArray();
        for (ModelClient.Image image : images) {
            originals.put(new JSONObject().put("image_url", "data:" + image.mime() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.bytes())));
        }
        return body.put("images", originals);
    }
}
