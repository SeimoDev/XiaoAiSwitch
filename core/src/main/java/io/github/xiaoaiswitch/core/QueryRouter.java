package io.github.xiaoaiswitch.core;

public final class QueryRouter {
    private QueryRouter() {}

    public record Selection(String route, String text) {}

    public static Selection select(String text, String defaultRoute, boolean nativeDrawing) {
        if (!defaultRoute.equals("chat") && !defaultRoute.equals("image")
                && !defaultRoute.equals("voice") && !defaultRoute.equals("control")) {
            throw new IllegalArgumentException("未知默认通道");
        }
        String[] prefixes = {"/draw ", "/chat ", "/voice ", "/control "};
        String[] routes = {"image", "chat", "voice", "control"};
        for (int index = 0; index < prefixes.length; index++) {
            if (text.startsWith(prefixes[index])) return new Selection(routes[index], text.substring(prefixes[index].length()).trim());
        }
        return new Selection(nativeDrawing ? "image" : defaultRoute, text);
    }
}
