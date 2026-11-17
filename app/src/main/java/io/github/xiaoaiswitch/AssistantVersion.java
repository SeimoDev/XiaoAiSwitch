package io.github.xiaoaiswitch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Raw DEX symbols for supported assistant builds, not JADX-generated aliases. */
record AssistantVersion(
        Engine engine, Images images, Markdown markdown, String dialogContext, String thinkingState,
        boolean phoneControl) {
    record Engine(String type, String post, String receive, String deliver, String handler,
                  String session, String tts, String ttsCallback) {
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

    record Images(String producer, String preferences, String gallery, String glide) {}
    record Markdown(String operation, String fullCardFactory, String floatingCardFactory, String fullCard, String renderer) {}

    private static final AssistantVersion V7 = new AssistantVersion(
            new Engine("b30.o0", "u0", "y0", "W", "y", "b30.g", "b30.z0", "s10.b"),
            new Images("vf0.a", "m20.z0", "fc0.l1$b", "gb0.a"),
            new Markdown("sd0.od", "g0", "h0", "com.xiaomi.voiceassistant.card.i", "nj0.c"),
            "com.xiaomi.voiceassist.engine.context.b", "ti0.h", true);

    private static final AssistantVersion V8 = new AssistantVersion(
            new Engine("p31.o0", "D0", "H0", "b0", "y", "p31.g", "p31.e1", "g21.a"),
            new Images("sj1.b", "com.xiaomi.voiceassist.baselibrary.utils.i1", "jf1.n1$b", "je1.a"),
            new Markdown("zg1.jf", "i0", "k0", "com.xiaomi.voiceassistant.card.k", "wn1.d"),
            "com.xiaomi.voiceassist.engine.context.j0", "zm1.k", false);

    static AssistantVersion forCode(int versionCode) {
        return switch (versionCode) {
            case 507011020 -> V7;
            case 508002070 -> V8;
            default -> null;
        };
    }

    static String detectedStructure(ClassLoader loader, Class<?> event, Class<?> instruction, Class<?> handlerType) {
        boolean v7 = V7.engine().matches(loader, event, instruction, handlerType);
        boolean v8 = V8.engine().matches(loader, event, instruction, handlerType);
        if (v7 && v8) return "匹配到多个已知结构";
        if (v7) return "匹配 7.11 结构";
        if (v8) return "匹配 8.2 结构";
        return "未匹配";
    }
}
