package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import org.jaust.context.DefaultContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HighPassFilter}.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>The filter does not cause a {@link StackOverflowError} when switched
 *       from bypass (fc = 0) to active after many sequential ticks — the root
 *       cause of the original bug.</li>
 *   <li>Sequential evaluation for many samples does not overflow the stack.</li>
 *   <li>The output satisfies y_hpf[n] = x[n] − y_lpf[n].</li>
 *   <li>When fc = 0 the filter passes the input unchanged (bypass).</li>
 * </ul>
 */
class HighPassFilterTest {

    private static final int    SAMPLE_RATE = 44_100;
    private static final double CUTOFF_HZ   = 4_000.0;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static IntSignal constantInt(Context ctx, int value) {
        return new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t)  { return value; }
        };
    }

    private static DoubleSignal constantDouble(Context ctx, double value) {
        return new DoubleSignal() {
            @Override public Context context()      { return ctx; }
            @Override public double  doubleAt(long t) { return value; }
        };
    }

    // ------------------------------------------------------------------
    // Test 1: no stack overflow after bypass → active transition
    // ------------------------------------------------------------------

    /**
     * Verifies that switching the HPF from bypass (fc = 0) to active
     * after N ticks does not cause a {@link StackOverflowError}.
     *
     * <p>The original bug: the bypass path ({@code if (fc <= 0) return x;})
     * never called {@code yLpf.intAt(t)}, so the inner LPF's rec cache was
     * never populated.  When the filter was enabled at tick N the inner LPF
     * had to recurse N levels deep to rebuild its history, blowing the call
     * stack after a few thousand samples.
     *
     * <p>The fix always evaluates {@code yLpf.intAt(t)} on every tick so the
     * cache stays current, reducing the transition cost to O(1).
     */
    @Test
    void filter_doesNotStackOverflowAfterBypassToActiveTransition() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);

        // Use an outer LPF as the audio source, which is the realistic pipeline.
        LowPassFilter outerLpf   = new LowPassFilter(ctx);
        IntSignal     outerOut   = (IntSignal) outerLpf.apply(
                DefaultArray.a(constantInt(ctx, 1_000), constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        HighPassFilter hpf = new HighPassFilter(ctx);

        // Bypass for N ticks (fc = 0), then enable.
        final int N = 5_000;
        long[] tick = {0};

        DoubleSignal cutoff = new DoubleSignal() {
            @Override public Context context()      { return ctx; }
            @Override public double  doubleAt(long t) {
                return (t < N) ? 0.0 : CUTOFF_HZ;
            }
        };

        IntSignal hpfOut = (IntSignal) hpf.apply(DefaultArray.a(outerOut, cutoff)).at(0);

        // Pull N bypass samples then one active sample — must not overflow.
        assertDoesNotThrow(() -> {
            for (long t = 0; t <= N; t++) {
                hpfOut.intAt(t);
            }
        }, "HPF must not cause a stack overflow when switching from bypass to active");
    }

    // ------------------------------------------------------------------
    // Test 2: no stack overflow during long sequential run
    // ------------------------------------------------------------------

    /**
     * Verifies that the HPF does not cause a {@link StackOverflowError}
     * when evaluated sequentially for 10 000 active-mode ticks.
     */
    @Test
    void filter_doesNotStackOverflowDuringSequentialEvaluation() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);
        HighPassFilter hpf = new HighPassFilter(ctx);

        IntSignal x = new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t)  { return (int) (t % 1_000); }
        };

        IntSignal hpfOut = (IntSignal) hpf.apply(
                DefaultArray.a(x, constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        assertDoesNotThrow(() -> {
            for (long t = 0; t < 10_000; t++) {
                hpfOut.intAt(t);
            }
        }, "HPF must not cause a stack overflow during sequential evaluation");
    }

    // ------------------------------------------------------------------
    // Test 3: correct output  y_hpf[n] = x[n] − y_lpf[n]
    // ------------------------------------------------------------------

    /**
     * Verifies that the HPF output equals {@code x[n] − y_lpf[n]} for the
     * first few samples, where {@code y_lpf} is an independently constructed
     * {@link LowPassFilter} applied to the same input.
     */
    @Test
    void filter_outputEqualsInputMinusLpf() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);

        // Reference LPF — same cutoff, applied to the same constant input.
        LowPassFilter lpf = new LowPassFilter(ctx);
        IntSignal x = constantInt(ctx, 10_000);

        IntSignal yLpf = (IntSignal) lpf.apply(
                DefaultArray.a(x, constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        // HPF under test.
        HighPassFilter hpf    = new HighPassFilter(ctx);
        IntSignal      hpfOut = (IntSignal) hpf.apply(
                DefaultArray.a(x, constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        for (long t = 0; t < 20; t++) {
            int expected = x.intAt(t) - yLpf.intAt(t);
            assertEquals(expected, hpfOut.intAt(t),
                    "y_hpf[%d] should equal x[%d] − y_lpf[%d]".formatted(t, t, t));
        }
    }

    // ------------------------------------------------------------------
    // Test 4: bypass when cutoff is zero
    // ------------------------------------------------------------------

    /**
     * When the cutoff frequency is 0 Hz the HPF must pass the input
     * through unchanged (bypass mode).
     */
    @Test
    void filter_bypassesWhenCutoffIsZero() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);
        HighPassFilter hpf = new HighPassFilter(ctx);

        IntSignal x = new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t)  { return (int) (t * 100); }
        };

        IntSignal hpfOut = (IntSignal) hpf.apply(
                DefaultArray.a(x, constantDouble(ctx, 0.0))
        ).at(0);

        for (long t = 0; t < 20; t++) {
            assertEquals(x.intAt(t), hpfOut.intAt(t),
                    "With cutoff=0 the HPF must be a pass-through at t=%d".formatted(t));
        }
    }
}
