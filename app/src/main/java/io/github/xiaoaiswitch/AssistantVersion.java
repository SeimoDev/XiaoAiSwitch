package io.github.xiaoaiswitch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Raw DEX symbols for supported assistant builds, not JADX-generated aliases. */
record AssistantVersion(
        Engine engine, Images images, Markdown markdown, String dialogContext, String thinkingState, String duplexState,
        boolean phoneControl, ControlBackend controlBackend, String nativeQuery, String queryCardPolicyOwner,
        String duplexSingleton, String nativeBuilder, String nativeOrigin) {
    record Engine(String type, String post, String receive, String deliver, String handler,
                  String session, String tts, String ttsCallback, String ttsSingleton) {
        Engine(String type, String post, String receive, String deliver, String handler, String session, String tts, String ttsCallback) {
            this(type, post, receive, deliver, handler, session, tts, ttsCallback, "a");
        }

        Engine withVoice(String voice, String callback, String singleton) {
            return new Engine(type, post, receive, deliver, handler, session, voice, callback, singleton);
        }
        boolean matches(ClassLoader loader, Class<?> event, Class<?> instruction, Class<?> handlerType) {
            if (event == null || instruction == null) return false;
            try {
                Class<?> owner = Class.forName(type, false, loader);
                Field dispatchHandler = owner.getDeclaredField(handler);
                return dispatchHandler.getType() == handlerType && !Modifier.isStatic(dispatchHandler.getModifiers())
                        && hasReturnType(owner, post, event, boolean.class)
                        && hasReturnType(owner, receive, instruction, void.class)
                        && hasReturnType(owner, deliver, instruction, void.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                return false;
            }
        }

        private static boolean hasReturnType(Class<?> owner, String name, Class<?> parameter, Class<?> result)
                throws NoSuchMethodException {
            Method member = owner.getDeclaredMethod(name, parameter);
            return member.getReturnType() == result && !Modifier.isStatic(member.getModifiers())
                    && !Modifier.isAbstract(member.getModifiers()) && !Modifier.isNative(member.getModifiers());
        }
    }

    record Images(String producer, String preferences, String gallery, String glide, String chainOwner, String chain,
                  String cacheIndex, String galleryOpen) {
        Images(String producer, String preferences, String gallery, String glide) {
            this(producer, preferences, gallery, glide, producer.equals("sj1.b") ? "dp1.d3" : "zj0.m1",
                    producer.equals("sj1.b") ? "ap1.k" : "wj0.j", preferences + (producer.equals("sj1.b") ? "$f0" : "$d0"), "g");
        }

        Images discover(Map<String, String> symbols) {
            boolean attachment = complete(symbols, "producer", "prefs", "chainOwner", "chain", "cache");
            boolean drawing = complete(symbols, "gallery", "glide", "galleryOpen");
            return new Images(attachment ? symbols.get("producer") : producer, attachment ? symbols.get("prefs") : preferences,
                    drawing ? symbols.get("gallery") : gallery, drawing ? symbols.get("glide") : glide,
                    attachment ? symbols.get("chainOwner") : chainOwner, attachment ? symbols.get("chain") : chain,
                    attachment ? symbols.get("cache") : cacheIndex, drawing ? symbols.get("galleryOpen") : galleryOpen);
        }
    }
    record Markdown(String operation, String fullCardFactory, String floatingCardFactory, String fullCard, String renderer,
                    String streamedCard, String rendererSingleton) {
        Markdown(String operation, String fullCardFactory, String floatingCardFactory, String fullCard, String renderer) {
            this(operation, fullCardFactory, floatingCardFactory, fullCard, renderer,
                    "com.xiaomi.voiceassistant.instruction.card.stream.b", "a");
        }

        Markdown discover(Map<String, String> symbols) {
            if (!complete(symbols, "markdownOperation", "fullFactory", "floatingFactory", "fullCard", "streamedCard", "renderer", "rendererSingleton")) return this;
            return new Markdown(symbols.get("markdownOperation"), symbols.get("fullFactory"), symbols.get("floatingFactory"),
                    symbols.get("fullCard"), symbols.get("renderer"), symbols.get("streamedCard"), symbols.get("rendererSingleton"));
        }
    }
    record ControlBackend(String permissions, String scope, String callback, String tasks, String capture, String window, String input) {}

    enum Feature {
        VOICE("语音"), DUPLEX("双工语音"), THINKING("思考模式"), ATTACHMENTS("图片附件"), DRAWING("绘图"),
        MARKDOWN("Markdown"), NATIVE_MEMORY("全局记忆"), CONTROL("手机控制"), QUERY_CARD("输入卡片策略");

        final String label;
        Feature(String label) { this.label = label; }
    }

    record Compatibility(AssistantVersion adapter, boolean exact, boolean discovered, Set<Feature> features) {
        Compatibility { features = Set.copyOf(features); }
        boolean supports(Feature feature) { return features.contains(feature); }
    }

    private static final Pattern FAMILY = Pattern.compile("(?i)(?:release-)?([78])(?:\\.[0-9]+)*(?:[-+_][0-9a-z._-]+)?");

    private static final AssistantVersion V7 = new AssistantVersion(
            new Engine("b30.o0", "u0", "y0", "W", "y", "b30.g", "b30.z0", "s10.b"),
            new Images("vf0.a", "m20.z0", "fc0.l1$b", "gb0.a"),
            new Markdown("sd0.od", "g0", "h0", "com.xiaomi.voiceassistant.card.i", "nj0.c"),
            "com.xiaomi.voiceassist.engine.context.b", "ti0.h", null, true, null, null, null, "a", null, null);

    private static final AssistantVersion V8 = new AssistantVersion(
            new Engine("p31.o0", "D0", "H0", "b0", "y", "p31.g", "p31.e1", "g21.a"),
            new Images("sj1.b", "com.xiaomi.voiceassist.baselibrary.utils.i1", "jf1.n1$b", "je1.a"),
            new Markdown("zg1.jf", "i0", "k0", "com.xiaomi.voiceassistant.card.k", "wn1.d"),
            "com.xiaomi.voiceassist.engine.context.j0", "zm1.k", "a41.g", true,
            new ControlBackend("com.xiaomi.voiceassistant.utils.o6", "ws1.b$f", "u62.g", "r21.e", "r21.t",
                    "com.xiaomi.voiceassistant.utils.ba", "com.xiaomi.voiceassist.baselibrary.utils.h0"), "x51.m0", "p31.y0",
            "a", "x51.m0$e", "com.xiaomi.voiceassistant.utils.p6");

    static AssistantVersion forCode(long versionCode) {
        if (versionCode == 507011020L) return V7;
        if (versionCode == 508002070L) return V8;
        return null;
    }

    static int familyOf(String versionName) {
        if (versionName == null) return 0;
        var version = FAMILY.matcher(versionName.trim());
        return version.matches() ? version.group(1).charAt(0) - '0' : 0;
    }

    static Compatibility resolve(long versionCode, String versionName, ClassLoader loader, Class<?> event,
                                 Class<?> instruction, Class<?> handler, Class<?> context) {
        AssistantVersion candidate = family(versionName);
        if (candidate == null || !matchesDialogue(candidate.engine, loader, event, instruction, handler)) return null;

        return candidate.verify(candidate == forCode(versionCode), false, false, loader, instruction, context);
    }

    private Compatibility verify(boolean exact, boolean discovered, boolean reviewedControl, ClassLoader loader,
                                   Class<?> instruction, Class<?> context) {
        var verified = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (hasMapping(feature) && (exact || (feature == Feature.CONTROL ? reviewedControl
                    : probe(() -> matches(feature, loader, instruction, context))))) {
                verified.add(feature);
            }
        }
        // A voice session switched to ASR-only must still be able to return memory commands to the native handler.
        if (nativeQuery != null && !verified.contains(Feature.NATIVE_MEMORY)) verified.remove(Feature.VOICE);
        if (!verified.contains(Feature.VOICE)) verified.remove(Feature.DUPLEX);
        return new Compatibility(this, exact, discovered, verified);
    }

    static Compatibility resolveDiscovered(String versionName, Engine engine, Map<String, String> symbols,
                                           boolean reviewedControl, ClassLoader loader, Class<?> event, Class<?> instruction,
                                           Class<?> handler, Class<?> context) {
        AssistantVersion candidate = family(versionName);
        if (candidate == null || !matchesDialogue(engine, loader, event, instruction, handler)) return null;
        Engine voice = complete(symbols, "tts", "ttsCallback", "ttsSingleton")
                ? engine.withVoice(symbols.get("tts"), symbols.get("ttsCallback"), symbols.get("ttsSingleton"))
                : engine.withVoice(candidate.engine.tts, candidate.engine.ttsCallback, candidate.engine.ttsSingleton);
        boolean duplex = complete(symbols, "duplex", "duplexSingleton");
        boolean memory = complete(symbols, "memoryQuery", "memoryBuilder", "memoryOrigin");
        ControlBackend control = reviewedControl ? candidate.controlBackend : null;
        return new AssistantVersion(voice, candidate.images.discover(symbols), candidate.markdown.discover(symbols),
                symbols.getOrDefault("dialogContext", candidate.dialogContext), candidate.thinkingState,
                duplex ? symbols.get("duplex") : candidate.duplexState, control != null, control,
                memory ? symbols.get("memoryQuery") : candidate.nativeQuery,
                symbols.getOrDefault("queryCard", candidate.queryCardPolicyOwner),
                duplex ? symbols.get("duplexSingleton") : candidate.duplexSingleton,
                memory ? symbols.get("memoryBuilder") : candidate.nativeBuilder,
                memory ? symbols.get("memoryOrigin") : candidate.nativeOrigin).verify(false, true, control != null, loader, instruction, context);
    }

    private static boolean complete(Map<String, String> symbols, String... keys) {
        for (String key : keys) if (!symbols.containsKey(key)) return false;
        return true;
    }

    private static AssistantVersion family(String versionName) {
        return switch (familyOf(versionName)) {
            case 7 -> V7;
            case 8 -> V8;
            default -> null;
        };
    }

    private static boolean matchesDialogue(Engine engine, ClassLoader loader, Class<?> event, Class<?> instruction, Class<?> handler) {
        return engine.matches(loader, event, instruction, handler)
                && probe(() -> member(loader, engine.session, "clearSession", false) == void.class
                        && member(loader, "com.xiaomi.ai.api.common.APIUtils", "readInstruction", true, String.class) == instruction);
    }

    private boolean hasMapping(Feature feature) {
        return switch (feature) {
            case DUPLEX -> duplexState != null;
            case NATIVE_MEMORY -> nativeQuery != null && nativeBuilder != null && nativeOrigin != null && duplexState != null;
            case CONTROL -> phoneControl;
            case QUERY_CARD -> queryCardPolicyOwner != null;
            default -> true;
        };
    }

    private boolean matches(Feature feature, ClassLoader loader, Class<?> instruction, Class<?> context)
            throws ReflectiveOperationException {
        return switch (feature) {
            case VOICE -> {
                Class<?> callback = type(loader, engine.ttsCallback);
                Class<?> tts = type(loader, engine.tts);
                yield callback.isInterface() && tts.isAssignableFrom(singleton(tts, engine.ttsSingleton))
                        && member(loader, engine.tts, "speakTts", false, String.class, callback, boolean.class) == String.class;
            }
            case DUPLEX -> member(loader, duplexState, "isInContinuousDialog", true) == boolean.class
                    && member(loader, singleton(type(loader, duplexState), duplexSingleton).getName(), "getEventId", false) == String.class;
            case THINKING -> member(loader, dialogContext, "generateDialogStateContext", true) != void.class;
            case ATTACHMENTS -> member(loader, images.producer, "saveImageLocalPath", false, String.class, String.class) == void.class
                    && member(loader, images.chainOwner(), "setMultiModalChain", false, type(loader, images.chain())) == void.class
                    && member(loader, images.preferences, "getString", true, String.class, String.class) == String.class
                    && member(loader, images.cacheIndex(), "getImageCacheIndex", true, String.class) == String.class;
            case DRAWING -> member(loader, images.gallery, images.galleryOpen, false, type(loader, "com.xiaomi.ai.api.Template$ImageLauncher")) == void.class
                    && member(loader, images.gallery, "init", false, instruction) == void.class
                    && member(loader, images.glide, "with", true, context) != void.class;
            case MARKDOWN -> {
                Class<?> holder = type(loader, "androidx.recyclerview.widget.RecyclerView$ViewHolder");
                String streamedCard = markdown.streamedCard;
                Class<?> renderer = type(loader, markdown.renderer);
                yield member(loader, markdown.operation, markdown.fullCardFactory, false, int.class) != void.class
                        && member(loader, markdown.operation, markdown.floatingCardFactory, false, int.class) != void.class
                        && member(loader, markdown.fullCard, "bindView", false, context, holder, int.class) == void.class
                        && member(loader, streamedCard, "bindView", false, context, holder, int.class) == void.class
                        && member(loader, streamedCard, "updateCardText", false, String.class) == void.class
                        && member(loader, streamedCard, "updateToastText", false, String.class) == void.class
                        && renderer.isAssignableFrom(singleton(renderer, markdown.rendererSingleton))
                        && member(loader, markdown.renderer, "getMarkwon", false, context, boolean.class, boolean.class) != void.class;
            }
            case NATIVE_MEMORY -> {
                String builder = nativeBuilder;
                type(loader, builder).getDeclaredConstructor();
                yield member(loader, builder, "setQuery", false, String.class) == type(loader, builder)
                        && member(loader, builder, "setQueryOrigin", false, String.class) == type(loader, builder)
                        && member(loader, nativeOrigin, "getLastQueryOrigin", true) == String.class
                        && member(loader, nativeQuery, "startQuery", true, member(loader, builder, "build", false)) == void.class
                        && member(loader, duplexState, "exitContinuousDialogAndFinishStreamIfNeed", true) == void.class;
            }
            // Verification of the screen authorization lifecycle is done on the corresponding APK and device.
            case CONTROL -> false;
            case QUERY_CARD -> member(loader, queryCardPolicyOwner, "shouldAddQueryCard", true, String.class) == boolean.class;
        };
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Class<?> singleton(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(owner.getName());
        return field.getType();
    }

    private static Class<?> member(ClassLoader loader, String owner, String name, boolean isStatic, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = type(loader, owner).getDeclaredMethod(name, parameters);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || Modifier.isAbstract(method.getModifiers())) {
            throw new NoSuchMethodException(name);
        }
        return method.getReturnType();
    }

    private interface Probe { boolean matches() throws ReflectiveOperationException; }

    private static boolean probe(Probe probe) {
        try {
            return probe.matches();
        } catch (ReflectiveOperationException | LinkageError | SecurityException error) {
            return false;
        }
    }
}
