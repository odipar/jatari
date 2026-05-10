package org.jatari.psg;

import org.jatari.ym.Ym2149Processor;
import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.context.DefaultContext;

/**
 * Creates a jaust {@link Processor} that replays a {@link PsgCapture}.
 *
 * <h2>Output signals (3)</h2>
 * <ul>
 *   <li>Signal 0: BOOL write-enable — {@code true} when a register write
 *       occurs at this 2 MHz YM-clock tick.</li>
 *   <li>Signal 1: INT register number (0–13; valid when write-enable is
 *       {@code true}).</li>
 *   <li>Signal 2: INT register value (0–255; valid when write-enable is
 *       {@code true}).</li>
 * </ul>
 *
 * <h2>Context frequency</h2>
 * <p>The {@link Context} runs at {@link Ym2149Processor#YM_CLOCK} Hz (2 MHz),
 * matching the YM2149 chip clock.  Feed the output directly into
 * {@link PsgYm2149Processor}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PsgCapture cap = PsgCaptureParser.parse(Path.of("capture.csv.zip"));
 * Processor proc = PsgCaptureProcessor.of(cap);
 *
 * SignalArray out = proc.apply();
 * boolean we  = out.at(0).boolAt(1000);  // write-enable at YM tick 1000
 * int     reg = out.at(1).intAt(1000);   // register number
 * int     val = out.at(2).intAt(1000);   // register value
 * }</pre>
 */
public final class PsgCaptureProcessor {

    private PsgCaptureProcessor() {}

    /**
     * Builds a zero-input, 3-output jaust {@link Processor} for the given capture.
     *
     * @param capture the parsed PSG capture to replay
     * @return a Processor with signals: BOOL write, INT reg, INT value at 2 MHz
     */
    public static Processor of(PsgCapture capture) {
        Context ctx = new DefaultContext(Ym2149Processor.YM_CLOCK);

        Processor write = ctx.genB(t -> capture.hasWriteAt(t));
        Processor reg   = ctx.genI(t -> capture.regAt(t));
        Processor value = ctx.genI(t -> capture.valueAt(t));

        return ctx.par(write, reg, value);
    }
}
