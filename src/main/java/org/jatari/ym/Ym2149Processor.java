package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;
import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

import java.util.Arrays;

/**
 * Creates a jaust {@link Processor} that wraps a cycle-accurate
 * {@link Ym2149AudioIndexed} simulation.
 *
 * <h2>Inputs</h2>
 * <p>The factory method {@link #of(Processor)} takes a 15-output source
 * processor, typically produced by {@link YmFileProcessor}:
 * <ul>
 *   <li>Signals 0–13: <b>INT</b> — YM2149 register values R0–R13.</li>
 *   <li>Signal 14:    <b>BOOL</b> — {@code isWriting} flag; {@code true}
 *       at each source frame boundary where registers should be written.</li>
 * </ul>
 * The source signals are captured (lazily evaluated) at the source
 * frequency (e.g.&nbsp;50 Hz for a PAL Atari ST YM file).
 *
 * <h2>Outputs</h2>
 * <p>Three <b>INT</b> signals at the simulation frequency
 * (default {@value #DEFAULT_SIMULATION_FREQ} Hz):
 * <ul>
 *   <li>Signal 0 — channel A 5-bit DAC index (0–31)</li>
 *   <li>Signal 1 — channel B 5-bit DAC index (0–31)</li>
 *   <li>Signal 2 — channel C 5-bit DAC index (0–31)</li>
 * </ul>
 *
 * <h2>Simulation model</h2>
 * <p>The processor up-samples the register inputs from the source frequency
 * to {@value #DEFAULT_SIMULATION_FREQ} Hz internally.  At each simulation
 * tick the {@link Ym2149AudioIndexed} chip is advanced by one clock cycle
 * ({@code risingEdge(true, false, true, false, false, 0)}).  At each source
 * frame boundary where {@code isWriting == true}, all 14 registers are
 * written into the chip before advancing the clock.
 *
 * <p><b>Sequential-access design:</b> the chip simulation is inherently
 * stateful and sequential.  The returned output signals cache their computed
 * values and automatically run the simulation forward when a later time is
 * queried.  Random-access queries at very large time values will trigger
 * simulation of all intermediate steps.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * YmFile ym = YmFileParser.parse(Path.of("music.ym"));
 * Processor regs  = YmFileProcessor.of(ym);          // 15 outputs @ 50 Hz
 * Processor audio = Ym2149Processor.of(regs);        // 3 outputs  @ 250 kHz
 *
 * var signals = audio.apply();
 * int chA = signals.at(0).intAt(0);  // channel A at sample 0
 * }</pre>
 */
public final class Ym2149Processor {

    /** Default simulation frequency (250 kHz = 2 MHz YM clock ÷ 8). */
    public static final long DEFAULT_SIMULATION_FREQ = 250_000L;

    private Ym2149Processor() {}

    /**
     * Builds a YM2149 simulation {@link Processor} driven by {@code input}.
     *
     * <p>Uses the default simulation frequency of
     * {@value #DEFAULT_SIMULATION_FREQ} Hz.
     *
     * @param input 15-output {@link Processor} at the source frame rate
     *              (signals 0–13: INT register values; signal 14: BOOL
     *              isWriting flag)
     * @return a zero-input, 3-output INT {@link Processor} at
     *         {@value #DEFAULT_SIMULATION_FREQ} Hz
     */
    public static Processor of(Processor input) {
        return of(input, DEFAULT_SIMULATION_FREQ);
    }

    /**
     * Builds a YM2149 simulation {@link Processor} driven by {@code input}
     * at a custom simulation frequency.
     *
     * @param input   15-output {@link Processor} at the source frame rate
     * @param simFreq simulation (output) frequency in Hz
     * @return a zero-input, 3-output INT {@link Processor} at {@code simFreq}
     * @throws IllegalArgumentException if {@code input} does not have exactly
     *                                  15 outputs (14 INT + 1 BOOL), or if
     *                                  {@code simFreq ≤ 0}
     */
    public static Processor of(Processor input, long simFreq) {
        Signal.Type[] types = input.outType();
        if (types.length != 15) {
            throw new IllegalArgumentException(
                    "input must have exactly 15 outputs; got %d".formatted(types.length));
        }
        if (simFreq <= 0) {
            throw new IllegalArgumentException(
                    "simFreq must be positive; got %d".formatted(simFreq));
        }

        long srcFreq = input.context().frequency();
        SignalArray srcSignals = input.apply();

        // GCD-reduced step: how many simulation ticks between source boundaries.
        long g      = gcd(srcFreq, simFreq);
        long period = simFreq / g; // dst ticks between consecutive src samples

        Simulation sim = new Simulation(srcSignals, srcFreq, simFreq, period);

        Context ctx = new DefaultContext(simFreq);
        return ctx.par(
                ctx.genI(t -> sim.get(0, t)),
                ctx.genI(t -> sim.get(1, t)),
                ctx.genI(t -> sim.get(2, t))
        );
    }

    // -----------------------------------------------------------------------
    // Internal sequential simulation with growing cache
    // -----------------------------------------------------------------------

    /**
     * Sequential YM2149 simulation that caches results in growing arrays.
     *
     * <p>Thread safety: {@link #get} is synchronised so that concurrent
     * jaust signal queries are safe, though typical usage is single-threaded.
     */
    private static final class Simulation {

        private static final int INITIAL_CAPACITY = 250_000; // ~1 s at 250 kHz

        private final Ym2149AudioIndexed chip = new Ym2149AudioIndexed();
        private final SignalArray input;
        private final long srcFreq;
        private final long dstFreq;
        private final long period; // dst ticks between src boundaries

        // Cached output arrays (grow on demand)
        private int[] chA = new int[INITIAL_CAPACITY];
        private int[] chB = new int[INITIAL_CAPACITY];
        private int[] chC = new int[INITIAL_CAPACITY];
        private int size  = 0; // next index to fill

        Simulation(SignalArray input, long srcFreq, long dstFreq, long period) {
            this.input   = input;
            this.srcFreq = srcFreq;
            this.dstFreq = dstFreq;
            this.period  = period;
            chip.applyReset();
        }

        /** Returns channel {@code ch} (0=A, 1=B, 2=C) output at dst time {@code t}. */
        synchronized int get(int ch, long t) {
            ensureTo(t);
            return switch (ch) {
                case 0 -> chA[(int) t];
                case 1 -> chB[(int) t];
                default -> chC[(int) t];
            };
        }

        /** Runs the simulation forward until {@code size > t}. */
        private void ensureTo(long t) {
            if (t < size) return;

            // Grow backing arrays if needed.
            if (t >= chA.length) {
                int newLen = (int) Math.max((long) chA.length * 2L, t + 1L);
                chA = Arrays.copyOf(chA, newLen);
                chB = Arrays.copyOf(chB, newLen);
                chC = Arrays.copyOf(chC, newLen);
            }

            while (size <= t) {
                long n = size;

                // At source-frame boundaries, write registers when isWriting.
                if (n % period == 0L) {
                    long srcTime = n * srcFreq / dstFreq;
                    if (input.at(14).boolAt(srcTime)) {
                        for (int r = 0; r < 14; r++) {
                            chip.writeRegister(r, input.at(r).intAt(srcTime));
                        }
                    }
                }

                // Advance simulation by one clock cycle.
                chip.risingEdge(true, false, true, false, false, 0);
                chA[size] = chip.getChAIndexO();
                chB[size] = chip.getChBIndexO();
                chC[size] = chip.getChCIndexO();
                size++;
            }
        }
    }

    private static long gcd(long a, long b) {
        return b == 0L ? a : gcd(b, a % b);
    }
}
