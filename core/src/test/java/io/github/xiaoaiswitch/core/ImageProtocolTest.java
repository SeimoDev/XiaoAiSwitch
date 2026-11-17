package io.github.xiaoaiswitch.core;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ImageProtocolTest {
    @Test public void supportsUrlsAndBase64WithoutPretendingTheyAreChatMessages() {
        assertEquals(List.of("https://HOST/image.png?signature=TOKEN"), ImageProtocol.sources(new JSONObject()
                .put("data", new JSONArray().put(new JSONObject().put("url", "https://HOST/image.png?signature=TOKEN")))));
        assertEquals(List.of("data:image/png;base64,AQID"), ImageProtocol.sources(new JSONObject()
                .put("data", new JSONArray().put(new JSONObject().put("b64_json", "AQID")))));
    }

    @Test public void rejectsErrorsEmptyInvalidAndLocalFileSources() {
        assertThrows(IllegalArgumentException.class, () -> ImageProtocol.sources(new JSONObject().put("error", "bad")));
        assertThrows(IllegalArgumentException.class, () -> ImageProtocol.sources(new JSONObject().put("data", new JSONArray())));
        assertThrows(IllegalArgumentException.class, () -> ImageProtocol.sources(new JSONObject().put("data", "not array")));
        for (JSONObject entry : List.of(new JSONObject(), new JSONObject().put("b64_json", "!bad!"),
                new JSONObject().put("url", "file:///TARGET"), new JSONObject().put("url", "not a URL"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImageProtocol.sources(new JSONObject().put("data", new JSONArray().put(entry))));
        }
    }

    @Test public void nativeGalleryIncludesImageDescriptionSourceAndPreviewLauncher() {
        JSONArray instructions = AivsProtocol.imageResponse("DIALOG", "画一只猫", List.of("https://HOST/image.png"));
        JSONObject gallery = instructions.getJSONObject(2);
        assertEquals("ImageContentGallery", gallery.getJSONObject("header").getString("name"));
        JSONObject image = gallery.getJSONObject("payload").getJSONArray("items").getJSONObject(0).getJSONObject("img");
        assertEquals("画一只猫", image.getString("description"));
        assertEquals("https://HOST/image.png", image.getJSONArray("sources").getJSONObject(0).getString("url"));
        assertEquals("xiaoaiswitch-image://preview", image.getJSONObject("launcher").getJSONObject("intent").getString("uri"));
        assertEquals("https://HOST/image.png", image.getJSONObject("launcher").getString("url"));
        assertEquals("FinishAnswer", instructions.getJSONObject(3).getJSONObject("header").getString("name"));
        assertEquals("Finish", instructions.getJSONObject(4).getJSONObject("header").getString("name"));
    }
}
