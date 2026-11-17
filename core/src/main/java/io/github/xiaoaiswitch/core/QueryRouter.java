package io.github.xiaoaiswitch.core;

public final class QueryRouter {
    private QueryRouter() {}

    public record Selection(String route, String text) {}

    public static Selection select(String text, String defaultRoute, boolean nativeDrawing) {
        return select(text, defaultRoute, nativeDrawing, false);
    }

    public static Selection select(String text, String defaultRoute, boolean nativeDrawing, boolean useVision) {
        if (!SettingsConfig.ROUTES.contains(defaultRoute)) throw new IllegalArgumentException("未知默认通道");
        String[] prefixes = {"/draw ", "/chat ", "/voice ", "/control ", "/vision "};
        String[] routes = {"image", "chat", "voice", "control", "vision"};
        for (int index = 0; index < prefixes.length; index++) {
            if (text.startsWith(prefixes[index])) return new Selection(routes[index], text.substring(prefixes[index].length()).trim());
        }
        return new Selection(nativeDrawing ? "image" : useVision ? "vision" : defaultRoute, text);
    }
}
