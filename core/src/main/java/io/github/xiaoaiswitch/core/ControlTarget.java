package io.github.xiaoaiswitch.core;

/** Identity bound to an authorized screenshot on the default display. */
public record ControlTarget(String pkg, String activity, int uid, int task, int rotation, int width, int height,
                            ControlViewport viewport) {
    public ControlTarget requireSame(ControlTarget current, String message) {
        if (!equals(current)) throw new SecurityException(message);
        return this;
    }
}
