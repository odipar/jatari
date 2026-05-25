package org.jatari.ym;

import org.jaust.Context;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

/**
 * Shared mutable simulation state for YM2149-based processors
 * ({@link Ym2149Processor} and {@link org.jatari.psg.PsgYm2149Processor}).
 *
 * <p>Subclasses implement {@link #writeRegisters(long)} to supply the
 * format-specific register-write logic; the common chip-clock loop,
 * output latching, and channel accessors are provided here.
 */
public abstract class Ym2149SimulationState {

    /** Source signal array consumed by {@link #writeRegisters(long)}. */
    protected final SignalArray       src;

    /** Cycle-accurate YM2149 chip instance. */
    protected final Ym2149AudioIndexed chip = new Ym2149AudioIndexed(0, 0);

    private long lastTime = -1;
    private int  chA = 0, chB = 0, chC = 0;

    /**
     * Initialises the state from {@code src} and applies the chip reset.
     *
     * @param src source signal array; interpretation is format-specific
     */
    protected Ym2149SimulationState(SignalArray src) {
        this.src = src;
        chip.applyReset();
    }

    /**
     * Writes the appropriate register(s) to the chip at sample time {@code i}.
     * Called once per clock cycle, immediately before the rising-edge advance.
     *
     * @param i current sample time (within the {@link #advanceTo} loop)
     */
    protected abstract void writeRegisters(long i);

    /** Advances the simulation from the last processed sample up to {@code t} (inclusive). */
    private void advanceTo(long t) {
        for (long i = lastTime + 1; i <= t; i++) {
            writeRegisters(i);
            chip.risingEdge(true, true, true, false, false, 0);
            chA = chip.getChAIndexO();
            chB = chip.getChBIndexO();
            chC = chip.getChCIndexO();
            lastTime = i;
        }
    }

    /** Returns the channel A DAC index at sample time {@code t}. */
    public int getChA(long t) { advanceTo(t); return chA; }

    /** Returns the channel B DAC index at sample time {@code t}. */
    public int getChB(long t) { advanceTo(t); return chB; }

    /** Returns the channel C DAC index at sample time {@code t}. */
    public int getChC(long t) { advanceTo(t); return chC; }

    /**
     * Builds a three-element INT {@link SignalArray} (channels A, B, C) backed
     * by this simulation state and the supplied context.
     *
     * @param ctx jaust context at the YM2149 clock frequency
     * @return a {@link SignalArray} with 3 INT signals
     */
    public SignalArray buildOutputArray(Context ctx) {
        IntSignal chA = new IntSignal() {
            public Context context() { return ctx; }
            public int intAt(long t) { return Ym2149SimulationState.this.getChA(t); }
        };
        IntSignal chB = new IntSignal() {
            public Context context() { return ctx; }
            public int intAt(long t) { return Ym2149SimulationState.this.getChB(t); }
        };
        IntSignal chC = new IntSignal() {
            public Context context() { return ctx; }
            public int intAt(long t) { return Ym2149SimulationState.this.getChC(t); }
        };
        return DefaultArray.a(chA, chB, chC);
    }
}
