package io.github.xiaoaiswitch.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** The published Focus notification fields used for one local control session. */
public final class ControlIslandProtocol {
    private ControlIslandProtocol() {}

    public static final String STOP_KEY = "miui.focus.action_close";
    public static final String STOP_LABEL = "停止控制";
    public static final String ICON_KEY = "miui.focus.pic_xiaoaiswitch_control";

    public static JSONObject params(String status) {
        JSONObject icon = new JSONObject().put("type", 1).put("pic", ICON_KEY);
        JSONObject stop = new JSONObject().put("action", STOP_KEY).put("type", 2)
                .put("actionTitle", STOP_LABEL).put("clickWithCollapse", true);
        JSONObject island = new JSONObject().put("islandProperty", 1).put("islandTimeout", 300)
                .put("highlightColor", "#3482FF")
                .put("smallIslandArea", new JSONObject().put("picInfo", icon))
                .put("bigIslandArea", new JSONObject().put("imageTextInfoLeft", new JSONObject().put("type", 1)
                        .put("picInfo", icon).put("textInfo", new JSONObject().put("title", "控制中").put("showHighlightColor", true))));
        return new JSONObject().put("param_v2", new JSONObject().put("protocol", 1)
                .put("business", "XiaoAiSwitch Control").put("islandFirstFloat", false).put("enableFloat", false)
                .put("isShowNotification", true).put("updatable", true).put("filterWhenNoPermission", false)
                .put("ticker", "控制中").put("tickerPic", ICON_KEY)
                .put("aodTitle", "正在控制手机").put("aodPic", ICON_KEY)
                .put("param_island", island)
                .put("iconTextInfo", new JSONObject().put("title", "正在控制手机").put("content", status)
                        .put("animIconInfo", new JSONObject().put("type", 0).put("src", ICON_KEY)))
                .put("actions", new JSONArray().put(stop)));
    }
}
