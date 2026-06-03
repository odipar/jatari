package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.SignalArray;

/**
 * A jaust {@link Processor} that drives a cycle-accurate
 * {@link org.jm2149.vhdl.indexed.Ym2149AudioIndexed} simulation and exposes the three channel outputs as jaust signals.
 *
 * <h2>Input (source processor)</h2>
 * <p>Accepts a source {@link Processor} with <b>15 outputs</b> running at the
 * YM file frame rate (typically 50 Hz):
 * <ul>
 *   <li>Signals 0–13: INT values for YM2149 registers R0–R13</li>
 *   <li>Signal 14: BOOL write-enable ({@code true} = write all registers to chip)</li>
 * </ul>
 * <p>The source is resampled from its frequency up to the YM2149 master clock
 * frequency using the jaust {@code resample} combinator.  At non-write positions
 * the write-enable is {@code false} and register values are zero (zero-stuffing);
 * the chip advances without a register update at those steps.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>3 INT outputs</b> at the
 * configured master clock frequency (PAL by default):
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
 *
 * // PAL (default)
 * Processor ymProc = Ym2149Processor.of(fileProc);
 *
 * // NTSC
 * Processor ymProcNtsc = Ym2149Processor.of(fileProc, Ym2149Processor.YM2149_F_MASTER_NTSC);
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

    /** PAL system crystal frequency in Hz (32.084988 MHz). */
    public static final double SYSTEM_CRYSTAL_PAL  = 32_084_988;

    /** NTSC system crystal frequency in Hz (32.042440 MHz). */
    public static final double SYSTEM_CRYSTAL_NTSC = 32_042_440;

    /** PAL YM2149 master clock frequency: SYSTEM_CRYSTAL_PAL / 16 Hz (~2.005 MHz). */
    public static final double YM2149_F_MASTER_PAL  = SYSTEM_CRYSTAL_PAL  / 16;

    /** NTSC YM2149 master clock frequency: SYSTEM_CRYSTAL_NTSC / 16 Hz (~2.003 MHz). */
    public static final double YM2149_F_MASTER_NTSC = SYSTEM_CRYSTAL_NTSC / 16;

    /** YM2149 chip clock frequency in Hz (PAL default: {@code (long) YM2149_F_MASTER_PAL}). */
    public static final long YM_CLOCK = (long) YM2149_F_MASTER_PAL;

    private static final Signal.Type[] OUT_TYPES =
            {Signal.Type.INT, Signal.Type.INT, Signal.Type.INT};

    /**
     * Creates a {@link Ym2149Processor} with the PAL master clock
     * ({@code (long) YM2149_F_MASTER_PAL} Hz).
     *
     * @param source processor with 15 outputs (14 INT registers + 1 BOOL write-enable)
     *               at any source frequency lower than the master clock
     * @return a new processor at PAL master clock frequency with 3 INT outputs
     */
    public static Processor of(Processor source) {
        return of(source, YM2149_F_MASTER_PAL);
    }

    /**
     * Creates a {@link Ym2149Processor} with a configurable master clock frequency.
     * Use {@link #YM2149_F_MASTER_PAL} or {@link #YM2149_F_MASTER_NTSC} for
     * standard Atari ST clocks.
     *
     * @param source       processor with 15 outputs (14 INT registers + 1 BOOL write-enable)
     *                     at any source frequency lower than {@code masterClock}
     * @param masterClock  YM2149 master clock in Hz (e.g. {@link #YM2149_F_MASTER_PAL})
     * @return a new processor at {@code (long) masterClock} Hz with 3 INT outputs
     */
    public static Processor of(Processor source, double masterClock) {
        return new Ym2149Processor(new DefaultContext((long) masterClock), source);
    }

    public Signal.Type[] inType()  { return new Signal.Type[]{}; }

    public Signal.Type[] outType() { return OUT_TYPES.clone(); }

    public SignalArray apply(SignalArray in) {
        // Resample the 15-signal source up to YM_CLOCK Hz.
        // BOOL write-enable: true only at sample positions aligned with
        // the original source frame boundaries; false (zero-stuffed) elsewhere.
        return new SimulationState(context.resample(source).apply()).buildOutputArray(context);
    }

    /**
     * Holds the mutable {@link Ym2149SimulationState} and implements the
     * YM-file-specific register-write logic (all 14 registers at once).
     */
    private static final class SimulationState extends Ym2149SimulationState {

        SimulationState(SignalArray src) { super(src); }

        @Override
        protected void writeRegisters(long i) {
            if (src.at(14).boolAt(i)) {
                for (int r = 0; r < 14; r++) {
                    int srcVal = src.at(r).intAt(i);
                    // when R13=255 do not touch (and reset!) the envelope generator on that sample
                    // ym format special case
                    if (!(r == 13 && srcVal == 255)) {
                        chip.writeRegister(r, srcVal);
                    }
                }
            }
        }
    }
}
