package io.github.xiaoaiswitch.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

/** Reads complete SSE data frames without treating TCP or JSON line boundaries as events. */
final class ServerSentEvents {
    private final BufferedReader reader;
    private final ModelClient.Cancellation cancellation;
    private int size;
    private boolean first = true;

    ServerSentEvents(BufferedReader reader, ModelClient.Cancellation cancellation) {
        this.reader = reader;
        this.cancellation = cancellation;
    }

    String next() throws IOException {
        StringBuilder data = new StringBuilder();
        while (true) {
            if (cancellation.isCancelled()) throw new CancellationException();
            String line;
            try { line = reader.readLine(); }
            catch (IOException error) {
                if (cancellation.isCancelled()) throw new CancellationException();
                throw error;
            }
            if (cancellation.isCancelled()) throw new CancellationException();
            if (line == null) return null;
            size += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (size > 16 * 1024 * 1024) throw new IOException("模型流式响应超过 16 MiB");
            if (first) {
                first = false;
                if (line.startsWith("\ufeff")) line = line.substring(1);
            }
            if (line.isEmpty()) {
                if (data.length() > 0) return data.substring(0, data.length() - 1);
                continue;
            }
            if (!line.startsWith("data:")) continue;
            String value = line.substring(5);
            data.append(value.startsWith(" ") ? value.substring(1) : value).append('\n');
        }
    }
}
