package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Signal;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;

/**
 * First-order IIR low-pass filter implemented as a jaust {@link DefaultProcessor}.
 *
 * <h2>Filter design</h2>
 * <p>Difference equation: y[n] = β · x[n] + α · y[n–1]
 * where α = exp(–2π · fc / fs) (feedback coefficient)
 * and β = 1 – α (feed-forward coefficient),
 * fc = cutoff frequency in Hz, fs = sample rate in Hz.
 *
 * <h2>Sequential access required</h2>
 * <p>The internal state (y[n–1]) is updated incrementally on each call to
 * {@link IntSignal#intAt(long)}.  Signals must be evaluated in strictly
 * increasing time order (t = 0, 1, 2, …), consistent with the sequential
 * playback loop in {@link org.jatari.player.YmPlayer}.
 *
 * <h2>Type contract</h2>
 * <ul>
 *   <li><b>Input</b>: one {@link Signal.Type#INT} signal</li>
 *   <li><b>Output</b>: one {@link Signal.Type#INT} signal (filtered)</li>
 * </ul>
 */
public class LowPassFilter implements DefaultProcessor {

    private final Context context;
    private final double  alpha;   // IIR feedback coefficient  = exp(−2π·fc/fs)
    private final double  beta;    // feed-forward coefficient  = 1 − α

    /**
     * Constructs a first-order IIR low-pass filter.
     *
     * @param context  jaust context whose {@link Context#frequency()} is the
     *                 sample rate in Hz
     * @param cutoffHz desired −3 dB cutoff frequency in Hz
     */
    public LowPassFilter(Context context, double cutoffHz) {
        this.context = context;
        this.alpha   = Math.exp(-2.0 * Math.PI * cutoffHz / context.frequency());
        this.beta    = 1.0 - alpha;
    }

    @Override public Context       context()  { return context; }
    @Override public Signal.Type[] inType()   { return new Signal.Type[]{Signal.Type.INT}; }
    @Override public Signal.Type[] outType()  { return new Signal.Type[]{Signal.Type.INT}; }

    /**
     * Wraps the single input INT signal with the IIR low-pass filter and
     * returns a filtered INT signal.
     *
     * @param inputs a {@link SignalArray} containing exactly one INT signal
     * @return a {@link SignalArray} containing one filtered INT signal
     */
    @Override
    public SignalArray apply(SignalArray inputs) {
        Signal input      = inputs.at(0);
        double localAlpha = alpha;
        double localBeta  = beta;
        // y[n-1]: mutable state updated sequentially with each sample query
        double[] prevY = {0.0};

        IntSignal filtered = new IntSignal() {
            @Override public Context context() { return LowPassFilter.this.context; }

            @Override
            public int intAt(long t) {
                double x = input.intAt(t);
                prevY[0] = localBeta * x + localAlpha * prevY[0];
                return (int) prevY[0];
            }
        };
        return DefaultArray.a(filtered);
    }
}
