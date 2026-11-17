package io.github.xiaoaiswitch.core;

/** Pixel bounds shared by a model-visible screenshot and the pointer coordinates derived from it. */
public record ControlViewport(int left, int top, int right, int bottom) {
    public record Point(float x, float y) {}

    public ControlViewport {
        if (left < 0 || top < 0 || right <= left || bottom <= top) {
            throw new IllegalArgumentException("屏幕内容区为空或无效");
        }
    }

    public static ControlViewport fromDisplay(int width, int height, int left, int top, int right, int bottom) {
        if (width < 1 || height < 1 || right < 0 || bottom < 0) {
            throw new IllegalArgumentException("显示尺寸或系统边距无效");
        }
        return new ControlViewport(left, top, width - right, height - bottom);
    }

    public int width() { return right - left; }
    public int height() { return bottom - top; }

    public Point project(double x, double y) {
        if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) {
            throw new IllegalArgumentException("触点不在截图坐标范围内");
        }
        return new Point((float) (left + x * (width() - 1)), (float) (top + y * (height() - 1)));
    }
}
