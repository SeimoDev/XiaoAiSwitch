package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public static class MethodHookParam { public Object[] args; }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
