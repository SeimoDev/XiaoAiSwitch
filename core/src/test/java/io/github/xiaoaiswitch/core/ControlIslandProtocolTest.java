package io.github.xiaoaiswitch.core;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ControlIslandProtocolTest {
    @Test public void expandedCardHasOneTextActionAndNoAutoExpansion() {
        JSONObject metadata = ControlIslandProtocol.params("等待重试 1/3").getJSONObject("param_v2");
        assertEquals(1, metadata.getInt("protocol"));
        assertFalse(metadata.getBoolean("islandFirstFloat"));
        assertFalse(metadata.getBoolean("enableFloat"));
        assertNotEquals("UI Agent", metadata.getString("business"));
        assertEquals("等待重试 1/3", metadata.getJSONObject("iconTextInfo").getString("content"));
        JSONArray actions = metadata.getJSONArray("actions");
        assertEquals(1, actions.length());
        JSONObject stop = actions.getJSONObject(0);
        assertEquals(ControlIslandProtocol.STOP_KEY, stop.getString("action"));
        assertEquals(2, stop.getInt("type"));
        assertEquals("停止控制", stop.getString("actionTitle"));
        assertTrue(stop.getBoolean("clickWithCollapse"));
        assertTrue(metadata.getJSONObject("param_island").has("smallIslandArea"));
        assertTrue(metadata.getJSONObject("param_island").has("bigIslandArea"));
        assertTrue(metadata.toString().getBytes(StandardCharsets.UTF_8).length < 3072);
    }
}
