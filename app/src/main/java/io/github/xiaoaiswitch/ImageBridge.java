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
    }

    private void remember(String id, String path) {
        if (id.isEmpty() || path.isEmpty()) return;
        synchronized (paths) {
            paths.put(id, path);
            while (paths.size() > 64) paths.remove(paths.keySet().iterator().next());
        }
    }

    Input inspect(Object event) {
        String name = (String) XposedHelpers.callMethod(event, "getFullName");
        if (name.equals("MultiModal.ImageGeneration")) {
            Object ids = value(XposedHelpers.callMethod(XposedHelpers.callMethod(event, "getPayload"), "getImageIds"));
            List<String> result = new ArrayList<>();
            if (ids != null) for (Object id : (List<?>) ids) result.add((String) id);
            return new Input(result, !result.isEmpty(), true);
        }
        Object contexts = XposedHelpers.callMethod(event, "getContexts");
        if (!(contexts instanceof List<?> list)) return NONE;
        for (Object nativeContext : list) {
            if (!"MultiModal.MultiModalState".equals(XposedHelpers.callMethod(nativeContext, "getFullName"))) continue;
            Object state = XposedHelpers.callMethod(nativeContext, "getPayload");
            Object scene = value(XposedHelpers.callMethod(state, "getGenerateImageScene"));
            String kind = scene == null ? "" : scene.toString();
            Object info = value(XposedHelpers.callMethod(state, "getImageInfo"));
            String id = info == null ? null : (String) value(XposedHelpers.callMethod(info, "getImageId"));
            if (id == null) id = (String) value(XposedHelpers.callMethod(state, "getImageId"));
            return new Input(id == null || id.isEmpty() ? Collections.emptyList() : Collections.singletonList(id),
                    kind.equals("EDIT_IMG"), kind.equals("EDIT_IMG") || kind.equals("CREATE_IMG"));
        }
        return NONE;
    }

    String query(Object event) {
        Object query = XposedHelpers.callMethod(XposedHelpers.callMethod(event, "getPayload"), "getQuery");
        return "MultiModal.ImageGeneration".equals(XposedHelpers.callMethod(event, "getFullName")) ? (String) value(query) : (String) query;
    }

    List<ModelClient.Image> load(Input input, ModelClient.Cancellation cancellation) throws Exception {
        List<ModelClient.Image> result = new ArrayList<>();
        for (String id : input.ids()) {
            String path = (String) XposedHelpers.callStaticMethod(XposedHelpers.findClass(mapping.preferences(), loader), "getString", id, "");
            if (path.isEmpty()) path = paths.get(id);
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
        return Boolean.TRUE.equals(XposedHelpers.callMethod(optional, "isPresent")) ? XposedHelpers.callMethod(optional, "get") : null;
    }
}
