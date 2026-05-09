package org.jatari.dsp;

import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Upsampler}.
 */
class UpsamplerTest {

    // -----------------------------------------------------------------------
    // BOOL — impulse upsampling
    // -----------------------------------------------------------------------

    @Test
    void bool_alwaysTrue_isPulseAtBoundaries() {
        // Source: 2 Hz, always true.  Target: 10 Hz.  Ratio = 5.
        Processor src = new DefaultContext(2L).genB(t -> true);
        Processor up  = Upsampler.of(src, 10L);

        assertEquals(10L, up.context().frequency(), "output frequency");
        assertEquals(Signal.Type.BOOL, up.outType()[0], "output type");

        var sig = up.apply().at(0);

        // Boundaries at t = 0, 5, 10, 15 …
        assertTrue(sig.boolAt(0),  "t=0  must be true (boundary)");
        assertFalse(sig.boolAt(1), "t=1  must be false");
        assertFalse(sig.boolAt(2), "t=2  must be false");
        assertFalse(sig.boolAt(3), "t=3  must be false");
        assertFalse(sig.boolAt(4), "t=4  must be false");
        assertTrue(sig.boolAt(5),  "t=5  must be true (boundary)");
        assertFalse(sig.boolAt(6), "t=6  must be false");
        assertTrue(sig.boolAt(10), "t=10 must be true (boundary)");
    }

    @Test
    void bool_sourceFalseAtFrame_remainsFalseAtBoundary() {
        // Source alternates: true at even frames, false at odd frames.
        Processor src = new DefaultContext(2L).genB(t -> t % 2 == 0);
        Processor up  = Upsampler.of(src, 10L);
        var sig = up.apply().at(0);

        // Frame 0 = even → true at boundary t=0
        assertTrue(sig.boolAt(0),   "frame 0 true → boundary true");
        assertFalse(sig.boolAt(1),  "non-boundary → false");
        // Frame 1 = odd → false at boundary t=5
        assertFalse(sig.boolAt(5),  "frame 1 false → boundary false");
        // Frame 2 = even → true at boundary t=10
        assertTrue(sig.boolAt(10),  "frame 2 true → boundary true");
    }

    @Test
    void bool_rejectsNonUpsampling() {
        Processor src = new DefaultContext(100L).genB(t -> true);
        assertThrows(IllegalArgumentException.class, () -> Upsampler.of(src, 50L));
        assertThrows(IllegalArgumentException.class, () -> Upsampler.of(src, 100L));
    }

    // -----------------------------------------------------------------------
    // INT — zero-order hold
    // -----------------------------------------------------------------------

    @Test
    void int_zeroOrderHold_holdsSourceValueBetweenBoundaries() {
        // Source: 2 Hz, value = frame index (0, 1, 2, …).  Target: 10 Hz.
        Processor src = new DefaultContext(2L).genI(t -> (int) t);
        Processor up  = Upsampler.of(src, 10L);

        assertEquals(Signal.Type.INT, up.outType()[0]);

        var sig = up.apply().at(0);

        // Samples t=0..4 → srcTime=0, value=0
        for (int t = 0; t < 5; t++) {
            assertEquals(0, sig.intAt(t), "t=%d → value 0".formatted(t));
        }
        // Samples t=5..9 → srcTime=1, value=1
        for (int t = 5; t < 10; t++) {
            assertEquals(1, sig.intAt(t), "t=%d → value 1".formatted(t));
        }
        // Sample t=10 → srcTime=2, value=2
        assertEquals(2, sig.intAt(10), "t=10 → value 2");
    }

    // -----------------------------------------------------------------------
    // LONG — zero-order hold
    // -----------------------------------------------------------------------

    @Test
    void long_zeroOrderHold_holdsSourceValueBetweenBoundaries() {
        // Source: 3 Hz, value = frame * 1000.  Target: 9 Hz.  Ratio = 3.
        Processor src = new DefaultContext(3L).genL(t -> t * 1000L);
        Processor up  = Upsampler.of(src, 9L);

        assertEquals(Signal.Type.LONG, up.outType()[0]);

        var sig = up.apply().at(0);

        for (int t = 0; t < 3; t++) {
            assertEquals(0L, sig.longAt(t), "t=%d → 0".formatted(t));
        }
        for (int t = 3; t < 6; t++) {
            assertEquals(1000L, sig.longAt(t), "t=%d → 1000".formatted(t));
        }
        assertEquals(2000L, sig.longAt(6), "t=6 → 2000");
    }

    // -----------------------------------------------------------------------
    // DOUBLE — Catmull-Rom interpolation
    // -----------------------------------------------------------------------

    @Test
    void double_atBoundaries_exactSourceValue() {
        // Source: constant 7.0.  Upsampled boundary values must equal 7.0.
        Processor src = new DefaultContext(2L).genD(t -> 7.0);
        Processor up  = Upsampler.of(src, 10L);

        assertEquals(Signal.Type.DOUBLE, up.outType()[0]);

        var sig = up.apply().at(0);
        assertEquals(7.0, sig.doubleAt(0),  1e-9, "t=0");
        assertEquals(7.0, sig.doubleAt(5),  1e-9, "t=5");
        assertEquals(7.0, sig.doubleAt(10), 1e-9, "t=10");
    }

    @Test
    void double_linearSource_interpolatesExactly() {
        // For a strictly linear source f(t) = t, Catmull-Rom gives exact results
        // when the four surrounding samples are all available (no edge clamping),
        // i.e. when srcTime >= 1.  Source: 1 Hz → target: 4 Hz.
        Processor src = new DefaultContext(1L).genD(t -> (double) t);
        Processor up  = Upsampler.of(src, 4L);

        var sig = up.apply().at(0);

        // dstTime=4 → srcTime=1.0 (exact boundary) → 1.0
        assertEquals(1.0,  sig.doubleAt(4), 1e-9, "t=4 (boundary)");
        // dstTime=5 → srcTime=1.25 → interpolated 1.25 (linear is exact)
        assertEquals(1.25, sig.doubleAt(5), 1e-9, "t=5");
        // dstTime=6 → srcTime=1.5 → interpolated 1.5
        assertEquals(1.5,  sig.doubleAt(6), 1e-9, "t=6");
        // dstTime=7 → srcTime=1.75 → interpolated 1.75
        assertEquals(1.75, sig.doubleAt(7), 1e-9, "t=7");
        // dstTime=8 → srcTime=2.0 (exact boundary) → 2.0
        assertEquals(2.0,  sig.doubleAt(8), 1e-9, "t=8 (boundary)");
    }
}
