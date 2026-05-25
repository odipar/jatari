package org.jatari.psg;

import org.jatari.ym.Ym2149Processor;
import org.jatari.ym.Ym2149SimulationState;
import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.SignalArray;

/**
 * A jaust {@link Processor} that drives a cycle-accurate
 * {@link org.jm2149.vhdl.indexed.Ym2149AudioIndexed} simulation from the three-signal output of a {@link PsgCaptureProcessor}.
 *
 * <h2>Input (source processor)</h2>
 * <p>Accepts a source {@link Processor} with <b>3 outputs</b> running at the
 * YM2149 clock frequency (2 MHz):
 * <ul>
 *   <li>Signal 0: BOOL write-enable — {@code true} when a register write occurs.</li>
 *   <li>Signal 1: INT register number (0–13).</li>
 *   <li>Signal 2: INT register value (0–255).</li>
 * </ul>
 *
 * <h2>Output signals (3)</h2>
 * <p>Three INT signals at {@value #YM_CLOCK} Hz:
 * <ul>
 *   <li>Signal 0: channel A 5-bit DAC index (0–31)</li>
 *   <li>Signal 1: channel B 5-bit DAC index (0–31)</li>
 *   <li>Signal 2: channel C 5-bit DAC index (0–31)</li>
 * </ul>
 *
 * <h2>Sequential evaluation</h2>
 * <p>The chip simulation is stateful and must be driven sample-by-sample in
 * strictly increasing order.  Evaluation is lazy: querying any output signal
 * at time {@code t} advances the chip from the last processed sample up to and
 * including {@code t}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PsgCapture   cap  = PsgCaptureParser.parse(Path.of("capture.csv.zip"));
 * Processor    src  = PsgCaptureProcessor.of(cap);    // 3 signals @ 2 MHz
 * Processor    proc = PsgYm2149Processor.of(src);     // 3 INT signals @ 2 MHz
 *
 * SignalArray out = proc.apply();
 * int chA = out.at(0).intAt(1000);
 * }</pre>
 */
public record PsgYm2149Processor(Context context, Processor source)
        implements DefaultProcessor {

    /** YM2149 chip clock frequency in Hz (shared with {@link Ym2149Processor}). */
    public static final long YM_CLOCK = Ym2149Processor.YM_CLOCK;

    private static final Signal.Type[] OUT_TYPES =
            {Signal.Type.INT, Signal.Type.INT, Signal.Type.INT};

    /**
     * Creates a {@link PsgYm2149Processor} driven by the given 3-signal source.
     *
     * @param source processor with 3 outputs (BOOL write, INT reg, INT value)
     *               running at {@value #YM_CLOCK} Hz
     * @return a new processor at {@value #YM_CLOCK} Hz with 3 INT outputs
     */
    public static Processor of(Processor source) {
        return new PsgYm2149Processor(new DefaultContext(YM_CLOCK), source);
    }

    @Override public Signal.Type[] inType()  { return new Signal.Type[]{}; }
    @Override public Signal.Type[] outType() { return OUT_TYPES.clone(); }

    @Override
    public SignalArray apply(SignalArray in) {
        // The source already runs at YM_CLOCK — no resampling required.
        return new SimulationState(source.apply()).buildOutputArray(context);
    }

    /**
     * Holds the mutable {@link Ym2149SimulationState} and implements the
     * PSG-capture-specific register-write logic (one register per write event).
     */
    private static final class SimulationState extends Ym2149SimulationState {

        SimulationState(SignalArray src) { super(src); }

        @Override
        protected void writeRegisters(long i) {
            if (src.at(0).boolAt(i)) {
                chip.writeRegister(src.at(1).intAt(i), src.at(2).intAt(i));
            }
        }
    }
}
