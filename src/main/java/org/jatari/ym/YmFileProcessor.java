package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.context.DefaultContext;

/**
 * Creates a jaust {@link Processor} that replays a {@link YmFile}.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>15 outputs</b>:
 * <ul>
 *   <li>Signals 0–13: <b>INT</b>, one for each YM2149 register R0–R13 in
 *       order.  At sample time {@code t} each output yields the value of the
 *       corresponding register in frame {@code t mod numFrames}.</li>
 *   <li>Signal 14: <b>BOOL</b>, the <em>isWriting</em> flag.  At the frame
 *       rate (e.g.&nbsp;50 Hz) this signal is always {@code true}, indicating
 *       that a new set of register values is being written every frame.  When
 *       this processor is later upsampled to the YM2149 simulation frequency
 *       (e.g.&nbsp;250 kHz), the flag becomes {@code true} only at the exact
 *       sample instants that correspond to a 50 Hz boundary, and
 *       {@code false} for all other samples.  This lets the YM2149 processor
 *       know precisely when to write registers into the chip simulation.</li>
 * </ul>
 *
 * <h2>Context frequency</h2>
 * <p>The {@link Context} frequency is set to the YM file's playback frame
 * rate (typically 50 Hz for PAL Atari ST tunes, but may differ for other
 * platforms or custom tunes).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * YmFile ym = YmFileParser.parse(Path.of("music.ym"));
 * Processor proc = YmFileProcessor.of(ym);
 *
 * // Query register 0 at frame 100:
 * int r0 = proc.apply().at(0).intAt(100);
 *
 * // Query the isWriting flag at frame 7 (always true at frame rate):
 * boolean w = proc.apply().at(14).boolAt(7);
 * }</pre>
 */
public final class YmFileProcessor {

    private YmFileProcessor() {}

    /**
     * Builds a jaust {@link Processor} for the given {@link YmFile}.
     *
     * @param ymFile the parsed YM file to replay
     * @return a zero-input, 15-output {@link Processor}
     *         (14 INT register signals + 1 BOOL isWriting signal)
     */
    public static Processor of(YmFile ymFile) {
        Context ctx  = new DefaultContext(ymFile.frameRate());
        int numFrames = ymFile.numFrames();

        // Signals 0-13: one genI processor per register (R0 .. R13)
        Processor[] procs = new Processor[15];
        for (int r = 0; r < 14; r++) {
            final int reg = r;
            procs[r] = ctx.genI(time -> ymFile.registerAt((int) (time % numFrames), reg));
        }

        // Signal 14: isWriting flag — always true at the frame rate.
        // When upsampled to a higher frequency (e.g. 250 kHz for the YM2149),
        // the Upsampler's BOOL impulse logic turns this into a pulse that is
        // true only at the frame boundaries.
        procs[14] = ctx.genB(time -> true);

        // Combine all 15 processors in parallel (Faust-style ',')
        return ctx.par(procs);
    }
}
