package org.jatari.psg;

import java.util.Map;

/**
 * Parsed contents of a Hatari PSG register-capture file.
 *
 * <p>The capture records individual YM2149 register writes that occurred during
 * Atari ST emulation.  The 8 MHz Atari clock values from the CSV are normalised
 * to start at zero and converted to 2 MHz YM-clock ticks (÷4) so that they
 * align directly with {@link PsgYm2149Processor}'s time domain.
 *
 * <h2>Lookup</h2>
 * <p>Each 2 MHz tick has at most one register write (confirmed by the minimum
 * observed inter-write gap of 28 Atari cycles = 7 YM ticks).  A {@link Map}
 * keyed by YM tick provides O(1) lookup in the signal generators.
 */
public final class PsgCapture {

    /** 2 MHz YM-clock rate used throughout the PSG pipeline. */
    static final long YM_CLOCK = 2_000_000L;

    private final Map<Long, int[]> writes;   // ymTick → [reg, value]
    private final long durationYmTicks;
    private final String fileName;

    PsgCapture(Map<Long, int[]> writes, long durationYmTicks, String fileName) {
        this.writes          = writes;
        this.durationYmTicks = durationYmTicks;
        this.fileName        = fileName;
    }

    /** Returns {@code true} if a register write occurred at YM tick {@code t}. */
    public boolean hasWriteAt(long t) {
        return writes.containsKey(t);
    }

    /** Returns the register number written at YM tick {@code t}, or 0 if none. */
    public int regAt(long t) {
        int[] w = writes.get(t);
        return w != null ? w[0] : 0;
    }

    /** Returns the register value written at YM tick {@code t}, or 0 if none. */
    public int valueAt(long t) {
        int[] w = writes.get(t);
        return w != null ? w[1] : 0;
    }

    /** Total playback length in 2 MHz YM-clock ticks. */
    public long durationYmTicks() { return durationYmTicks; }

    /** Total playback duration in seconds. */
    public double durationSeconds() { return (double) durationYmTicks / YM_CLOCK; }

    /** Original file name (for display in the UI). */
    public String fileName() { return fileName; }
}
