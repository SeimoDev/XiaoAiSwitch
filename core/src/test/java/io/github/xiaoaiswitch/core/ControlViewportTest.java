package io.github.xiaoaiswitch.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ControlViewportTest {
    @Test public void topOfCalendarFrameStartsBelowStatusBarAndEndsAboveNavigationBar() {
        ControlViewport frame = ControlViewport.fromDisplay(1440, 3200, 0, 144, 0, 96);
        assertEquals(1440, frame.width());
        assertEquals(2960, frame.height());
        assertEquals(new ControlViewport.Point(0, 144), frame.project(0, 0));
        assertEquals(new ControlViewport.Point(1439, 3103), frame.project(1, 1));
    }

    @Test public void landscapeAndScaledBitmapKeepTheSameCropOrigin() {
        ControlViewport frame = ControlViewport.fromDisplay(3200, 1440, 180, 96, 120, 36);
        // The provider may downscale the crop. Fractions still map to the original visible pixels.
        assertEquals(new ControlViewport.Point(180, 96), frame.project(0, 0));
        assertEquals(new ControlViewport.Point(1629.5f, 749.5f), frame.project(0.5, 0.5));
        assertEquals(new ControlViewport.Point(3079, 1403), frame.project(1, 1));
        for (int index = 0; index <= 20; index++) {
            float part = index / 20f;
            ControlViewport.Point point = frame.project(part, 1 - part);
            assertTrue(point.x() >= frame.left() && point.x() < frame.right());
            assertTrue(point.y() >= frame.top() && point.y() < frame.bottom());
        }
    }

    @Test public void emptyOrInvalidGeometryDoesNotFallBackToWholeScreen() {
        assertThrows(IllegalArgumentException.class, () -> ControlViewport.fromDisplay(1440, 3200, 0, 3200, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlViewport.fromDisplay(1440, 3200, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlViewport.fromDisplay(1440, 3200, 0, 0, 1500, 0));
        ControlViewport frame = ControlViewport.fromDisplay(1440, 3200, 0, 144, 0, 96);
        assertThrows(IllegalArgumentException.class, () -> frame.project(0, -0.01));
        assertThrows(IllegalArgumentException.class, () -> frame.project(1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> frame.project(Double.NaN, 0));
    }
}
