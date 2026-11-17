package io.github.xiaoaiswitch.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ImageProtocol {
    private ImageProtocol() {}

    public static List<String> sources(JSONObject response) {
        if (response.has("error")) throw new IllegalArgumentException("绘图服务返回错误");
        JSONArray data = response.optJSONArray("data");
        if (data == null || data.length() == 0) throw new IllegalArgumentException("绘图响应缺少 data 图片列表");
        List<String> sources = new ArrayList<>();
        for (int index = 0; index < data.length(); index++) {
            JSONObject item = data.optJSONObject(index);
            if (item == null) throw new IllegalArgumentException("绘图列表项格式错误");
            String url = item.optString("url", "").trim();
            if (!url.isEmpty()) {
                URI uri = URI.create(url);
                if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException("图片 URL 需要 HTTP(S) 地址");
                }
                sources.add(url);
                continue;
            }
            String base64 = item.optString("b64_json", "");
            if (base64.isEmpty() || Base64.getDecoder().decode(base64).length == 0) {
                throw new IllegalArgumentException("图片缺少 url 或 b64_json");
            }
            sources.add("data:image/png;base64," + base64);
        }
        return sources;
    }
}
