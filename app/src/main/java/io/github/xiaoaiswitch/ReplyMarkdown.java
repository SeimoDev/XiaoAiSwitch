package io.github.xiaoaiswitch;

import android.content.Context;
import android.text.Spanned;
import android.text.method.MovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.AivsProtocol;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Reuses the installed assistant's renderer only for module-owned Toast cards. */
final class ReplyMarkdown {
    private static final String OWNER = "xiaoaiswitch.markdown.owner";
    private static final String VIEW = "xiaoaiswitch.markdown.view";
    private static final String MOVEMENT = "xiaoaiswitch.markdown.movement";
    private static final String VIEW_OWNER = "xiaoaiswitch.markdown.bound-card";
    private record SavedMovement(MovementMethod method) {}

    private ReplyMarkdown() {}

    static void install(ClassLoader loader, AssistantVersion.Markdown mapping) {
        for (String method : new String[]{mapping.fullCardFactory(), mapping.floatingCardFactory()}) {
            XposedHelpers.findAndHookMethod(mapping.operation(), loader, method, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() == null) return;
                    String id = (String) XposedHelpers.callMethod(param.thisObject, "getId");
                    if (id != null && id.startsWith(AivsProtocol.MARKDOWN_ID_PREFIX)) {
                        XposedHelpers.setAdditionalInstanceField(param.getResult(), OWNER, id);
                    }
                }
            });
        }
        Class<?> holder = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$ViewHolder", loader);
        hookCard(loader, holder, mapping.fullCard(), "getText", false, mapping.renderer());
        hookCard(loader, holder, "com.xiaomi.voiceassistant.instruction.card.stream.b", "getToastText", true, mapping.renderer());
    }

    private static void hookCard(ClassLoader loader, Class<?> holder, String name, String getter, boolean flow, String renderer) {
        XposedHelpers.findAndHookMethod(name, loader, "bindView", Context.class, holder, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                TextView target = view(param.args[1], flow);
                if (target == null) return;
                SavedMovement saved = (SavedMovement) XposedHelpers.removeAdditionalInstanceField(target, MOVEMENT);
                if (saved != null) target.setMovementMethod(saved.method());
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                TextView target = view(param.args[1], flow);
                if (target == null) return;
                XposedHelpers.setAdditionalInstanceField(target, VIEW_OWNER, new WeakReference<>(param.thisObject));
                if (!owned(param.thisObject)) return;
                XposedHelpers.setAdditionalInstanceField(param.thisObject, VIEW, new WeakReference<>(target));
                render(param.thisObject, target, getter, loader, renderer);
            }
        });
        if (!flow) return;
        for (String method : new String[]{"updateCardText", "updateToastText"}) {
            XposedHelpers.findAndHookMethod(name, loader, method, String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!owned(param.thisObject)) return;
                    Object stored = XposedHelpers.getAdditionalInstanceField(param.thisObject, VIEW);
                    if (!(stored instanceof WeakReference<?> reference) || !(reference.get() instanceof TextView target)) return;
                    Object owner = XposedHelpers.getAdditionalInstanceField(target, VIEW_OWNER);
                    if (!(owner instanceof WeakReference<?> bound) || bound.get() != param.thisObject) return;
                    render(param.thisObject, target, getter, loader, renderer);
                }
            });
        }
    }

    private static TextView view(Object holder, boolean flow) {
        View root = (View) XposedHelpers.getObjectField(holder, "itemView");
        return root.findViewById(root.getResources().getIdentifier(flow ? "template_toast_text" : "txv_text", "id", "com.miui.voiceassist"));
    }

    private static boolean owned(Object card) {
        String value = id(card);
        return value != null && value.startsWith(AivsProtocol.MARKDOWN_ID_PREFIX);
    }

    private static String id(Object card) {
        Object source = XposedHelpers.getAdditionalInstanceField(card, OWNER);
        if (source instanceof String value) return value;
        return (String) XposedHelpers.callMethod(card, "getId");
    }

    private static void render(Object card, TextView target, String getter, ClassLoader loader, String rendererClass) {
        String text = (String) XposedHelpers.callMethod(card, getter);
        if (!formatted(text)) return;
        try {
            if (XposedHelpers.getAdditionalInstanceField(target, MOVEMENT) == null) {
                XposedHelpers.setAdditionalInstanceField(target, MOVEMENT, new SavedMovement(target.getMovementMethod()));
            }
            Object manager = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(rendererClass, loader), "a");
            Object renderer = XposedHelpers.callMethod(manager, "getMarkwon", target.getContext(), false, false);
            XposedHelpers.callMethod(renderer, "setMarkdown", target, text);
            if (target.getText() instanceof Spanned spanned) {
                Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
                Log.i("XiaoAiSwitch", "markdown-rendered id=" + id(card)
                        + " spans=" + spans.length + " types=" + Arrays.stream(spans).map(span -> span.getClass().getName())
                        .distinct().sorted().collect(Collectors.joining(",")));
            }
        } catch (Throwable error) {
            target.setText(text);
            SavedMovement saved = (SavedMovement) XposedHelpers.removeAdditionalInstanceField(target, MOVEMENT);
            if (saved != null) target.setMovementMethod(saved.method());
            Log.w("XiaoAiSwitch", "markdown-render-failed " + error.getClass().getSimpleName());
        }
    }

    private static boolean formatted(String text) {
        if (text == null) return false;
        String value = text.trim();
        if (value.isEmpty()) return false;
        return text.indexOf('\n') >= 0 || text.indexOf('*') >= 0 || text.indexOf('`') >= 0 || text.indexOf('$') >= 0
                || value.startsWith("#") || value.startsWith(">") || value.startsWith("- ") || value.startsWith("+ ")
                || value.startsWith("_") || text.contains(" _") || text.contains("~~") || text.contains("](")
                || text.contains("://") || text.contains("\\(") || text.contains("\\[")
                || Character.isDigit(value.charAt(0)) && value.contains(". ");
    }
}
