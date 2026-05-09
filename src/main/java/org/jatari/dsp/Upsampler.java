package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;

/**
 * Converts a {@link Processor}'s output signals from a lower source frequency
 * to a higher target frequency.
 *
 * <h2>Per-type upsampling strategy</h2>
 * <ul>
 *   <li><b>BOOL</b> — impulse: {@code true} only at exact source-sample
 *       boundaries (where a source tick aligns with a target tick).  All
 *       other target samples return {@code false}.  This preserves the
 *       "event occurred at this instant" semantics used by the YM register-
 *       write flag: a 50 Hz signal that is always {@code true} becomes
 *       {@code true} only once every 5 000 samples after upsampling to
 *       250 kHz.</li>
 *   <li><b>INT / LONG</b> — zero-order hold (nearest lower sample).
 *       Appropriate for stepped / register-style values that do not
 *       interpolate between discrete states.</li>
 *   <li><b>DOUBLE</b> — Catmull-Rom cubic spline interpolation (highest
 *       quality continuous-signal reconstruction).  Uses four neighbouring
 *       source samples for smooth, derivative-continuous reconstruction.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Processor src = YmFileProcessor.of(ym);        // 15 outputs @ 50 Hz
 * Processor up  = Upsampler.of(src, 250_000L);   // 15 outputs @ 250 kHz
 * }</pre>
 */
public final class Upsampler {

    private Upsampler() {}

    /**
     * Builds an upsampled {@link Processor} from {@code source}.
     *
     * <p>The returned processor has the same number and types of outputs as
     * {@code source} but operates at {@code targetFreq}.  It has zero formal
     * inputs; the source signals are captured at construction time.
     *
     * @param source     the source processor (must have {@code targetFreq >
     *                   source.context().frequency()})
     * @param targetFreq the desired output sample rate in Hz
     * @return a new processor at {@code targetFreq}
     * @throws IllegalArgumentException if {@code targetFreq} is not greater
     *                                  than the source frequency
     */
    public static Processor of(Processor source, long targetFreq) {
        long srcFreq = source.context().frequency();
        if (targetFreq <= srcFreq) {
            throw new IllegalArgumentException(
                    "targetFreq (%d) must be greater than source frequency (%d)"
                            .formatted(targetFreq, srcFreq));
        }

        // GCD-reduced ratio: every 'period' dst samples corresponds to one src sample.
        long g      = gcd(srcFreq, targetFreq);
        long period = targetFreq / g; // dst steps between two consecutive src samples

        Context    dstCtx     = new DefaultContext(targetFreq);
        SignalArray srcSignals = source.apply();
        Signal.Type[] types   = source.outType();

        Processor[] procs = new Processor[types.length];
        for (int i = 0; i < types.length; i++) {
            procs[i] = upsampleSignal(srcSignals.at(i), types[i],
                                      srcFreq, targetFreq, period, dstCtx);
        }
        return dstCtx.par(procs);
    }

    // -----------------------------------------------------------------------
    // Per-type upsampling
    // -----------------------------------------------------------------------

    private static Processor upsampleSignal(Signal sig, Signal.Type type,
                                            long srcFreq, long dstFreq,
                                            long period, Context ctx) {
        return switch (type) {
            case BOOL -> ctx.genB(dstTime -> {
                // Impulse: true only at exact source-sample boundaries AND
                // only when the source signal itself is true at that point.
                if (dstTime % period != 0) return false;
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.boolAt(srcTime);
            });

            case INT -> ctx.genI(dstTime -> {
                // Zero-order hold: floor the source-time index.
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.intAt(srcTime);
            });

            case LONG -> ctx.genL(dstTime -> {
                // Zero-order hold: floor the source-time index.
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.longAt(srcTime);
            });

            case DOUBLE -> ctx.genD(dstTime -> {
                // Catmull-Rom cubic spline interpolation.
                double srcTimeD = (double) dstTime * srcFreq / dstFreq;
                long   t1       = (long) srcTimeD;          // floor
                double frac     = srcTimeD - t1;            // [0, 1)

                if (frac == 0.0) return sig.doubleAt(t1);

                // Four surrounding source samples for the Catmull-Rom formula.
                double p0 = sig.doubleAt(Math.max(0L, t1 - 1L));
                double p1 = sig.doubleAt(t1);
                double p2 = sig.doubleAt(t1 + 1L);
                double p3 = sig.doubleAt(t1 + 2L);

                double t  = frac;
                double t2 = t * t;
                double t3 = t2 * t;

                // Standard Catmull-Rom formula (alpha = 0.5).
                return 0.5 * ((2.0 * p1)
                        + (-p0 + p2) * t
                        + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                        + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
            });
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static long gcd(long a, long b) {
        return b == 0L ? a : gcd(b, a % b);
    }
}
