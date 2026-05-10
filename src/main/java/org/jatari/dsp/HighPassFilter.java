package org.jatari.dsp;

import org.jaust.Context;
import org.jaust.Signal;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;

/**
 * First-order IIR high-pass filter implemented as a jaust {@link DefaultProcessor},
 * built as the complement of {@link LowPassFilter}:
 * y_hpf[n] = x[n] − y_lpf[n].
 *
 * <h2>Filter design</h2>
 * <p>The complementary relationship is:
 * <pre>
 *   y_hpf[n] = x[n] − y_lpf[n]
 *            = x[n] − ((1−α)·x[n] + α·y_lpf[n−1])
 *            = α·(x[n] − y_lpf[n−1])
 * </pre>
 * where α = exp(−2π·fc/fs). Frequencies below {@code fc} are attenuated,
 * and frequencies above {@code fc} are passed.
 * When fc = 0, the filter is bypassed (y[n] = x[n]).
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
 */
public class HighPassFilter implements DefaultProcessor {

    private final Context       context;
    private final LowPassFilter lpf;

    /**
     * Constructs a first-order IIR high-pass filter.
     *
     * @param context jaust context whose {@link Context#frequency()} is the
     *                sample rate in Hz
     */
    public HighPassFilter(Context context) {
        this.context = context;
        this.lpf     = new LowPassFilter(context);
    }

    @Override public Context       context()  { return context; }
    @Override public Signal.Type[] inType()   { return new Signal.Type[]{Signal.Type.INT, Signal.Type.DOUBLE}; }
    @Override public Signal.Type[] outType()  { return new Signal.Type[]{Signal.Type.INT}; }

    /**
     * Applies the high-pass filter to the audio signal.
     *
     * @param inputs {@link SignalArray} with signal 0 = INT audio,
     *               signal 1 = DOUBLE cutoff frequency in Hz
     * @return a {@link SignalArray} containing one filtered INT signal
     */
    @Override
    public SignalArray apply(SignalArray inputs) {
        IntSignal    x        = (IntSignal)    inputs.at(0);
        DoubleSignal cutoffHz = (DoubleSignal) inputs.at(1);

        // Compute LPF with the same inputs; y_hpf = x - y_lpf
        SignalArray lpfOut = lpf.apply(inputs);
        IntSignal   yLpf   = (IntSignal) lpfOut.at(0);

        return DefaultArray.a(new IntSignal() {
            @Override public Context context() { return HighPassFilter.this.context; }

            @Override
            public int intAt(long t) {
                // Bypass when cutoff is 0 (alpha = 0 makes LPF a wire, so x - x = 0)
                if (cutoffHz.doubleAt(t) <= 0.0) return x.intAt(t);
                return x.intAt(t) - yLpf.intAt(t);
            }
        });
    }
}
