package io.github.xiaoaiswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.ModelClient;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ImageBridge {
    record Input(List<String> ids, boolean editing, boolean drawing) {}
    static final Input NONE = new Input(Collections.emptyList(), false, false);

    private final Context context;
    private final ClassLoader loader;
    private final AssistantVersion.Images mapping;
    private final Map<String, String> paths = Collections.synchronizedMap(new LinkedHashMap<>());

    ImageBridge(Context context, ClassLoader loader, AssistantVersion.Images mapping) {
        this.context = context;
        this.loader = loader;
        this.mapping = mapping;
    }

    void install() {
        XposedHelpers.findAndHookMethod(mapping.producer(), loader, "saveImageLocalPath", String.class, String.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { remember((String) param.args[0], (String) param.args[1]); }
        });
        XposedHelpers.findAndHookMethod(mapping.chainOwner(), loader, "setMultiModalChain",
                mapping.chain(), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { rememberChain(param.args[0]); }
        });
    }

    private void remember(String id, String path) {
        if (id == null || path == null || id.isEmpty() || path.isEmpty()) return;
        synchronized (paths) {
            paths.put(id, path);
            while (paths.size() > 64) paths.remove(paths.keySet().iterator().next());
        }
    }

    private void rememberChain(Object chain) {
        if (chain == null) return;
        Object output = XposedHelpers.callMethod(chain, "getMultiModalOutputInfo");
        if (output == null) return;
        Object data = XposedHelpers.callMethod(output, "getOutputData");
        if (data == null) return;
        Object media = XposedHelpers.callMethod(data, "getMultimodalMediaEntity");
        if (media == null) return;
        Object input = XposedHelpers.callMethod(chain, "getMultiModalInputInfo");
        if (input == null) return;
        Object source = XposedHelpers.callMethod(input, "getInputData");
        if (source == null) return;
        remember((String) XposedHelpers.callMethod(media, "getFileId"), (String) XposedHelpers.callMethod(source, "getFilePath"));
    }

    Input inspect(Object event) {
        String name = (String) XposedHelpers.callMethod(event, "getFullName");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        boolean drawing = name.equals("MultiModal.ImageGeneration");
        boolean understanding = name.equals("MultiModal.ImageUnderstand");
        boolean editing = false;
        if (drawing || understanding) {
            Object images = XposedHelpers.callMethod(XposedHelpers.callMethod(event, "getPayload"), "getImageIds");
            // ImageUnderstand.image_ids is required; ImageGeneration.image_ids is optional.
            Object values = drawing ? value(images) : images;
            if (values instanceof List<?> list) for (Object id : list) {
                if (id instanceof String text && !text.isEmpty()) ids.add(text);
            }
            if (understanding) return ids.isEmpty() ? NONE : new Input(List.copyOf(ids), false, false);
            editing = drawing && !ids.isEmpty();
        }
        Object contexts = XposedHelpers.callMethod(event, "getContexts");
        if (contexts instanceof List<?> list) for (Object nativeContext : list) {
            if (!"MultiModal.MultiModalState".equals(XposedHelpers.callMethod(nativeContext, "getFullName"))) continue;
            Object state = XposedHelpers.callMethod(nativeContext, "getPayload");
            Object scene = value(XposedHelpers.callMethod(state, "getGenerateImageScene"));
            String kind = scene == null ? "" : scene.toString();
            Object info = value(XposedHelpers.callMethod(state, "getImageInfo"));
            String id = info == null ? null : (String) value(XposedHelpers.callMethod(info, "getImageId"));
            if (id == null) id = (String) value(XposedHelpers.callMethod(state, "getImageId"));
            if (id != null && !id.isEmpty()) ids.add(id);
            editing |= kind.equals("EDIT_IMG");
            drawing |= kind.equals("EDIT_IMG") || kind.equals("CREATE_IMG");
        }
        return ids.isEmpty() && !drawing ? NONE : new Input(List.copyOf(ids), editing, drawing);
    }

    String query(Object event) {
        Object query = XposedHelpers.callMethod(XposedHelpers.callMethod(event, "getPayload"), "getQuery");
        return query instanceof String text ? text : (String) value(query);
    }

    boolean isAttachmentQuery(Object event) {
        Object payload = XposedHelpers.callMethod(event, "getPayload");
        // Screen access checks and linked OCR requests must follow the original assistant flow.
        if (value(XposedHelpers.callMethod(payload, "getCheckAccessResultCode")) != null) return false;
        Object contexts = XposedHelpers.callMethod(event, "getContexts");
        if (contexts instanceof List<?> list) for (Object item : list) {
            if ("Sys.EventRoute".equals(XposedHelpers.callMethod(item, "getFullName"))) return false;
        }
        return true;
    }

    List<ModelClient.Image> load(Input input, ModelClient.Cancellation cancellation) throws Exception {
        List<ModelClient.Image> result = new ArrayList<>();
        for (String id : input.ids()) {
            String path = paths.get(id);
            if (path == null || path.isEmpty()) path = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(mapping.preferences(), loader), "getString", id, "");
            // Ordinary attachments use image_cache_index, not the drawing module's default MMKV.
            if (path == null || path.isEmpty()) path = (String) XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    mapping.cacheIndex(), loader), "getImageCacheIndex", id);
            if (path == null || path.isEmpty()) throw new IllegalStateException("未找到原图，请重新选择图片");
            result.add(loadSource(path, cancellation));
        }
        if (input.editing() && result.isEmpty()) throw new IllegalStateException("图生图请求缺少原图");
        return result;
    }

    ModelClient.Image loadSource(String source, ModelClient.Cancellation cancellation) throws Exception {
        byte[] bytes;
        if (source.startsWith("data:image")) {
            bytes = Base64.decode(source.substring(source.indexOf(',') + 1), Base64.DEFAULT);
        } else if (source.startsWith("https://") || source.startsWith("http://")) {
            bytes = ModelClient.downloadImage(source, cancellation);
        } else {
            try (InputStream input = source.startsWith("content://") ? context.getContentResolver().openInputStream(Uri.parse(source))
                    : new FileInputStream(source.startsWith("file://") ? Uri.parse(source).getPath() : source)) {
                if (input == null) throw new IllegalStateException("原图未打开");
                bytes = ModelClient.read(input, cancellation);
            }
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return new ModelClient.Image(bytes, "image/png");
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) return new ModelClient.Image(bytes, "image/jpeg");
        if (bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return new ModelClient.Image(bytes, "image/webp");
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IllegalArgumentException("原图格式不受支持");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return new ModelClient.Image(output.toByteArray(), "image/png");
        } finally { bitmap.recycle(); }
    }

    static String dataUrl(ModelClient.Image image) { return "data:" + image.mime() + ";base64," + Base64.encodeToString(image.bytes(), Base64.NO_WRAP); }

    private Object value(Object optional) {
        if (optional == null) return null;
        return Boolean.TRUE.equals(XposedHelpers.callMethod(optional, "isPresent")) ? XposedHelpers.callMethod(optional, "get") : null;
    }
}
