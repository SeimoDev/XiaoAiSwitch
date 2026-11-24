package io.github.xiaoaiswitch.core;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ControlCancellationTest {
    @Test public void stopInvalidatesTaskBeforeDisconnectRunsOffThread() throws Exception {
        var cancellation = new ModelClient.Cancellation();
        var disconnects = new AtomicInteger();
        var deferred = new ArrayList<Runnable>();
        var connection = new HttpURLConnection(URI.create("http://127.0.0.1").toURL()) {
            @Override public void disconnect() { disconnects.incrementAndGet(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };
        cancellation.attach(connection);
        cancellation.cancel(deferred::add);
        cancellation.cancel(deferred::add);
        assertTrue(cancellation.isCancelled());
        assertThrows(CancellationException.class, () -> cancellation.attach(connection));
        assertEquals(0, disconnects.get());
        assertEquals(1, deferred.size());
        deferred.get(0).run();
        assertEquals(1, disconnects.get());
    }
}
