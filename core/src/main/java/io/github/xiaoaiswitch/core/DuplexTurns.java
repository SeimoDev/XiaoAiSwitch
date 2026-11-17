package io.github.xiaoaiswitch.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** Correlates per-utterance IDs with one already-claimed native voice stream. */
public final class DuplexTurns {
    private final String stream;
    private final Set<String> recognized = new LinkedHashSet<>();
    private String pending;
    private boolean closed;

    public DuplexTurns(String stream, String firstRound) {
        this.stream = stream;
        if (firstRound != null) recognize(stream, firstRound);
    }

    public synchronized boolean recognize(String currentStream, String dialog) {
        if (closed || !stream.equals(currentStream) || dialog.isEmpty() || stream.equals(dialog)) return false;
        if (recognized.add(dialog)) {
            pending = dialog;
            if (recognized.size() > 64) recognized.remove(recognized.iterator().next());
        }
        return true;
    }

    public synchronized boolean owns(String dialog) {
        return recognized.contains(dialog);
    }

    public synchronized boolean finish(String dialog) {
        if (closed || !dialog.equals(pending)) return false;
        pending = null;
        return true;
    }

    public synchronized boolean close(String dialog) {
        if (!stream.equals(dialog)) return false;
        closed = true;
        pending = null;
        return true;
    }
}
