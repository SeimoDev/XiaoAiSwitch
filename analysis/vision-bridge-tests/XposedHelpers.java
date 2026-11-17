package de.robv.android.xposed;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reflection-only host adapter. Fixtures exercise the production ImageBridge implementation. */
public final class XposedHelpers {
    private static final Map<String, XC_MethodHook> hooks = new LinkedHashMap<>();
    public static void findAndHookMethod(String owner, ClassLoader loader, String name, Object... args) {
        hooks.put(owner + "." + name, (XC_MethodHook) args[args.length - 1]);
    }
    public static Object callMethod(Object target, String name, Object... args) {
        return invoke(target.getClass(), target, name, args);
    }
    public static Object callStaticMethod(Class<?> owner, String name, Object... args) {
        return invoke(owner, null, name, args);
    }
    private static Object invoke(Class<?> owner, Object target, String name, Object... args) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { return method.invoke(target, args); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        throw new AssertionError(owner.getName() + "." + name + " missing");
    }
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName("io.github.xiaoaiswitch.TestImageBridge$" + (name.endsWith("$d0") || name.endsWith("$f0") ? "ImageCache" : "Preferences")); }
        catch (ClassNotFoundException error) { throw new AssertionError(error); }
    }
    public static void after(String owner, String name, Object argument) throws Throwable {
        XC_MethodHook hook = hooks.get(owner + "." + name);
        if (hook == null) throw new AssertionError("Production hook missing: " + owner + "." + name);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.args = new Object[]{argument};
        hook.afterHookedMethod(param);
    }
}
