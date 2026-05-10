package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Signal;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import org.jaust.context.DefaultContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LowPassFilter}.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>The input signal is sampled <em>exactly once</em> per time tick — the
 *       feedback path must not re-enter the input computation for a tick that
 *       has already been evaluated.</li>
 *   <li>The filter does not cause deep recursion (stack overflow) when its
 *       output is used as the input of a second {@link LowPassFilter} instance,
 *       which is the pattern used by {@link HighPassFilter}.</li>
 *   <li>The difference equation y[n] = (1−α)·x[n] + α·y[n−1] is correct.</li>
 * </ul>
 */
class LowPassFilterTest {

    private static final int    SAMPLE_RATE = 44_100;
    private static final double CUTOFF_HZ   = 4_000.0;

    // ------------------------------------------------------------------
    // Helper: constant INT signal
    // ------------------------------------------------------------------

    private static IntSignal constantInt(Context ctx, int value) {
        return new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t)  { return value; }
        };
    }

    // ------------------------------------------------------------------
    // Helper: constant DOUBLE signal
    // ------------------------------------------------------------------

    private static DoubleSignal constantDouble(Context ctx, double value) {
        return new DoubleSignal() {
            @Override public Context context()      { return ctx; }
            @Override public double  doubleAt(long t) { return value; }
        };
    }

    // ------------------------------------------------------------------
    // Test 1: input sampled exactly once per time tick
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code x.intAt(t)} is called <em>exactly once</em> for
     * each tick {@code t} when the filter output is pulled sequentially.
     *
     * <p>With the buggy wiring ({@code cache(rec(lpfStep, ...))}), the feedback
     * path re-enters the uncached {@code lpfStep} output for {@code t−1},
     * causing {@code x.intAt(t−1)} to be called a second time while computing
     * {@code y[t]}.  The fixed wiring ({@code rec(cache(lpfStep), ...)})
     * avoids that by hitting the cache on the feedback read.
     */
    @Test
    void filter_samplesInputExactlyOncePerTick() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);
        LowPassFilter  lpf = new LowPassFilter(ctx);

        int N = 50;
        int[] callCount = new int[N];

        IntSignal x = new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t) {
                if (t >= 0 && t < N) callCount[(int) t]++;
                return 1_000;
            }
        };

        SignalArray output = lpf.apply(DefaultArray.a(x, constantDouble(ctx, CUTOFF_HZ)));
        IntSignal   y      = (IntSignal) output.at(0);

        // Pull all samples sequentially
        for (long t = 0; t < N; t++) {
            y.intAt(t);
        }

        // Every tick must have been sampled exactly once
        for (int t = 0; t < N; t++) {
            assertEquals(1, callCount[t],
                "x.intAt(%d) was called %d time(s) instead of exactly 1"
                    .formatted(t, callCount[t]));
        }
    }

    // ------------------------------------------------------------------
    // Test 2: no deep recursion when LPF output is chained as another
    //         LPF's input (the pattern used by HighPassFilter)
    // ------------------------------------------------------------------

    /**
     * Verifies that the filter does not cause a {@link StackOverflowError}
     * when its output is fed as the input to a second {@link LowPassFilter}.
     *
     * <p>This mirrors what {@link HighPassFilter} does internally: it creates
     * a {@link LowPassFilter} and passes the HPF's own input (which is already
     * an LPF output) through it.  With the buggy wiring the feedback path of
     * the inner LPF reaches back into the outer LPF's uncached output at
     * {@code t−1}; because the outer LPF's cache was already advanced to
     * {@code t} the inner LPF must recurse all the way to {@code t = 0},
     * blowing the call stack after a few thousand samples.
     */
    @Test
    void filter_doesNotStackOverflowWhenOutputChainedAsInput() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);

        // Outer LPF — processes a simple constant audio source
        LowPassFilter outerLpf = new LowPassFilter(ctx);
        Signal outerOut = outerLpf.apply(
                DefaultArray.a(constantInt(ctx, 1_000), constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        // Inner LPF — uses the outer LPF's output as its audio input, exactly
        // as HighPassFilter does when computing y_hpf = x − y_lpf.
        LowPassFilter innerLpf = new LowPassFilter(ctx);
        IntSignal innerOut = (IntSignal) innerLpf.apply(
                DefaultArray.a(outerOut, constantDouble(ctx, CUTOFF_HZ))
        ).at(0);

        // 10 000 sequential samples — would overflow the stack within ~500
        // samples with the buggy cache placement.
        assertDoesNotThrow(() -> {
            for (long t = 0; t < 10_000; t++) {
                innerOut.intAt(t);
            }
        }, "LPF output chained as another LPF's input must not cause a stack overflow");
    }

    // ------------------------------------------------------------------
    // Test 3: correct IIR difference equation
    // ------------------------------------------------------------------

    /**
     * Spot-checks the first few output samples against the hand-computed
     * difference equation y[n] = (1−α)·x[n] + α·y[n−1] with y[−1] = 0.
     */
    @Test
    void filter_producesCorrectDifferenceEquation() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);
        LowPassFilter  lpf = new LowPassFilter(ctx);

        double fs      = SAMPLE_RATE;
        double fc      = CUTOFF_HZ;
        double alpha   = Math.exp(-2.0 * Math.PI * fc / fs);
        int    xConst  = 10_000;

        IntSignal x = constantInt(ctx, xConst);
        SignalArray output = lpf.apply(DefaultArray.a(x, constantDouble(ctx, fc)));
        IntSignal   y      = (IntSignal) output.at(0);

        double yPrev = 0.0;
        for (long t = 0; t < 20; t++) {
            double yExpected = (1.0 - alpha) * xConst + alpha * yPrev;
            assertEquals((int) yExpected, y.intAt(t),
                "y[%d] should match IIR formula".formatted(t));
            yPrev = yExpected;
        }
    }

    // ------------------------------------------------------------------
    // Test 4: bypass when cutoff is zero
    // ------------------------------------------------------------------

    /**
     * When the cutoff frequency is 0 Hz the filter must pass the input
     * through unchanged (α = 0, y[n] = x[n]).
     */
    @Test
    void filter_bypassesWhenCutoffIsZero() {
        DefaultContext ctx = new DefaultContext(SAMPLE_RATE);
        LowPassFilter  lpf = new LowPassFilter(ctx);

        IntSignal x = new IntSignal() {
            @Override public Context context() { return ctx; }
            @Override public int intAt(long t)  { return (int) (t * 100); }
        };

        SignalArray output = lpf.apply(DefaultArray.a(x, constantDouble(ctx, 0.0)));
        IntSignal   y      = (IntSignal) output.at(0);

        for (long t = 0; t < 20; t++) {
            assertEquals(x.intAt(t), y.intAt(t),
                "With cutoff=0 the filter must be a pass-through at t=%d".formatted(t));
        }
    }
}
