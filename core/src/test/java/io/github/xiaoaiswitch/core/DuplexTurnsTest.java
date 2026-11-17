package io.github.xiaoaiswitch.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DuplexTurnsTest {
    @Test public void bindsFirstRoundAndASecondRoundToTheSameNativeStream() {
        DuplexTurns turns = new DuplexTurns("stream", "first");
        assertTrue(turns.owns("first"));
        assertFalse(turns.owns("second"));
        assertTrue(turns.recognize("stream", "first"));
        assertTrue(turns.finish("first"));
        assertFalse(turns.finish("first"));
        assertTrue(turns.recognize("stream", "second"));
        assertTrue(turns.finish("second"));
        assertTrue(turns.owns("second"));
    }

    @Test public void unrelatedOrClosedStreamsCannotClaimAnAsrResult() {
        DuplexTurns turns = new DuplexTurns("stream", "first");
        assertFalse(turns.recognize("unrelated", "first"));
        assertFalse(turns.recognize(null, "second"));
        assertFalse(turns.recognize("stream", ""));
        assertFalse(turns.recognize("stream", "stream"));
        assertFalse(turns.finish("unseen"));
        assertFalse(turns.close("another"));
        assertTrue(turns.close("stream"));
        assertFalse(turns.recognize("stream", "second"));
        assertFalse(turns.finish("first"));
        assertTrue(turns.owns("first"));
    }

    @Test public void ignoresDuplicateOrOutOfOrderFinalResults() {
        DuplexTurns turns = new DuplexTurns("stream", null);
        assertTrue(turns.recognize("stream", "one"));
        assertTrue(turns.recognize("stream", "two"));
        assertFalse(turns.finish("one"));
        assertTrue(turns.finish("two"));
        assertTrue(turns.recognize("stream", "one"));
        assertFalse(turns.finish("one"));
    }
}
