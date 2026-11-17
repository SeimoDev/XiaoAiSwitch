package io.github.xiaoaiswitch;

import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.ModelClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class TestImageBridge {
    private static int failures;
    private static int cases;
    public static void main(String[] args) throws Throwable {
        check("understand raw image_ids", () -> {
            ImageBridge.Input input = bridge(false).inspect(new Event("MultiModal.ImageUnderstand",
                    new Understand(Arrays.asList(null, "", "photo-id"), "Describe this image"), List.of()));
            require(input.ids().equals(List.of("photo-id")) && !input.editing() && !input.drawing());
        });
        check("understand optional query", () -> require("Describe this image".equals(bridge(false).query(
                new Event("MultiModal.ImageUnderstand", new Understand(List.of("photo-id"), "Describe this image"), List.of())))));
        check("understand keeps only its own attachments", () -> {
            ImageBridge.Input input = bridge(true).inspect(new Event("MultiModal.ImageUnderstand",
                    new Understand(List.of("first", "second"), "Describe both"),
                    List.of(new NativeContext(new State("EDIT_IMG", "old-drawing")))));
            require(input.ids().equals(List.of("first", "second")) && !input.editing() && !input.drawing());
        });
        check("stock screen access checks and routed OCR remain native", () -> {
            ImageBridge bridge = bridge(true);
            require(bridge.isAttachmentQuery(new Event("MultiModal.ImageUnderstand",
                    new Understand(List.of("photo"), "Describe it"), List.of())));
            require(!bridge.isAttachmentQuery(new Event("MultiModal.ImageUnderstand",
                    new Understand(List.of("screen"), null, 0), List.of())));
            require(!bridge.isAttachmentQuery(new Event("MultiModal.ImageUnderstand",
                    new Understand(List.of("screen"), null), List.of(new RouteContext()))));
        });
        check("required NLP query", () -> require("hello".equals(bridge(false).query(
                new Event("Nlp.Request", new Nlp("hello"), List.of())))));
        check("drawing optional image_ids unchanged", () -> {
            ImageBridge.Input input = bridge(false).inspect(new Event("MultiModal.ImageGeneration",
                    new Generation(List.of("edit-id")), List.of()));
            require(input.ids().equals(List.of("edit-id")) && input.editing() && input.drawing());
        });
        check("all native states examined", () -> {
            ImageBridge.Input input = bridge(false).inspect(event(new State(null, null), new State(null, "photo-id")));
            require(input.ids().equals(List.of("photo-id")) && !input.drawing());
        });
        check("drawing after upload metadata state", () -> {
            ImageBridge.Input input = bridge(false).inspect(event(new State(null, null), new State("CREATE_IMG", null)));
            require(input.ids().isEmpty() && input.drawing() && !input.editing());
        });
        check("duplicate native ids removed", () -> require(bridge(false).inspect(event(
                new State("EDIT_IMG", "photo-id"), new State(null, "photo-id"))).ids().equals(List.of("photo-id"))));
        check("no attachment remains NONE", () -> require(bridge(false).inspect(event()).equals(ImageBridge.NONE)));
        Path photo = Files.createTempFile("xiaoai-vision-fixture-", ".png");
        try {
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");
            Files.write(photo, png);
            for (boolean v8 : List.of(false, true)) check("ordinary native chain maps photo V" + (v8 ? 8 : 7), () -> {
                ImageBridge bridge = bridge(v8);
                bridge.install();
                XposedHelpers.after(v8 ? "dp1.d3" : "zj0.m1", "setMultiModalChain", new Chain(photo.toString(), "native-id"));
                List<ModelClient.Image> result = bridge.load(new ImageBridge.Input(List.of("native-id"), false, false), new ModelClient.Cancellation());
                require(result.size() == 1 && Arrays.equals(png, result.get(0).bytes()));
            });
            for (boolean v8 : List.of(false, true)) check("persisted native image cache V" + (v8 ? 8 : 7), () -> {
                ImageCache.paths.put("cached-id", photo.toString());
                List<ModelClient.Image> result = bridge(v8).load(new ImageBridge.Input(List.of("cached-id"), false, false), new ModelClient.Cancellation());
                require(result.size() == 1 && Arrays.equals(png, result.get(0).bytes()));
            });
        } finally { Files.deleteIfExists(photo); }
        System.out.println("cases=" + cases + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
    static ImageBridge bridge(boolean v8) {
        return new ImageBridge(null, TestImageBridge.class.getClassLoader(), new AssistantVersion.Images(
                v8 ? "sj1.b" : "vf0.a", "fixture.preferences", "fixture.gallery", "fixture.glide"));
    }
    static Event event(State... states) {
        return new Event("Nlp.Request", new Nlp("describe"), Arrays.stream(states).map(NativeContext::new).toList());
    }
    static void require(boolean value) { if (!value) throw new AssertionError("production result mismatch"); }
    interface Checked { void run() throws Throwable; }
    static void check(String name, Checked test) {
        cases++;
        try { test.run(); System.out.println("PASS " + name); }
        catch (Throwable error) { failures++; System.out.println("FAIL " + name + " " + error); }
    }
    public record OptionalValue(Object value) {
        public boolean isPresent() { return value != null; }
        public Object get() { return value; }
    }
    public record Event(String name, Object payload, List<?> contexts) {
        public String getFullName() { return name; }
        public String getId() { return "fixture-event"; }
        public Object getPayload() { return payload; }
        public List<?> getContexts() { return contexts; }
    }
    public record Understand(List<String> ids, String query, Integer accessCode) {
        Understand(List<String> ids, String query) { this(ids, query, null); }
        public List<String> getImageIds() { return ids; }
        public OptionalValue getImageContents() { return new OptionalValue(null); }
        public OptionalValue getQuery() { return new OptionalValue(query); }
        public OptionalValue getCheckAccessResultCode() { return new OptionalValue(accessCode); }
    }
    public static final class RouteContext { public String getFullName() { return "Sys.EventRoute"; } }
    public record Generation(List<String> ids) { public OptionalValue getImageIds() { return new OptionalValue(ids); } }
    public record Nlp(String query) { public String getQuery() { return query; } }
    public record NativeContext(State state) {
        public String getFullName() { return "MultiModal.MultiModalState"; }
        public State getPayload() { return state; }
    }
    public record State(String scene, String id) {
        public OptionalValue getGenerateImageScene() { return new OptionalValue(scene); }
        public OptionalValue getImageInfo() { return new OptionalValue(new Info(id)); }
        public OptionalValue getImageId() { return new OptionalValue(null); }
    }
    public record Info(String id) {
        public OptionalValue getImageId() { return new OptionalValue(id); }
        public OptionalValue getBase64Data() { return new OptionalValue(null); }
    }
    public record Chain(String path, String id) {
        public InputInfo getMultiModalInputInfo() { return new InputInfo(path); }
        public OutputInfo getMultiModalOutputInfo() { return new OutputInfo(id); }
    }
    public record InputInfo(String path) { public InputData getInputData() { return new InputData(path); } }
    public record InputData(String path) { public String getFilePath() { return path; } }
    public record OutputInfo(String id) { public OutputData getOutputData() { return new OutputData(id); } }
    public record OutputData(String id) { public Media getMultimodalMediaEntity() { return new Media(id); } }
    public record Media(String id) { public String getFileId() { return id; } }
    public static final class Preferences { public static String getString(String id, String fallback) { return fallback; } }
    public static final class ImageCache {
        public static final Map<String, String> paths = new LinkedHashMap<>();
        public static String getImageCacheIndex(String id) { return paths.get(id); }
    }
}
