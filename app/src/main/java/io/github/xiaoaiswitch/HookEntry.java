package io.github.xiaoaiswitch;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.core.content.pm.PackageInfoCompat;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.xiaoaiswitch.AssistantVersion.Feature;
import io.github.xiaoaiswitch.core.AivsProtocol;
import io.github.xiaoaiswitch.core.ChatProtocol;
import io.github.xiaoaiswitch.core.Profile;
import io.github.xiaoaiswitch.core.ModelClient;
import io.github.xiaoaiswitch.core.ImageProtocol;
import io.github.xiaoaiswitch.core.QueryRouter;
import io.github.xiaoaiswitch.core.ControlLoop;
import io.github.xiaoaiswitch.core.DialogueProtocol;
import io.github.xiaoaiswitch.core.DialogueLoop;
import io.github.xiaoaiswitch.core.DialogueHookDiscovery;
import io.github.xiaoaiswitch.core.DuplexTurns;
import io.github.xiaoaiswitch.core.MemoryCommand;
import io.github.xiaoaiswitch.core.CodexClient;
import io.github.xiaoaiswitch.core.CodexOAuth;
import io.github.xiaoaiswitch.core.ModeProfiles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.lang.reflect.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.miui.voiceassist".equals(load.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                PackageInfo host = null;
                try {
                    host = context.getPackageManager().getPackageInfo(load.packageName, 0);
                    Class<?> event = XposedHelpers.findClassIfExists("com.xiaomi.ai.api.common.Event", load.classLoader);
                    Class<?> instruction = XposedHelpers.findClassIfExists("com.xiaomi.ai.api.common.Instruction", load.classLoader);
                    AssistantVersion.Compatibility compatibility = AssistantVersion.resolve(PackageInfoCompat.getLongVersionCode(host),
                            host.versionName, load.classLoader, event, instruction, Handler.class, Context.class);
                    if (compatibility == null || !compatibility.exact()) {
                        int family = AssistantVersion.familyOf(host.versionName);
                        if (family == 0) reportCompatibility(context, host, "小爱版本不在兼容范围，保留原厂链路", null);
                        else discoverFeatures(context, host, load.classLoader, event, instruction, compatibility);
                        return;
                    }
                    EnumSet<Feature> features = new Runtime(context, load.classLoader, compatibility).install(event, instruction);
                    reportCompatibility(context, host, compatibility.exact() ? "已匹配内置版本" : "已自动匹配对话结构", features);
                } catch (Throwable error) {
                    String failure = "Hook 安装失败：" + error.getClass().getSimpleName();
                    if (host == null) report(context, failure);
                    else reportCompatibility(context, host, failure, null);
                    XposedBridge.log("XiaoAiSwitch: install " + error.getClass().getSimpleName());
                }
            }
        });
    }

    private static void discoverFeatures(Context context, PackageInfo host, ClassLoader loader, Class<?> event, Class<?> instruction,
                                         AssistantVersion.Compatibility baseline) {
        reportCompatibility(context, host, "正在本机查找各项 Hook，暂时保留原厂链路", null);
        new Thread(() -> {
            AssistantVersion.Compatibility compatibility = baseline;
            try {
                Bundle result = context.getContentResolver().call(ConfigProvider.URI, "dialogueLayout", null, null);
                if (result != null && result.getLong("version", -1) == PackageInfoCompat.getLongVersionCode(host)
                        && result.getLong("updatedAt", -1) == host.lastUpdateTime) {
                    var mapping = DialogueHookDiscovery.Layout.decode(result.getString("layout"));
                    AssistantVersion.Engine engine = baseline != null ? baseline.adapter().engine() : mapping == null ? null
                            : new AssistantVersion.Engine(mapping.owner(), mapping.post(), mapping.receive(), mapping.deliver(),
                            mapping.handler(), mapping.session(), "", "");
                    if (engine != null) {
                        var discovered = AssistantVersion.resolveDiscovered(host.versionName, engine,
                                LocalHookDiscovery.decodeSymbols(result.getString("symbols")), loader, event, instruction, Handler.class, Context.class);
                        if (discovered != null && (baseline == null || discovered.features().containsAll(baseline.features()))) {
                            compatibility = discovered;
                        }
                    }
                }
            } catch (RuntimeException | LinkageError error) {
                // A scan failure must leave the original assistant usable.
            }
            if (compatibility == null) {
                reportCompatibility(context, host, "本机未找到唯一且通过校验的对话 Hook，保留原厂链路", null);
                return;
            }
            var selected = compatibility;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    var features = new Runtime(context, loader, selected).install(event, instruction);
                    reportCompatibility(context, host, selected.discovered() ? "已在本机校验功能 Hook" : "已匹配对话结构", features);
                } catch (Throwable error) {
                    reportCompatibility(context, host, "本机 Hook 加载失败，保留原厂链路", null);
                }
            });
        }, "XiaoAiSwitch-HookDiscovery").start();
    }

    static void report(Context context, String text) {
        report(context, text, null);
    }

    private static void reportCompatibility(Context context, PackageInfo host, String status, EnumSet<Feature> features) {
        Bundle extras = new Bundle();
        extras.putInt("moduleVersion", features == null ? 0 : BuildConfig.VERSION_CODE);
        extras.putLong("assistantUpdateTime", host.lastUpdateTime);
        extras.putString("compatibility", status);
        ArrayList<String> matched = new ArrayList<>();
        if (features != null) for (Feature feature : features) matched.add(feature.name());
        extras.putStringArrayList("features", matched);
        report(context, status, extras);
    }

    private static void report(Context context, String text, Bundle extras) {
        try {
            context.getContentResolver().call(ConfigProvider.URI, "status", text, extras);
        } catch (RuntimeException error) {
            XposedBridge.log("XiaoAiSwitch: status provider " + error.getClass().getSimpleName());
        }
    }

    private static final class Runtime {
        private final Context context;
        private final ClassLoader loader;
        private final AssistantVersion adapter;
        private final boolean exact;
        private final boolean discovered;
        private final EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
        private final ExecutorService network = Executors.newFixedThreadPool(2);
        private final AtomicLong generation = new AtomicLong();
        private final AtomicReference<PhoneControl> activeControl = new AtomicReference<>();
        private final AtomicReference<ModelClient.Cancellation> runningHttp = new AtomicReference<>();
        private final Map<String, String> owned = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, JSONArray> histories = new LinkedHashMap<>();
        private final Map<String, PendingVoice> voiceInputs = Collections.synchronizedMap(new LinkedHashMap<>());
        private final ImageBridge images;
        private volatile DuplexVoice duplexVoice;
        private volatile String previousImage = "";
        private volatile boolean ready;
        private long revision;

        Runtime(Context context, ClassLoader loader, AssistantVersion.Compatibility compatibility) {
            this.context = context;
            this.loader = loader;
            adapter = compatibility.adapter();
            exact = compatibility.exact();
            discovered = compatibility.discovered();
            features.addAll(compatibility.features());
            images = new ImageBridge(context, loader, adapter.images());
        }

        EnumSet<Feature> install(Class<?> eventClass, Class<?> instructionClass) {
            AssistantVersion.Engine engine = adapter.engine();
            Class<?> engineClass = XposedHelpers.findClass(engine.type(), loader);
            XposedHelpers.findAndHookMethod(engineClass, engine.post(), eventClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ready) return;
                    try {
                        outbound(param);
                    } catch (Throwable error) {
                        report(context, "请求适配异常：" + error.getClass().getSimpleName());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(engineClass, engine.receive(), instructionClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ready) return;
                    try {
                        inbound(param);
                    } catch (Throwable error) {
                        report(context, "语音适配异常：" + error.getClass().getSimpleName());
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getObjectExtra("voice");
                    if (!(value instanceof VoiceRequest voice)) return;
                    if (useNativeMemory(voice.config()) && MemoryCommand.query(voice.text()) != null) {
                        forwardMemorySpeech(param.thisObject, voice);
                        return;
                    }
                    start(param.thisObject, voice.config(), voice.profile(), voice.route(), voice.dialog(), voice.text(), voice.text(), voice.input(), true);
                }
            });
            XposedHelpers.findAndHookMethod(engine.session(), loader, "clearSession", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (activeControl.get() == null) {
                        duplexVoice = null;
                        generation.incrementAndGet();
                        cancelPending();
                        previousImage = "";
                    }
                    synchronized (histories) { histories.clear(); }
                }
            });
            installOptional(Feature.ATTACHMENTS, images::install);
            installOptional(Feature.DRAWING, () -> installGallery(instructionClass));
            installOptional(Feature.MARKDOWN, () -> ReplyMarkdown.install(loader, adapter.markdown()));
            if (!discovered) installOptional("测试入口", () -> FixtureSupport.install(context, loader, adapter));
            ready = true;
            return features;
        }

        private void installOptional(Feature feature, Runnable install) {
            if (features.contains(feature) && !installOptional(feature.label, install)) features.remove(feature);
        }

        private boolean installOptional(String name, Runnable install) {
            try {
                install.run();
                return true;
            } catch (Throwable error) {
                XposedBridge.log("XiaoAiSwitch: " + name + " " + error.getClass().getSimpleName());
                return false;
            }
        }

        private void installGallery(Class<?> instructionClass) {
            AssistantVersion.Images mapping = adapter.images();
            XposedHelpers.findAndHookMethod(mapping.gallery(), loader, mapping.galleryOpen(),
                    XposedHelpers.findClass("com.xiaomi.ai.api.Template$ImageLauncher", loader), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String source = previewSource(param.args[0]);
                    if (source == null) return;
                    param.setResult(null);
                    try {
                        View view = (View) XposedHelpers.getObjectField(param.thisObject, "itemView");
                        Dialog dialog = new Dialog(view.getContext());
                        LinearLayout content = new LinearLayout(view.getContext());
                        content.setOrientation(LinearLayout.VERTICAL);
                        content.setPadding(24, 24, 24, 24);
                        TextView title = new TextView(view.getContext());
                        title.setText("绘图预览");
                        title.setTextSize(22);
                        content.addView(title);
                        ImageView image = new ImageView(view.getContext());
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        image.setContentDescription("自定义绘图预览");
                        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                        Button close = new Button(view.getContext());
                        close.setText("关闭");
                        close.setOnClickListener(ignored -> dialog.dismiss());
                        content.addView(close);
                        dialog.setContentView(content);
                        // Gallery view holders use the assistant's floating-window Context, not an Activity token.
                        ViewGroup.LayoutParams root = view.getRootView().getLayoutParams();
                        dialog.getWindow().setType(root instanceof WindowManager.LayoutParams window
                                ? window.type : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                        dialog.show();
                        dialog.getWindow().setLayout(view.getResources().getDisplayMetrics().widthPixels * 9 / 10,
                                view.getResources().getDisplayMetrics().heightPixels * 4 / 5);
                        Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(mapping.glide(), loader), "with", view.getContext());
                        Object request = XposedHelpers.callMethod(manager, "load", source);
                        XposedHelpers.callMethod(request, "into", image);
                        dialog.setOnDismissListener(ignored -> XposedHelpers.callMethod(manager, "clear", image));
                        report(context, "绘图预览已打开");
                    } catch (Throwable error) {
                        report(context, "绘图预览失败：" + error.getClass().getSimpleName());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(mapping.gallery(), loader, "init", instructionClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null) return;
                    Object payload = XposedHelpers.callMethod(param.args[0], "getPayload");
                    Object optional = XposedHelpers.callMethod(payload, "getItems");
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(optional, "isPresent"))) return;
                    List<?> items = (List<?>) XposedHelpers.callMethod(optional, "get");
                    if (items.isEmpty()) return;
                    Object image = XposedHelpers.callMethod(items.get(0), "getImg");
                    Object launcher = XposedHelpers.callMethod(image, "getLauncher");
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(launcher, "isPresent"))
                            || previewSource(XposedHelpers.callMethod(launcher, "get")) == null) return;
                    View view = (View) XposedHelpers.getObjectField(param.thisObject, "itemView");
                    int id = view.getResources().getIdentifier("from", "id", "com.miui.voiceassist");
                    View attribution = view.findViewById(id);
                    if (attribution instanceof TextView text) text.setText("结果来源自定义绘图服务");
                }
            });
        }

        private String previewSource(Object launcher) {
            if (launcher == null) return null;
            Object intent = XposedHelpers.callMethod(launcher, "getIntent");
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(intent, "isPresent"))) return null;
            Object value = XposedHelpers.callMethod(intent, "get");
            if (!"xiaoaiswitch-image://preview".equals(XposedHelpers.callMethod(value, "getUri"))) return null;
            return (String) XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getUrl"), "get");
        }

        private JSONObject config() throws Exception {
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
            return new JSONObject(result == null ? "{}" : result.getString("json", "{}"));
        }

        private Profile profile(JSONObject config, String route) {
            return ModeProfiles.resolve(config, route, false);
        }

        private void outbound(XC_MethodHook.MethodHookParam param) throws Exception {
            Object event = param.args[0];
            String name = (String) XposedHelpers.callMethod(event, "getFullName");
            if (name.equals("SpeechRecognizer.DuplexRecognizeFinished")) {
                DuplexVoice duplex = duplexVoice;
                if (duplex != null && duplex.engine() == param.thisObject) {
                    duplex.turns().close((String) XposedHelpers.callMethod(event, "getId"));
                }
                return;
            }
            boolean duplexStart = name.equals("SpeechRecognizer.DuplexRecognizeStarted") && features.contains(Feature.DUPLEX);
            boolean voiceStart = duplexStart || name.equals("SpeechRecognizer.Recognize") && features.contains(Feature.VOICE);
            boolean nativeVision = name.equals("MultiModal.ImageUnderstand");
            boolean nlp = name.equals("Nlp.Request") || name.equals("Nlp.RequestLargeLanguageModelContent");
            if (!nlp && !voiceStart && !name.equals("MultiModal.ImageGeneration") && !nativeVision) return;
            if (nativeVision && (!features.contains(Feature.ATTACHMENTS) || !images.isAttachmentQuery(event))) return;
            JSONObject config = config();
            if (!config.optBoolean("enabled", false)) {
                duplexVoice = null;
                generation.incrementAndGet();
                cancelPending();
                owned.clear();
                report(context, "保留原厂：应用首页的模块接管已关闭");
                return;
            }
            String dialog = (String) XposedHelpers.callMethod(event, "getId");
            if (nlp && (adapter.nativeQuery() != null || !exact) && isNativeMemoryCapture(event)) {
                // Preserve the original screen-collect session and its follow-up instructions.
                keepNative(dialog, "截图收藏请求已交给原厂小爱");
                return;
            }
            ImageBridge.Input input = images.inspect(event);
            if (!input.ids().isEmpty() && !features.contains(Feature.ATTACHMENTS)) return;
            boolean visionEnabled = profile(config, "vision").enabled();
            if (nativeVision && !visionEnabled) {
                report(context, "保留原厂：未启用自定义识图模型");
                return;
            }
            boolean nativeDrawing = !nativeVision && input.drawing();
            if (nativeDrawing && !features.contains(Feature.DRAWING)) return;
            boolean useVision = (nativeVision || (!input.ids().isEmpty() && !nativeDrawing)) && visionEnabled;
            if (voiceStart) {
                duplexVoice = null;
                String route = useVision ? "vision" : "voice";
                boolean thinking = !useVision && isThinking(event);
                if (!ModeProfiles.resolve(config, route, thinking).enabled()) {
                    report(context, "保留原厂：语音通道未开启自定义模型");
                    return;
                }
                String firstRound = duplexStart ? (String) optionalValue(XposedHelpers.callMethod(
                        XposedHelpers.callMethod(event, "getPayload"), "getFirstRoundRid")) : null;
                onlyAsr(event);
                generation.incrementAndGet();
                cancelPending();
                PendingVoice pending = new PendingVoice(input, thinking, route);
                if (duplexStart) {
                    duplexVoice = new DuplexVoice(param.thisObject, new DuplexTurns(dialog, firstRound), pending);
                } else {
                    own(dialog, "voice-pending");
                    voiceInputs.put(dialog, pending);
                    synchronized (voiceInputs) {
                        while (voiceInputs.size() > 64) voiceInputs.remove(voiceInputs.keySet().iterator().next());
                    }
                }
                context.getContentResolver().call(ConfigProvider.URI, "asr", null, null);
                if (duplexStart) report(context, "双工语音识别中");
                return;
            }
            String text = images.query(event);
            String question = text;
            if (!nativeVision && !nativeDrawing && input.ids().isEmpty() && text != null
                    && config.optBoolean("nativeMemory", true) && (!exact || features.contains(Feature.NATIVE_MEMORY))) {
                String memoryQuery = MemoryCommand.query(text);
                if (memoryQuery != null) {
                    if (!memoryQuery.equals(text) && features.contains(Feature.NATIVE_MEMORY)) {
                        XposedHelpers.callMethod(XposedHelpers.callMethod(event, "getPayload"), "setQuery", memoryQuery);
                    }
                    keepNative(dialog, "全局记忆请求已交给原厂小爱");
                    return;
                }
            }
            if (nativeVision && (text == null || text.trim().isEmpty())) text = "请描述这张图片。";
            QueryRouter.Selection selection = QueryRouter.select(text == null ? "" : text,
                    config.optString("defaultRoute", "chat"), nativeDrawing, useVision);
            if (!supportsRoute(selection.route())) {
                String status = selection.route().equals("control")
                        ? (features.contains(Feature.CONTROL) ? "保留原厂：已匹配控制后端，但平台控制能力未就绪"
                        : "保留原厂：当前版本未匹配已验证的手机控制后端")
                        : "保留原厂：通道尚未匹配或未就绪（" + selection.route() + "）";
                report(context, status);
                return;
            }
            boolean thinking = (selection.route().equals("chat") || selection.route().equals("voice"))
                    && isThinking(event);
            Profile profile = ModeProfiles.resolve(config, selection.route(), thinking);
            if (!profile.enabled()) {
                report(context, "保留原厂：通道未启用自定义模型（" + selection.route() + "）");
                return;
            }
            start(param.thisObject, config, profile, selection.route(), dialog, selection.text(), question, input, selection.route().equals("voice"));
            param.setResult(true);
        }

        private boolean isThinking(Object event) {
            return ThinkingMode.isThinking(event, loader, features.contains(Feature.THINKING) ? adapter.dialogContext() : null);
        }

        private boolean supportsRoute(String route) {
            return switch (route) {
                case "voice" -> features.contains(Feature.VOICE);
                case "vision" -> features.contains(Feature.ATTACHMENTS);
                case "image" -> features.contains(Feature.DRAWING);
                case "control" -> canControl();
                default -> true;
            };
        }

        private void onlyAsr(Object event) {
            Object control = XposedHelpers.newInstance(XposedHelpers.findClass("com.xiaomi.ai.api.Execution$RequestControl", loader));
            Class<?> type = XposedHelpers.findClass("com.xiaomi.ai.api.Execution$RequestControlType", loader);
            List<Object> disabled = new ArrayList<>();
            disabled.add(XposedHelpers.getStaticObjectField(type, "NLP"));
            disabled.add(XposedHelpers.getStaticObjectField(type, "TTS"));
            XposedHelpers.callMethod(control, "setDisabled", disabled);
            Object nativeContext = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.xiaomi.ai.api.common.APIUtils", loader),
                    "buildContext", control);
            Object contexts = XposedHelpers.callMethod(event, "getContexts");
            if (contexts instanceof List<?> list) {
                list.removeIf(value -> "Execution.RequestControl".equals(XposedHelpers.callMethod(value, "getFullName")));
            }
            XposedHelpers.callMethod(event, "addContext", nativeContext);
        }

        private void inbound(XC_MethodHook.MethodHookParam param) throws Exception {
            Object instruction = param.args[0];
            String name = (String) XposedHelpers.callMethod(instruction, "getFullName");
            String dialog = (String) optionalValue(XposedHelpers.callMethod(instruction, "getDialogId"));
            if (dialog == null) return;
            DuplexVoice duplex = duplexVoice;
            if (duplex != null && duplex.engine() == param.thisObject && inboundDuplex(param, duplex, instruction, name, dialog)) return;
            String owner = owned.get(dialog);
            if (owner == null) return;
            if (!config().optBoolean("enabled", false)) {
                generation.incrementAndGet();
                owned.clear();
                return;
            }
            if (name.equals("SpeechRecognizer.RecognizeResult")) {
                Object payload = XposedHelpers.callMethod(instruction, "getPayload");
                if (!"voice-pending".equals(owner) || !Boolean.TRUE.equals(XposedHelpers.callMethod(payload, "isFinal"))) return;
                Object results = XposedHelpers.callMethod(payload, "getResults");
                if (!(results instanceof List<?> list) || list.isEmpty()) return;
                String text = (String) XposedHelpers.callMethod(list.get(0), "getText");
                if (text.trim().isEmpty()) {
                    owned.remove(dialog);
                    voiceInputs.remove(dialog);
                    report(context, "语音识别为空");
                    return;
                }
                JSONObject config = config();
                PendingVoice pending = voiceInputs.remove(dialog);
                String route = pending == null ? "voice" : pending.route();
                Profile profile = ModeProfiles.resolve(config, route, pending != null && pending.thinking());
                if (!config.optBoolean("enabled", false) || !profile.enabled()) return;
                param.setObjectExtra("voice", new VoiceRequest(config, profile, route, dialog, text, pending == null ? ImageBridge.NONE : pending.input()));
                return;
            }
            if (name.equals("SpeechRecognizer.StopCapture") || name.equals("SpeechRecognizer.ExpectSpeech")) return;
            // Ignore cloud answers for a dialog whose LLM request belongs to this module.
            param.setResult(null);
        }

        private boolean inboundDuplex(XC_MethodHook.MethodHookParam param, DuplexVoice duplex, Object instruction,
                                      String name, String dialog) throws Exception {
            if (name.equals("SpeechRecognizer.RecognizeResult")) {
                Class<?> state = XposedHelpers.findClass(adapter.duplexState(), loader);
                if (!Boolean.TRUE.equals(XposedHelpers.callStaticMethod(state, "isInContinuousDialog"))) return false;
                String currentStream = (String) XposedHelpers.callMethod(
                        XposedHelpers.getStaticObjectField(state, adapter.duplexSingleton()), "getEventId");
                if (!duplex.turns().recognize(currentStream, dialog)) return false;
                Object payload = XposedHelpers.callMethod(instruction, "getPayload");
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(payload, "isFinal"))) return true;
                Object results = XposedHelpers.callMethod(payload, "getResults");
                if (!(results instanceof List<?> list) || list.isEmpty()) return true;
                String text = (String) XposedHelpers.callMethod(list.get(0), "getText");
                if (text.trim().isEmpty() || !duplex.turns().finish(dialog)) return true;
                JSONObject config = config();
                PendingVoice pending = duplex.profile();
                Profile profile = ModeProfiles.resolve(config, pending.route(), pending.thinking());
                if (config.optBoolean("enabled", false) && profile.enabled()) {
                    param.setObjectExtra("voice", new VoiceRequest(config, profile, pending.route(), dialog, text, pending.input()));
                }
                return true;
            }
            if (!duplex.turns().owns(dialog)) return false;
            // The recognizer still manages listening, VAD and the native continuous-dialog state.
            if (!name.startsWith("SpeechRecognizer.") && !name.equals("Nlp.StartPreStream")) param.setResult(null);
            return true;
        }

        private Object optionalValue(Object optional) {
            return optional != null && Boolean.TRUE.equals(XposedHelpers.callMethod(optional, "isPresent"))
                    ? XposedHelpers.callMethod(optional, "get") : null;
        }

        private boolean useNativeMemory(JSONObject config) {
            return features.contains(Feature.NATIVE_MEMORY) && config.optBoolean("nativeMemory", true);
        }

        private boolean isNativeMemoryCapture(Object event) {
            Object contexts = XposedHelpers.callMethod(event, "getContexts");
            if (!(contexts instanceof List<?> list)) return false;
            for (Object item : list) {
                if ("General.RequestState".equals(XposedHelpers.callMethod(item, "getFullName"))) {
                    Object state = XposedHelpers.callMethod(item, "getPayload");
                    String origin = (String) optionalValue(XposedHelpers.callMethod(state, "getOrigin"));
                    if (MemoryCommand.isNativeCaptureOrigin(origin)) return true;
                }
            }
            return false;
        }

        private void keepNative(String dialog, String status) {
            duplexVoice = null;
            owned.remove(dialog);
            generation.incrementAndGet();
            cancelPending();
            report(context, status);
        }

        private void forwardMemorySpeech(Object engine, VoiceRequest voice) {
            long request = generation.incrementAndGet();
            cancelPending();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!current(request, voice.config().optLong("revision"))) return;
                try {
                    // End this ASR-only conversation through the original UI lifecycle before re-submitting as native NLP.
                    duplexVoice = null;
                    owned.remove(voice.dialog());
                    XposedHelpers.callStaticMethod(XposedHelpers.findClass(adapter.duplexState(), loader),
                            "exitContinuousDialogAndFinishStreamIfNeed");
                    Class<?> replay = XposedHelpers.findClass(adapter.nativeQuery(), loader);
                    Object builder = XposedHelpers.newInstance(XposedHelpers.findClass(adapter.nativeBuilder(), loader));
                    String origin = (String) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(adapter.nativeOrigin(), loader), "getLastQueryOrigin");
                    XposedHelpers.callMethod(builder, "setQuery", voice.text());
                    XposedHelpers.callMethod(builder, "setQueryOrigin", origin);
                    XposedHelpers.callStaticMethod(replay, "startQuery", XposedHelpers.callMethod(builder, "build"));
                } catch (Throwable error) {
                    deliver(engine, request, AivsProtocol.textResponse(voice.dialog(), voice.text(), "原厂全局记忆入口未就绪。"),
                            false, "");
                    report(context, "全局记忆启动失败：" + error.getClass().getSimpleName());
                }
            });
        }

        private record VoiceRequest(JSONObject config, Profile profile, String route, String dialog, String text, ImageBridge.Input input) {}
        private record PendingVoice(ImageBridge.Input input, boolean thinking, String route) {}
        private record DuplexVoice(Object engine, DuplexTurns turns, PendingVoice profile) {}

        private void own(String dialog, String route) {
            synchronized (owned) {
                owned.put(dialog, route);
                while (owned.size() > 64) owned.remove(owned.keySet().iterator().next());
            }
        }

        private void start(Object engine, JSONObject config, Profile profile, String route, String nativeDialog, String text,
                           String question, ImageBridge.Input input, boolean speak) {
            boolean isDialogue = !route.equals("image") && !route.equals("control");
            String dialog;
            synchronized (owned) {
                String previous = owned.get(nativeDialog);
                // Typed requests during continuous listening can share the transport ID.
                dialog = isDialogue && previous != null && !previous.equals("voice-pending") ? UUID.randomUUID().toString() : nativeDialog;
                own(nativeDialog, route);
            }
            boolean showQuestion = isDialogue && question != null && !question.trim().isEmpty()
                    && (!features.contains(Feature.QUERY_CARD) || Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(adapter.queryCardPolicyOwner(), loader), "shouldAddQueryCard", nativeDialog)));
            long request = generation.incrementAndGet();
            cancelPending();
            ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
            runningHttp.set(cancellation);
            JSONArray history;
            synchronized (histories) {
                if (revision != config.optLong("revision")) {
                    histories.clear();
                    previousImage = "";
                    revision = config.optLong("revision");
                }
                history = histories.get(route);
                if (history == null) history = new JSONArray();
            }
            JSONArray prior = history;
            report(context, "自定义请求中：" + route + " / " + profile.model());
            network.execute(() -> {
                ReplyStream stream = new ReplyStream(engine, request, dialog, question == null ? text : question, cancellation);
                try {
                    requireCurrent(request, config);
                    if (showQuestion) deliver(engine, request, AivsProtocol.queryPrelude(dialog, question), false, "");
                    if (route.equals("control")) {
                        String answer = control(config, profile, text, request, cancellation);
                        requireCurrent(request, config);
                        deliver(engine, request, AivsProtocol.textResponse(dialog, text, answer), false, "");
                        report(context, "自定义手机控制已结束：" + profile.model());
                        return;
                    }
                    List<ModelClient.Image> attached = images.load(input, cancellation);
                    if (route.equals("vision") && attached.isEmpty()) {
                        deliver(engine, request, AivsProtocol.textResponse(dialog, text, "请先添加图片，再使用识图模型。"), false, "");
                        report(context, "识图未执行：没有图片附件");
                        return;
                    }
                    if (route.equals("image")) {
                        List<String> result = generate(config, profile, text, attached, request, cancellation);
                        deliver(engine, request, AivsProtocol.imageResponse(dialog, text, result), false, "");
                        report(context, "自定义绘图响应成功：" + profile.model());
                        return;
                    }
                    Profile imageProfile = profile(config, "image");
                    Profile controlProfile = profile(config, "control");
                    boolean auto = !route.equals("vision") && config.optBoolean("autoRoute", true);
                    boolean imageTool = auto && imageProfile.enabled() && features.contains(Feature.DRAWING);
                    boolean controlTool = auto && controlProfile.enabled() && canControl();
                    boolean systemTools = (route.equals("chat") || route.equals("voice")) && config.optBoolean("systemTools", false);
                    DialogueProtocol.Tools tools = new DialogueProtocol.Tools(imageTool, controlTool, systemTools);
                    List<String> attachments = new ArrayList<>();
                    for (ModelClient.Image image : attached) attachments.add(ImageBridge.dataUrl(image));
                    JSONObject body = DialogueProtocol.request(profile, prior, text, attachments, tools);
                    DialogueLoop.Result result = DialogueLoop.run(profile, body, tools, task -> {
                        requireCurrent(request, config);
                        if (task.systemAction() != null) return SystemActionExecutor.execute(context, task.systemAction());
                        if (task.route().equals("control")) {
                            return new DialogueLoop.Result(control(config, controlProfile, task.text(), request, cancellation), Collections.emptyList());
                        }
                        List<ModelClient.Image> originals = new ArrayList<>();
                        if (task.image().equals("input")) {
                            if (attached.isEmpty()) throw new IllegalArgumentException("请先附加原图");
                            originals.addAll(attached);
                        }
                        if (task.image().equals("previous")) {
                            if (previousImage.isEmpty()) throw new IllegalArgumentException("当前会话没有上次生成的图片");
                            originals.add(images.loadSource(previousImage, cancellation));
                        }
                        List<String> output = generate(config, imageProfile, task.text(), originals, request, cancellation);
                        return new DialogueLoop.Result("已生成图片", output);
                    }, () -> current(request, config.optLong("revision")), cancellation, (selected, bodyPart, cancel) -> {
                        stream.beginPart();
                        return chat(selected, bodyPart, cancel, stream::delta);
                    });
                    requireCurrent(request, config);
                    synchronized (histories) { histories.put(route, ChatProtocol.history(body, result.text())); }
                    stream.finish(result.text(), result.images(), speak);
                    report(context, "自定义请求成功：" + route + " / " + profile.model());
                } catch (Exception error) {
                    if (!current(request, config.optLong("revision"))) return;
                    String message = error instanceof ControlLoop.StepLimit || error instanceof ModelClient.HttpError
                            || error instanceof CodexClient.LoginRequired || error instanceof SecurityException ? error.getMessage()
                            : "模型请求失败（" + error.getClass().getSimpleName() + "），请检查配置和服务状态。";
                    if (route.equals("vision") && error instanceof ModelClient.HttpError) message += "。请确认所选模型支持图片输入。";
                    stream.beginPart();
                    stream.finish(message, List.of(), false);
                    report(context, error instanceof SecurityException ? "操作已停止：" + error.getMessage()
                            : "自定义请求失败：" + error.getClass().getSimpleName());
                } finally {
                    runningHttp.compareAndSet(cancellation, null);
                }
            });
        }

        private final class ReplyStream {
            private final Object engine;
            private final long request;
            private final String dialog;
            private final String query;
            private final ModelClient.Cancellation cancellation;
            private boolean started;
            private boolean partHasText;

            ReplyStream(Object engine, long request, String dialog, String query, ModelClient.Cancellation cancellation) {
                this.engine = engine;
                this.request = request;
                this.dialog = dialog;
                this.query = query;
                this.cancellation = cancellation;
            }

            void beginPart() { partHasText = false; }

            void delta(String text) {
                if (text.isEmpty() || generation.get() != request || cancellation.isCancelled()) return;
                if (!started) {
                    started = true;
                    deliver(engine, request, AivsProtocol.streamStart(dialog), false, "");
                } else if (!partHasText) {
                    deliver(engine, request, AivsProtocol.streamDelta(dialog, "\n\n"), false, "");
                }
                partHasText = true;
                deliver(engine, request, AivsProtocol.streamDelta(dialog, text), false, "");
            }

            void finish(String text, List<String> images, boolean speak) {
                if (!started) {
                    deliver(engine, request, images.isEmpty() ? AivsProtocol.textResponse(dialog, query, text)
                            : AivsProtocol.richResponse(dialog, query, text, images), speak, text);
                    return;
                }
                if (!partHasText) delta(text);
                deliver(engine, request, AivsProtocol.streamEnd(dialog, query, images), speak, text);
            }
        }

        private List<String> generate(JSONObject config, Profile profile, String text, List<ModelClient.Image> input,
                                      long request, ModelClient.Cancellation cancellation) throws Exception {
            if (!profile.enabled()) throw new IllegalArgumentException("绘图通道未启用");
            requireCurrent(request, config);
            report(context, "自定义绘图请求：" + (input.isEmpty() ? "文生图" : "图生图") + " / " + profile.model());
            JSONObject response = profile.provider().equals("codex_subscription") ? subscriptionImage(profile, text, input, cancellation)
                    : input.isEmpty() ? ModelClient.post(profile, profile.imageUrl(), ChatProtocol.imageRequest(profile, text), cancellation)
                    : ModelClient.edit(profile, text, input, cancellation);
            List<String> output = ImageProtocol.sources(response);
            requireCurrent(request, config);
            previousImage = output.get(0);
            return output;
        }

        private boolean canControl() {
            return features.contains(Feature.CONTROL) && PhoneControl.available(context, loader, adapter.controlBackend());
        }

        private String control(JSONObject config, Profile profile, String text, long request, ModelClient.Cancellation cancellation) throws Exception {
            if (!profile.enabled()) throw new IllegalArgumentException("控制通道未启用");
            PhoneControl phone = new PhoneControl(context, loader, adapter.controlBackend(),
                    () -> current(request, config.optLong("revision")),
                    value -> report(context, value), () -> {
                if (generation.compareAndSet(request, request + 1)) report(context, "已停止自定义手机控制");
                cancellation.cancel();
            });
            activeControl.set(phone);
            try {
                phone.begin();
                return ControlLoop.run(profile, text, phone, config.optInt("controlSteps", 12),
                        () -> current(request, config.optLong("revision")), cancellation, step -> {
                    phone.progress(step);
                    report(context, "手机控制模型调用：" + step + " / " + profile.model());
                }, this::chat, attempt -> {
                    phone.retrying(attempt);
                    report(context, "手机控制暂未就绪，等待 " + ControlLoop.RETRY_DELAY_MS / 1000
                            + " 秒后重试（" + attempt + "/" + ControlLoop.RETRY_LIMIT + "）");
                });
            } finally {
                try {
                    if (current(request, config.optLong("revision")) && !cancellation.isCancelled()) phone.prepareResult();
                } finally {
                    phone.close();
                    activeControl.compareAndSet(phone, null);
                }
            }
        }

        private void requireCurrent(long request, JSONObject config) {
            if (!current(request, config.optLong("revision"))) throw new CancellationException();
        }

        private JSONObject chat(Profile profile, JSONObject request, ModelClient.Cancellation cancellation) throws Exception {
            return chat(profile, request, cancellation, null);
        }

        private JSONObject chat(Profile profile, JSONObject request, ModelClient.Cancellation cancellation, Consumer<String> delta) throws Exception {
            if (!profile.provider().equals("codex_subscription")) {
                return delta == null ? ModelClient.chat(profile, request, cancellation) : ModelClient.chat(profile, request, cancellation, delta);
            }
            CodexOAuth.Tokens tokens = credentials(false, null);
            try {
                return CodexClient.chat(profile, request, tokens, cancellation, delta);
            } catch (ModelClient.HttpError error) {
                if (error.status() != 401) throw error;
                return CodexClient.chat(profile, request, credentials(true, tokens.accessToken()), cancellation, delta);
            }
        }

        private JSONObject subscriptionImage(Profile profile, String prompt, List<ModelClient.Image> originals,
                                             ModelClient.Cancellation cancellation) throws Exception {
            CodexOAuth.Tokens tokens = credentials(false, null);
            try {
                return CodexClient.images(profile, prompt, originals, tokens, cancellation);
            } catch (ModelClient.HttpError error) {
                if (error.status() != 401) throw error;
                return CodexClient.images(profile, prompt, originals, credentials(true, tokens.accessToken()), cancellation);
            }
        }

        private CodexOAuth.Tokens credentials(boolean force, String previous) throws Exception {
            Bundle extras = new Bundle();
            extras.putBoolean("force", force);
            if (previous != null) extras.putString("previousAccess", previous);
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "codexCredentials", null, extras);
            if (result == null || result.containsKey("error")) throw new CodexClient.LoginRequired();
            return new CodexOAuth.Tokens(result.getString("accessToken", ""), "", "", result.getString("accountId", ""), "", "", result.getLong("expiresAt"));
        }

        private void cancelPending() {
            PhoneControl phone = activeControl.getAndSet(null);
            if (phone != null) phone.close();
            ModelClient.Cancellation pending = runningHttp.getAndSet(null);
            if (pending != null) pending.cancel();
        }

        private boolean current(long request, long expectedRevision) {
            if (generation.get() != request) return false;
            try {
                JSONObject latest = config();
                return latest.optBoolean("enabled", false) && latest.optLong("revision") == expectedRevision;
            } catch (Exception error) {
                return false;
            }
        }

        private void deliver(Object engine, long request, JSONArray instructions, boolean speak, String answer) {
            AssistantVersion.Engine mapping = adapter.engine();
            Handler handler = (Handler) XposedHelpers.getObjectField(engine, mapping.handler());
            handler.post(() -> {
                if (generation.get() != request) return;
                try {
                    Class<?> api = XposedHelpers.findClass("com.xiaomi.ai.api.common.APIUtils", loader);
                    for (int index = 0; index < instructions.length(); index++) {
                        Object instruction = XposedHelpers.callStaticMethod(api, "readInstruction", instructions.getJSONObject(index).toString());
                        XposedHelpers.callMethod(engine, mapping.deliver(), instruction);
                    }
                    if (speak) {
                        Object tts = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(mapping.tts(), loader), mapping.ttsSingleton());
                        Class<?> callback = XposedHelpers.findClass(mapping.ttsCallback(), loader);
                        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            if (method.getName().equals("toString")) return "XiaoAiSwitchTtsCallback";
                            if (generation.get() != request) return null;
                            if (method.getName().equals("onPlayStart")) report(context, "原厂 TTS 已开始播放");
                            if (method.getName().equals("onPlayFinish")) report(context, "原厂 TTS 播放结束（中断=" + args[0] + "）");
                            return null;
                        });
                        XposedHelpers.callMethod(tts, "speakTts", answer, listener, true);
                    }
                } catch (Throwable error) {
                    report(context, "原生卡片回填失败：" + error.getClass().getSimpleName());
                }
            });
        }
    }
}
