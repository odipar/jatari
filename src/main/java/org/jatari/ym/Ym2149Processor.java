package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

/**
 * A jaust {@link Processor} that drives a cycle-accurate {@link Ym2149AudioIndexed}
 * simulation and exposes the three channel outputs as jaust signals.
 *
 * <h2>Input (source processor)</h2>
 * <p>Accepts a source {@link Processor} with <b>15 outputs</b> running at the
 * YM file frame rate (typically 50 Hz):
 * <ul>
 *   <li>Signals 0–13: INT values for YM2149 registers R0–R13</li>
 *   <li>Signal 14: BOOL write-enable ({@code true} = write all registers to chip)</li>
 * </ul>
 * <p>The source is resampled from its frequency up to the YM2149 clock frequency
 * ({@value YM_CLOCK} Hz) using the jaust {@code resample} combinator.  At
 * non-write positions the write-enable is {@code false} and register values are
 * zero (zero-stuffing); the chip advances without a register update at those steps.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>3 INT outputs</b> at
 * {@value YM_CLOCK} Hz:
 * <ul>
 *   <li>Signal 0: channel A 5-bit DAC index (0–31)</li>
 *   <li>Signal 1: channel B 5-bit DAC index (0–31)</li>
 *   <li>Signal 2: channel C 5-bit DAC index (0–31)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * YmFile ym = YmFileParser.parse(Path.of("music.ym"));
 * Processor fileProc = YmFileProcessor.of(ym);       // 15 signals @ 50 Hz
 * Processor ymProc   = Ym2149Processor.of(fileProc); // 3 INT signals @ 250 kHz
 *
 * SignalArray out = ymProc.apply();
 * int chA = out.at(0).intAt(1000); // channel A DAC index at cycle 1000
 * int chB = out.at(1).intAt(1000);
 * int chC = out.at(2).intAt(1000);
 * }</pre>
 *
 * <h2>Sequential evaluation</h2>
 * <p>The underlying chip simulation is stateful: it must be driven
 * sample-by-sample in strictly increasing order.  Querying a signal at time
 * {@code t} advances the chip from the last processed sample up to and
 * including {@code t}.  Querying at a time <em>earlier</em> than the last
 * processed sample returns the cached value for the last processed sample and
 * does not rewind the simulation.
 */
public record Ym2149Processor(Context context, Processor source) implements DefaultProcessor {

    /** YM2149 chip clock frequency in Hz. */
    public static final long YM_CLOCK = 250_000L;

    private static final Signal.Type[] OUT_TYPES =
            {Signal.Type.INT, Signal.Type.INT, Signal.Type.INT};

    /**
     * Creates a {@link Ym2149Processor} that resamples {@code source} to
     * {@value YM_CLOCK} Hz and drives the chip simulation.
     *
     * @param source processor with 15 outputs (14 INT registers + 1 BOOL write-enable)
     *               at any source frequency lower than {@value YM_CLOCK} Hz
     * @return a new processor at {@value YM_CLOCK} Hz with 3 INT outputs
     */
    public static Processor of(Processor source) {
        return new Ym2149Processor(new DefaultContext(YM_CLOCK), source);
    }

    public Signal.Type[] inType()  { return new Signal.Type[]{}; }

    public Signal.Type[] outType() { return OUT_TYPES.clone(); }

    public SignalArray apply(SignalArray in) {
        // Resample the 15-signal source up to YM_CLOCK Hz.
        // BOOL write-enable: true only at sample positions aligned with
        // the original source frame boundaries; false (zero-stuffed) elsewhere.
        Processor upsampled = context.resample(source);
        SignalArray src = upsampled.apply();

        // All three output signals share a single mutable state that
        // advances the chip simulation lazily and sequentially.
        var state = new SimulationState(src);

        IntSignal chA = new IntSignal() {
            public Context context() { return Ym2149Processor.this.context; }
            public int intAt(long t)  { return state.getChA(t); }
        };
        IntSignal chB = new IntSignal() {
            public Context context() { return Ym2149Processor.this.context; }
            public int intAt(long t)  { return state.getChB(t); }
        };
        IntSignal chC = new IntSignal() {
            public Context context() { return Ym2149Processor.this.context; }
            public int intAt(long t)  { return state.getChC(t); }
        };
        return DefaultArray.a(chA, chB, chC);
    }

    /**
     * Holds the mutable {@link Ym2149AudioIndexed} simulation state and advances
     * it lazily in sample order.
     *
     * <p>When any output signal queries sample time {@code t}, the simulator
     * advances one clock cycle at a time from the last processed sample up to
     * and including {@code t}.  At each step:
     * <ol>
     *   <li>If the upsampled write-enable signal is {@code true}, all 14
     *       register values are written to the chip via
     *       {@link Ym2149AudioIndexed#writeRegister}.</li>
     *   <li>One rising-clock-edge is simulated with standard operating
     *       conditions: {@code enClkPsgI=true, selNI=false, resetNI=true}.</li>
     *   <li>The three DAC-index outputs are latched.</li>
     * </ol>
     */
    private static final class SimulationState {

        private final SignalArray src;
        private final Ym2149AudioIndexed chip = new Ym2149AudioIndexed();

        private long lastTime = -1;
        private int chA = 0;
        private int chB = 0;
        private int chC = 0;

        SimulationState(SignalArray src) {
            this.src = src;
            chip.applyReset();
        }

        /** Advance the simulation to sample {@code t} (inclusive). */
        void advanceTo(long t) {
            for (long i = lastTime + 1; i <= t; i++) {
                // Write all 14 registers when the write-enable pulse is present.
                if (src.at(14).boolAt(i)) {
                    for (int r = 0; r < 14; r++) {
                        chip.writeRegister(r, src.at(r).intAt(i));
                    }
                }
                // Advance the chip by one YM2149 clock cycle.
                chip.risingEdge(true, false, true, false, false, 0);
                chA = chip.getChAIndexO();
                chB = chip.getChBIndexO();
                chC = chip.getChCIndexO();
                lastTime = i;
            }
        }

        int getChA(long t) { advanceTo(t); return chA; }
        int getChB(long t) { advanceTo(t); return chB; }
        int getChC(long t) { advanceTo(t); return chC; }
    }
}
