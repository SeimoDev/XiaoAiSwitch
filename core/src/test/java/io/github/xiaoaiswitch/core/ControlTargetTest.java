package io.github.xiaoaiswitch.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ControlTargetTest {
    @Test public void sameUidAndTaskDoNotAuthorizeADifferentForegroundActivity() {
        var allowed = target("fixture.First", 0);
        assertEquals(allowed, allowed.requireSame(target("fixture.First", 0), "changed"));
        assertThrows(SecurityException.class, () -> allowed.requireSame(target("fixture.Second", 0), "changed"));
        assertThrows(SecurityException.class, () -> allowed.requireSame(target("fixture.First", 1), "changed"));
        assertThrows(SecurityException.class, () -> allowed.requireSame(null, "changed"));
    }

    private static ControlTarget target(String activity, int rotation) {
        return new ControlTarget("fixture", activity, 11000, 3, rotation, 1080, 2400, null);
    }
}
