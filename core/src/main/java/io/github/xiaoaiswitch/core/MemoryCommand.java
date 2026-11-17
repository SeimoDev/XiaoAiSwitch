package io.github.xiaoaiswitch.core;

import java.util.List;
import java.util.regex.Pattern;

public final class MemoryCommand {
    private static final String ROUTE = "/memory";
    private static final Pattern EMPTY_WRITE = Pattern.compile("[\\p{P}\\p{Z}\\s]*");
    private static final List<String> READ = List.of(
            "你记得我", "你还记得我", "查我的记忆", "查一下我的记忆", "查看我的记忆",
            "搜索我的记忆", "我的记忆里有什么", "我之前记过什么", "用我的记忆回答", "打开超级小爱记忆");

    private MemoryCommand() {}

    /** In these native flows the screen attachment is bound to an OEM session, not the NLP text. */
    public static boolean isNativeCaptureOrigin(String origin) {
        return "com.miui.voiceassist.query&&three_gesture_up".equals(origin)
                || "com.miui.voiceassist.query&&ai_physical_key".equals(origin)
                || "com.miui.voiceassist.query&&favorite_auto_sync".equals(origin)
                || "os_bot_trigger_favorite".equals(origin);
    }

    /** Returns the original assistant query only for explicitly requested memory operations. */
    public static String query(String text) {
        if (text == null) return null;
        String query = trim(text);
        if (query.isEmpty()) return null;
        if (query.startsWith(ROUTE)) {
            int separator = ROUTE.length();
            return query.length() > separator && space(query.charAt(separator)) ? trim(query.substring(separator)) : null;
        }

        String request = query;
        if (request.startsWith("请")) request = trim(request.substring(1));
        if (request.startsWith("麻烦")) request = trim(request.substring(2));
        if (request.startsWith("帮我") || request.startsWith("替我")) request = trim(request.substring(2));
        if (request.startsWith("记住")) {
            String content = trim(request.substring(2));
            if (content.startsWith("是什么") || EMPTY_WRITE.matcher(content).matches()) return null;
            return query;
        }
        return READ.stream().anyMatch(request::startsWith) ? query : null;
    }

    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.charAt(start))) start++;
        while (end > start && space(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean space(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
