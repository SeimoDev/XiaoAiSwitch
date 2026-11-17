package io.github.xiaoaiswitch;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.xiaoaiswitch.core.AivsProtocol;
import io.github.xiaoaiswitch.core.ChatProtocol;
import io.github.xiaoaiswitch.core.Profile;
import io.github.xiaoaiswitch.core.ModelClient;
import io.github.xiaoaiswitch.core.ImageProtocol;
import io.github.xiaoaiswitch.core.QueryRouter;
import io.github.xiaoaiswitch.core.ControlLoop;
import io.github.xiaoaiswitch.core.DialogueProtocol;
import io.github.xiaoaiswitch.core.DialogueLoop;
import io.github.xiaoaiswitch.core.DuplexTurns;
import io.github.xiaoaiswitch.core.CodexClient;
import io.github.xiaoaiswitch.core.CodexOAuth;
import io.github.xiaoaiswitch.core.ModeProfiles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.lang.reflect.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.miui.voiceassist".equals(load.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                try {
                    int version = context.getPackageManager().getPackageInfo(load.packageName, 0).versionCode;
                    Class<?> event = XposedHelpers.findClassIfExists("com.xiaomi.ai.api.common.Event", load.classLoader);
                    Class<?> instruction = XposedHelpers.findClassIfExists("com.xiaomi.ai.api.common.Instruction", load.classLoader);
                    AssistantVersion adapter = AssistantVersion.forCode(version);
                    if (adapter == null || !adapter.engine().matches(load.classLoader, event, instruction, Handler.class)) {
                        report(context, "小爱版本未适配：" + version + "；"
                                + AssistantVersion.detectedStructure(load.classLoader, event, instruction, Handler.class)
                                + "；保持原厂链路");
                        return;
                    }
                    new Runtime(context, load.classLoader, adapter).install(event, instruction);
                } catch (Throwable error) {
                    report(context, "Hook 安装失败：" + error.getClass().getSimpleName());
                    XposedBridge.log("XiaoAiSwitch: install " + error.getClass().getSimpleName());
                }
            }
        });
    }

    static void report(Context context, String text) {
        report(context, text, false);
    }

    private static void report(Context context, String text, boolean ready) {
        try {
            Bundle extras = new Bundle();
            if (ready) extras.putInt("moduleVersion", BuildConfig.VERSION_CODE);
            context.getContentResolver().call(ConfigProvider.URI, "status", text, extras);
        } catch (RuntimeException error) {
            XposedBridge.log("XiaoAiSwitch: status provider " + error.getClass().getSimpleName());
        }
    }

    private static final class Runtime {
        private final Context context;
        private final ClassLoader loader;
        private final AssistantVersion adapter;
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
        private long revision;

        Runtime(Context context, ClassLoader loader, AssistantVersion adapter) {
            this.context = context;
            this.loader = loader;
            this.adapter = adapter;
            images = new ImageBridge(context, loader, adapter.images());
        }

        void install(Class<?> eventClass, Class<?> instructionClass) {
            AssistantVersion.Engine engine = adapter.engine();
            Class<?> engineClass = XposedHelpers.findClass(engine.type(), loader);
            XposedHelpers.findAndHookMethod(engineClass, engine.post(), eventClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        outbound(param);
                    } catch (Throwable error) {
                        report(context, "请求适配异常：" + error.getClass().getSimpleName());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(engineClass, engine.receive(), instructionClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        inbound(param);
                    } catch (Throwable error) {
                        report(context, "语音适配异常：" + error.getClass().getSimpleName());
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getObjectExtra("voice");
                    if (!(value instanceof VoiceRequest voice)) return;
                    start(param.thisObject, voice.config(), voice.profile(), voice.route(), voice.dialog(), voice.text(), voice.input(), true);
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
            List<String> unavailable = new ArrayList<>();
            if (!installOptional("图片附件", images::install)) unavailable.add("图片附件");
            if (!installOptional("绘图预览", () -> installGallery(instructionClass))) unavailable.add("绘图预览");
            if (!installOptional("Markdown", () -> ReplyMarkdown.install(loader, adapter.markdown()))) unavailable.add("Markdown");
            installOptional("测试入口", () -> FixtureSupport.install(context, loader, adapter));
            if (!adapter.phoneControl()) unavailable.add("自定义手机控制");
            report(context, "已加载：AIVS 对话、语音、识图与绘图 Hook；进程 " + android.os.Process.myPid()
                    + (unavailable.isEmpty() ? "" : "；未接管：" + String.join("、", unavailable)), true);
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
            XposedHelpers.findAndHookMethod(mapping.gallery(), loader, "g",
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
            boolean duplexStart = name.equals("SpeechRecognizer.DuplexRecognizeStarted") && adapter.duplexState() != null;
            boolean voiceStart = duplexStart || name.equals("SpeechRecognizer.Recognize");
            boolean nativeVision = name.equals("MultiModal.ImageUnderstand");
            if (!name.equals("Nlp.Request") && !name.equals("Nlp.RequestLargeLanguageModelContent")
                    && !voiceStart && !name.equals("MultiModal.ImageGeneration") && !nativeVision) return;
            if (nativeVision && !images.isAttachmentQuery(event)) return;
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
            ImageBridge.Input input = images.inspect(event);
            boolean visionEnabled = profile(config, "vision").enabled();
            if (nativeVision && !visionEnabled) {
                report(context, "保留原厂：未启用自定义识图模型");
                return;
            }
            boolean nativeDrawing = !nativeVision && input.drawing();
            boolean useVision = (nativeVision || (!input.ids().isEmpty() && !nativeDrawing)) && visionEnabled;
            if (voiceStart) {
                duplexVoice = null;
                String route = useVision ? "vision" : "voice";
                boolean thinking = !useVision && ThinkingMode.isThinking(event, loader, adapter.dialogContext());
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
            if (nativeVision && (text == null || text.trim().isEmpty())) text = "请描述这张图片。";
            QueryRouter.Selection selection = QueryRouter.select(text == null ? "" : text,
                    config.optString("defaultRoute", "chat"), nativeDrawing, useVision);
            if (selection.route().equals("control") && !adapter.phoneControl()) {
                report(context, "保留原厂：当前小爱版本未启用自定义手机控制");
                return;
            }
            boolean thinking = (selection.route().equals("chat") || selection.route().equals("voice"))
                    && ThinkingMode.isThinking(event, loader, adapter.dialogContext());
            Profile profile = ModeProfiles.resolve(config, selection.route(), thinking);
            if (!profile.enabled()) {
                report(context, "保留原厂：通道未启用自定义模型（" + selection.route() + "）");
                return;
            }
            start(param.thisObject, config, profile, selection.route(), dialog, selection.text(), input, selection.route().equals("voice"));
            param.setResult(true);
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
                String currentStream = (String) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(state, "a"), "getEventId");
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

        private record VoiceRequest(JSONObject config, Profile profile, String route, String dialog, String text, ImageBridge.Input input) {}
        private record PendingVoice(ImageBridge.Input input, boolean thinking, String route) {}
        private record DuplexVoice(Object engine, DuplexTurns turns, PendingVoice profile) {}

        private void own(String dialog, String route) {
            synchronized (owned) {
                owned.put(dialog, route);
                while (owned.size() > 64) owned.remove(owned.keySet().iterator().next());
            }
        }

        private void start(Object engine, JSONObject config, Profile profile, String route, String dialog, String text, ImageBridge.Input input, boolean speak) {
            own(dialog, route);
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
                try {
                    requireCurrent(request, config);
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
                    boolean imageTool = auto && imageProfile.enabled();
                    boolean controlTool = auto && adapter.phoneControl() && controlProfile.enabled();
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
                    }, () -> current(request, config.optLong("revision")), cancellation, this::chat);
                    requireCurrent(request, config);
                    synchronized (histories) { histories.put(route, ChatProtocol.history(body, result.text())); }
                    deliver(engine, request, result.images().isEmpty() ? AivsProtocol.textResponse(dialog, text, result.text())
                            : AivsProtocol.richResponse(dialog, text, result.text(), result.images()), speak, result.text());
                    report(context, "自定义请求成功：" + route + " / " + profile.model());
                } catch (Exception error) {
                    if (!current(request, config.optLong("revision"))) return;
                    String message = error instanceof ControlLoop.StepLimit || error instanceof ModelClient.HttpError
                            || error instanceof CodexClient.LoginRequired || error instanceof SecurityException ? error.getMessage()
                            : "模型请求失败（" + error.getClass().getSimpleName() + "），请检查配置和服务状态。";
                    if (route.equals("vision") && error instanceof ModelClient.HttpError) message += "。请确认所选模型支持图片输入。";
                    deliver(engine, request, AivsProtocol.textResponse(dialog, text,
                            message), false, "");
                    report(context, error instanceof SecurityException ? "操作已停止：" + error.getMessage()
                            : "自定义请求失败：" + error.getClass().getSimpleName());
                } finally {
                    runningHttp.compareAndSet(cancellation, null);
                }
            });
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

        private String control(JSONObject config, Profile profile, String text, long request, ModelClient.Cancellation cancellation) throws Exception {
            if (!profile.enabled()) throw new IllegalArgumentException("控制通道未启用");
            PhoneControl phone = new PhoneControl(context, loader, () -> current(request, config.optLong("revision")),
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
                }, this::chat);
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
            if (!profile.provider().equals("codex_subscription")) return ModelClient.chat(profile, request, cancellation);
            CodexOAuth.Tokens tokens = credentials(false, null);
            try {
                return CodexClient.chat(profile, request, tokens, cancellation);
            } catch (ModelClient.HttpError error) {
                if (error.status() != 401) throw error;
                return CodexClient.chat(profile, request, credentials(true, tokens.accessToken()), cancellation);
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
                        Object tts = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(mapping.tts(), loader), "a");
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
