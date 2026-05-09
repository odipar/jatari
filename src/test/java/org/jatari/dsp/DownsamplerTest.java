package org.jatari.dsp;

import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Downsampler}.
 */
class DownsamplerTest {

    // -----------------------------------------------------------------------
    // BOOL — decimation
    // -----------------------------------------------------------------------

    @Test
    void bool_decimate_samplesAtAlignedSourceIndex() {
        // Source: 10 Hz, alternates true/false each sample.
        // Target: 2 Hz (ratio 5).  Dst sample n picks src sample n*5.
        Processor src = new DefaultContext(10L).genB(t -> t % 2 == 0); // even→true
        Processor down = Downsampler.of(src, 2L);

        assertEquals(2L, down.context().frequency(), "output frequency");
        assertEquals(Signal.Type.BOOL, down.outType()[0], "output type");

        var sig = down.apply().at(0);

        // t=0 → srcTime=0 → even → true
        assertTrue(sig.boolAt(0),  "dst t=0 → src t=0 (even=true)");
        // t=1 → srcTime=5 → odd → false
        assertFalse(sig.boolAt(1), "dst t=1 → src t=5 (odd=false)");
        // t=2 → srcTime=10 → even → true
        assertTrue(sig.boolAt(2),  "dst t=2 → src t=10 (even=true)");
    }

    @Test
    void bool_rejectsNonDownsampling() {
        Processor src = new DefaultContext(10L).genB(t -> true);
        assertThrows(IllegalArgumentException.class, () -> Downsampler.of(src, 50L));
        assertThrows(IllegalArgumentException.class, () -> Downsampler.of(src, 10L));
    }

    // -----------------------------------------------------------------------
    // INT — decimation
    // -----------------------------------------------------------------------

    @Test
    void int_decimate_picksNearestLowerSourceIndex() {
        // Source: 10 Hz, value = srcTime.  Target: 2 Hz (ratio 5).
        Processor src  = new DefaultContext(10L).genI(t -> (int) t);
        Processor down = Downsampler.of(src, 2L);

        assertEquals(Signal.Type.INT, down.outType()[0]);

        var sig = down.apply().at(0);

        // t=0 → srcTime=0 → 0
        assertEquals(0, sig.intAt(0), "dst t=0");
        // t=1 → srcTime=5 → 5
        assertEquals(5, sig.intAt(1), "dst t=1");
        // t=2 → srcTime=10 → 10
        assertEquals(10, sig.intAt(2), "dst t=2");
    }

    // -----------------------------------------------------------------------
    // LONG — decimation
    // -----------------------------------------------------------------------

    @Test
    void long_decimate_picksNearestLowerSourceIndex() {
        // Source: 6 Hz, value = srcTime * 100.  Target: 2 Hz (ratio 3).
        Processor src  = new DefaultContext(6L).genL(t -> t * 100L);
        Processor down = Downsampler.of(src, 2L);

        assertEquals(Signal.Type.LONG, down.outType()[0]);

        var sig = down.apply().at(0);

        assertEquals(0L,   sig.longAt(0), "dst t=0 → src t=0");
        assertEquals(300L, sig.longAt(1), "dst t=1 → src t=3");
        assertEquals(600L, sig.longAt(2), "dst t=2 → src t=6");
    }

    // -----------------------------------------------------------------------
    // DOUBLE — windowed average (anti-aliased)
    // -----------------------------------------------------------------------

    @Test
    void double_average_integersOverWindow() {
        // Source: 10 Hz, value = srcTime (0,1,2,…).  Target: 2 Hz (window 5).
        // Dst t=0: avg(0,1,2,3,4) = 2.0
        // Dst t=1: avg(5,6,7,8,9) = 7.0
        Processor src  = new DefaultContext(10L).genD(t -> (double) t);
        Processor down = Downsampler.of(src, 2L);

        assertEquals(Signal.Type.DOUBLE, down.outType()[0]);

        var sig = down.apply().at(0);

        assertEquals(2.0, sig.doubleAt(0), 1e-9, "window 0..4");
        assertEquals(7.0, sig.doubleAt(1), 1e-9, "window 5..9");
    }

    @Test
    void double_average_constantSource_returnsConstant() {
        // Averaging a constant should return the constant.
        Processor src  = new DefaultContext(100L).genD(t -> 42.0);
        Processor down = Downsampler.of(src, 10L);
        var sig = down.apply().at(0);

        for (int t = 0; t < 5; t++) {
            assertEquals(42.0, sig.doubleAt(t), 1e-9,
                    "constant source at dst t=%d".formatted(t));
        }
    }

    // -----------------------------------------------------------------------
    // Multi-signal downsampler
    // -----------------------------------------------------------------------

    @Test
    void multiSignal_allTypesPassThrough() {
        // Build a 4-output source (BOOL, INT, LONG, DOUBLE) at 10 Hz.
        var ctx = new DefaultContext(10L);
        Processor src = ctx.par(
                ctx.genB(t -> t % 3 == 0),
                ctx.genI(t -> (int) t),
                ctx.genL(t -> t * 10L),
                ctx.genD(t -> t * 0.5)
        );

        Processor down = Downsampler.of(src, 2L);

        assertEquals(4, down.outType().length);
        assertEquals(Signal.Type.BOOL,   down.outType()[0]);
        assertEquals(Signal.Type.INT,    down.outType()[1]);
        assertEquals(Signal.Type.LONG,   down.outType()[2]);
        assertEquals(Signal.Type.DOUBLE, down.outType()[3]);

        var signals = down.apply();

        // Dst t=0 → src t=0
        assertTrue(signals.at(0).boolAt(0));   // 0 % 3 == 0 → true
        assertEquals(0, signals.at(1).intAt(0));
        assertEquals(0L, signals.at(2).longAt(0));
        // DOUBLE is windowed average over src [0,5): 0*0.5+1*0.5+2*0.5+3*0.5+4*0.5 / 5 = 1.0
        assertEquals(1.0, signals.at(3).doubleAt(0), 1e-9);
    }
}
