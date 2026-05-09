package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;

/**
 * Converts a {@link Processor}'s output signals from a higher source frequency
 * to a lower target frequency.
 *
 * <h2>Per-type downsampling strategy</h2>
 * <ul>
 *   <li><b>BOOL</b> — decimate: sample the source at the nearest lower
 *       source-index that aligns with each target sample.</li>
 *   <li><b>INT / LONG</b> — decimate: nearest lower source sample, which is
 *       lossless for stepped/register-style values.</li>
 *   <li><b>DOUBLE</b> — windowed-average anti-aliasing: each output sample
 *       averages all source samples whose indices fall within the
 *       corresponding source window, preventing aliasing artefacts.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Processor src  = ...;                            // some processor @ 250 kHz
 * Processor down = Downsampler.of(src, 44_100L);  // outputs @ 44.1 kHz
 * }</pre>
 */
public final class Downsampler {

    private Downsampler() {}

    /**
     * Builds a downsampled {@link Processor} from {@code source}.
     *
     * <p>The returned processor has the same number and types of outputs as
     * {@code source} but operates at {@code targetFreq}.  It has zero formal
     * inputs; the source signals are captured at construction time.
     *
     * @param source     the source processor (must have {@code targetFreq <
     *                   source.context().frequency()})
     * @param targetFreq the desired output sample rate in Hz
     * @return a new processor at {@code targetFreq}
     * @throws IllegalArgumentException if {@code targetFreq} is not less than
     *                                  the source frequency
     */
    public static Processor of(Processor source, long targetFreq) {
        long srcFreq = source.context().frequency();
        if (targetFreq >= srcFreq) {
            throw new IllegalArgumentException(
                    "targetFreq (%d) must be less than source frequency (%d)"
                            .formatted(targetFreq, srcFreq));
        }

        Context    dstCtx     = new DefaultContext(targetFreq);
        SignalArray srcSignals = source.apply();
        Signal.Type[] types   = source.outType();

        Processor[] procs = new Processor[types.length];
        for (int i = 0; i < types.length; i++) {
            procs[i] = downsampleSignal(srcSignals.at(i), types[i],
                                        srcFreq, targetFreq, dstCtx);
        }
        return dstCtx.par(procs);
    }

    // -----------------------------------------------------------------------
    // Per-type downsampling
    // -----------------------------------------------------------------------

    private static Processor downsampleSignal(Signal sig, Signal.Type type,
                                              long srcFreq, long dstFreq,
                                              Context ctx) {
        return switch (type) {
            case BOOL -> ctx.genB(dstTime -> {
                // Decimate: sample source at the aligned source index.
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.boolAt(srcTime);
            });

            case INT -> ctx.genI(dstTime -> {
                // Decimate: nearest lower source index.
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.intAt(srcTime);
            });

            case LONG -> ctx.genL(dstTime -> {
                // Decimate: nearest lower source index.
                long srcTime = dstTime * srcFreq / dstFreq;
                return sig.longAt(srcTime);
            });

            case DOUBLE -> ctx.genD(dstTime -> {
                // Anti-aliased: average all source samples in the window
                // [srcStart, srcEnd) that maps to this target sample.
                long srcStart = dstTime * srcFreq / dstFreq;
                long srcEnd   = (dstTime + 1L) * srcFreq / dstFreq;
                long count    = srcEnd - srcStart;
                if (count <= 0L) return sig.doubleAt(srcStart);
                double sum = 0.0;
                for (long s = srcStart; s < srcEnd; s++) {
                    sum += sig.doubleAt(s);
                }
                return sum / count;
            });
        };
    }
}
