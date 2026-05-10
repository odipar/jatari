package org.jatari.psg;

import org.jatari.ym.Ym2149Processor;
import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.BoolSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;

/**
 * A jaust {@link Processor} that replays a {@link PsgCaptureFile}.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>3 outputs</b> at the
 * YM2149 clock rate ({@value Ym2149Processor#YM_CLOCK} Hz):
 * <ul>
 *   <li>Signal 0: BOOL <em>write</em> — {@code true} when a register write
 *       is replayed at this clock cycle</li>
 *   <li>Signal 1: INT  <em>register number</em> (0–13)</li>
 *   <li>Signal 2: INT  <em>register value</em> (0–255)</li>
 * </ul>
 *
 * <h2>Clock mapping</h2>
 * <p>The capture records writes at Atari 8 MHz master-clock ticks.  The
 * YM2149 is clocked at 2 MHz (every 4th master-clock tick), so each 2 MHz
 * cycle {@code t} corresponds to the 8 MHz range {@code [t*4, t*4+4)}.
 * If multiple entries fall in the same 2 MHz cycle the last one wins.
 *
 * <h2>Looping</h2>
 * <p>The signal loops the capture when queried beyond the last recorded
 * entry.  The loop length is determined by {@link PsgCaptureFile#durationClocks()}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PsgCaptureFile cap = PsgCaptureParser.parse(Path.of("synergy_start.csv.zip"));
 * Processor psgProc  = PsgCaptureProcessor.of(cap);       // 3 signals @ 2 MHz
 * Processor ymProc   = Ym2149Processor.ofPsg(psgProc);    // 3 INT DAC indices @ 2 MHz
 * }</pre>
 */
public record PsgCaptureProcessor(Context context, PsgCaptureFile capture)
        implements DefaultProcessor {

    /** Atari master-clock frequency in Hz. */
    public static final long ATARI_CLOCK = 8_000_000L;

    /**
     * Ratio of Atari master-clock ticks to YM2149 clock cycles.
     * One YM2149 cycle spans {@value #ATARI_RATIO} master-clock ticks.
     */
    public static final long ATARI_RATIO = ATARI_CLOCK / Ym2149Processor.YM_CLOCK; // 4

    private static final Signal.Type[] OUT_TYPES =
            {Signal.Type.BOOL, Signal.Type.INT, Signal.Type.INT};

    /**
     * Creates a {@link PsgCaptureProcessor} for the given capture.
     *
     * @param capture the parsed PSG capture to replay
     * @return a zero-input, 3-output {@link Processor} at {@value Ym2149Processor#YM_CLOCK} Hz
     */
    public static Processor of(PsgCaptureFile capture) {
        return new PsgCaptureProcessor(new DefaultContext(Ym2149Processor.YM_CLOCK), capture);
    }

    @Override public Signal.Type[] inType()  { return new Signal.Type[]{}; }
    @Override public Signal.Type[] outType() { return OUT_TYPES.clone(); }

    @Override
    public SignalArray apply(SignalArray in) {
        // Total capture duration in 2 MHz cycles (loop length).
        // Round up so the last entry is always included in at least one cycle.
        final long loopTicks = (capture.durationClocks() + ATARI_RATIO - 1) / ATARI_RATIO;
        final long totalTicks = loopTicks > 0 ? loopTicks : 1;

        // Shared state: a single binary-search cache shared by all 3 signals.
        // The cache avoids redundant lookups when all three signals are queried
        // for the same time step (the common case in the simulation loop).
        final var state = new CaptureState(capture, totalTicks);

        BoolSignal writeSignal = new BoolSignal() {
            @Override public Context context() { return PsgCaptureProcessor.this.context; }
            @Override public boolean boolAt(long t) { return state.writeAt(t); }
        };
        IntSignal regSignal = new IntSignal() {
            @Override public Context context() { return PsgCaptureProcessor.this.context; }
            @Override public int intAt(long t)  { return state.regAt(t); }
        };
        IntSignal valueSignal = new IntSignal() {
            @Override public Context context() { return PsgCaptureProcessor.this.context; }
            @Override public int intAt(long t)  { return state.valueAt(t); }
        };

        return DefaultArray.a(writeSignal, regSignal, valueSignal);
    }

    // -----------------------------------------------------------------------
    // Shared lookup state
    // -----------------------------------------------------------------------

    /**
     * Caches the lookup result for the most recently queried time step,
     * avoiding three separate binary searches when all three output signals
     * are read at the same step.
     */
    private static final class CaptureState {

        private final PsgCaptureFile capture;
        private final long totalTicks;

        private long    lastT     = Long.MIN_VALUE;
        private boolean cachedWrite;
        private int     cachedReg;
        private int     cachedValue;

        CaptureState(PsgCaptureFile capture, long totalTicks) {
            this.capture    = capture;
            this.totalTicks = totalTicks;
        }

        private void computeAt(long t) {
            if (t == lastT) return; // cache hit

            // Map to the looping capture domain
            long tMod       = Math.floorMod(t, totalTicks);
            long clockStart = tMod * ATARI_RATIO;
            long clockEnd   = clockStart + ATARI_RATIO;

            int idx = findLastInRange(capture.clocks(), capture.numEntries(), clockStart, clockEnd);
            if (idx >= 0) {
                cachedWrite = true;
                cachedReg   = capture.regs()[idx];
                cachedValue = capture.values()[idx];
            } else {
                cachedWrite = false;
                cachedReg   = 0;
                cachedValue = 0;
            }
            lastT = t;
        }

        boolean writeAt(long t) { computeAt(t); return cachedWrite; }
        int     regAt(long t)   { computeAt(t); return cachedReg; }
        int     valueAt(long t) { computeAt(t); return cachedValue; }
    }

    // -----------------------------------------------------------------------
    // Binary search helper
    // -----------------------------------------------------------------------

    /**
     * Returns the index of the last entry whose {@code clock} value is in
     * the half-open interval {@code [start, end)}, or {@code -1} if no such
     * entry exists.
     */
    static int findLastInRange(long[] clocks, int n, long start, long end) {
        // Binary search for the first index with clock >= start.
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (clocks[mid] < start) lo = mid + 1;
            else hi = mid;
        }
        // lo is now the first index with clock >= start.
        if (lo >= n || clocks[lo] >= end) return -1;

        // Find the last index with clock < end.
        int first = lo;
        lo = first; hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (clocks[mid] < end) lo = mid + 1;
            else hi = mid;
        }
        // lo - 1 is the last index with clock < end.
        return lo - 1;
    }
}
