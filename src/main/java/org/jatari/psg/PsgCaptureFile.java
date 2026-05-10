package org.jatari.psg;

/**
 * Holds the data from a Hatari PSG register-capture file.
 *
 * <p>Each entry records a single YM2149 register write that occurred at a
 * given Atari 8 MHz master-clock tick during program execution.  The arrays
 * are sorted in ascending clock order and have the same length.
 *
 * <h2>Clock relationship</h2>
 * <p>The Atari ST master clock runs at 8 MHz.  The YM2149 is driven at
 * 2 MHz (every 4th master-clock tick).  Use {@link #durationClocks()} to
 * obtain the total capture length in 8 MHz ticks; divide by 4 to convert
 * to YM2149 clock cycles.
 */
public record PsgCaptureFile(long[] clocks, int[] regs, int[] values) {

    /** Number of register-write entries in this capture. */
    public int numEntries() {
        return clocks.length;
    }

    /**
     * Total capture duration in Atari 8 MHz clock ticks.
     * Defined as the clock value of the last entry plus one, or 0 for an
     * empty capture.
     */
    public long durationClocks() {
        return numEntries() > 0 ? clocks[numEntries() - 1] + 1 : 0;
    }
}
