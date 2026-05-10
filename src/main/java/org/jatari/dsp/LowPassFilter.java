package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;

/**
 * First-order IIR low-pass filter implemented as a jaust {@link DefaultProcessor}
 * using the Faust {@code rec} (recursive) operator and the {@code cache} processor.
 *
 * <h2>Filter design</h2>
 * <p>Difference equation: y[n] = (1−α) · x[n] + α · y[n−1]
 * where α = exp(−2π · fc / fs), fc = cutoff frequency in Hz, fs = sample rate in Hz.
 * When fc = 0, α = 0 and the filter is bypassed (y[n] = x[n]).
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li>Signal 0 ({@link Signal.Type#INT}): audio signal to filter</li>
 *   <li>Signal 1 ({@link Signal.Type#DOUBLE}): cutoff frequency in Hz
 *       (may vary per sample; 0 = bypass)</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <ul>
 *   <li>Signal 0 ({@link Signal.Type#INT}): filtered audio</li>
 * </ul>
 *
 * <h2>Implementation</h2>
 * <p>The IIR feedback loop is built with {@link Context#rec(Processor, Processor)}.
 * The {@link Context#cache(Processor)} wrapper is placed on the body processor
 * <em>inside</em> {@code rec}, so the feedback signal references the cached output
 * of each completed sample.  This guarantees that x[n] is sampled exactly once per
 * time tick and that computing y[n] does not re-enter the computation of y[n−1],
 * keeping evaluation O(1) per sample without mutable state in this class.
 */
public class LowPassFilter implements DefaultProcessor {

    private final Context  context;
    private final Processor recFilter;

    /**
     * Constructs a first-order IIR low-pass filter that accepts a per-sample
     * cutoff-frequency signal as its second input.
     *
     * @param context jaust context whose {@link Context#frequency()} is the
     *                sample rate in Hz
     */
    public LowPassFilter(Context context) {
        this.context = context;

        // p1: (y_prev: DOUBLE, x: INT, alpha: DOUBLE) → y: DOUBLE
        // y[n] = (1 − alpha[n]) · x[n] + alpha[n] · y[n−1]
        DefaultProcessor lpfStep = new DefaultProcessor() {
            @Override public Context context() { return LowPassFilter.this.context; }
            @Override public Signal.Type[] inType() {
                return new Signal.Type[]{Signal.Type.DOUBLE, Signal.Type.INT, Signal.Type.DOUBLE};
            }
            @Override public Signal.Type[] outType() { return new Signal.Type[]{Signal.Type.DOUBLE}; }

            @Override
            public SignalArray apply(SignalArray inputs) {
                DoubleSignal yPrev = (DoubleSignal) inputs.at(0);
                IntSignal    x     = (IntSignal)    inputs.at(1);
                DoubleSignal alpha = (DoubleSignal)  inputs.at(2);
                return DefaultArray.a(new DoubleSignal() {
                    @Override public Context context() { return LowPassFilter.this.context; }
                    @Override public double doubleAt(long t) {
                        double a = alpha.doubleAt(t);
                        return (1.0 - a) * x.intAt(t) + a * yPrev.doubleAt(t);
                    }
                });
            }
        };

        // p2 = cache(wire(DOUBLE)): ensures y[n−1] is not re-evaluated if lpfStep
        // reads it more than once in a single tick.
        Processor cachedWire = context.cache(context.wire(Signal.Type.DOUBLE));

        // rec(cache(lpfStep), cachedWire): the cache wraps lpfStep *inside* the
        // rec loop so that RecSignal.rec points to the cached output.  When the
        // feedback path reads y[n−1] it therefore always hits the cache rather
        // than re-entering lpfStep — keeping evaluation O(1) per sample and
        // ensuring x[n] is sampled exactly once per time tick.
        this.recFilter = context.rec(context.cache(lpfStep), cachedWire);
    }

    @Override public Context       context()  { return context; }
    @Override public Signal.Type[] inType()   { return new Signal.Type[]{Signal.Type.INT, Signal.Type.DOUBLE}; }
    @Override public Signal.Type[] outType()  { return new Signal.Type[]{Signal.Type.INT}; }

    /**
     * Applies the low-pass filter to the audio signal.
     *
     * @param inputs {@link SignalArray} with signal 0 = INT audio,
     *               signal 1 = DOUBLE cutoff frequency in Hz
     * @return a {@link SignalArray} containing one filtered INT signal
     */
    @Override
    public SignalArray apply(SignalArray inputs) {
        IntSignal    x        = (IntSignal)    inputs.at(0);
        DoubleSignal cutoffHz = (DoubleSignal) inputs.at(1);
        double       fs       = context.frequency();

        // Convert cutoff Hz → IIR coefficient α; 0 Hz → α = 0 (bypass: y[n] = x[n])
        DoubleSignal alpha = new DoubleSignal() {
            @Override public Context context() { return LowPassFilter.this.context; }
            @Override public double doubleAt(long t) {
                double fc = cutoffHz.doubleAt(t);
                return fc <= 0.0 ? 0.0 : Math.exp(-2.0 * Math.PI * fc / fs);
            }
        };

        SignalArray  recOut  = recFilter.apply(DefaultArray.a(x, alpha));
        DoubleSignal yDouble = (DoubleSignal) recOut.at(0);
        return DefaultArray.a(new IntSignal() {
            @Override public Context context() { return LowPassFilter.this.context; }
            @Override public int intAt(long t) { return (int) yDouble.doubleAt(t); }
        });
    }
}
