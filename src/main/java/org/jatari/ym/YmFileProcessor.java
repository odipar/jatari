package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.context.DefaultContext;

/**
 * Creates a jaust {@link Processor} that replays a {@link YmFile}.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>14 INT outputs</b>, one for
 * each YM2149 register R0–R13 in order.  At sample time {@code t} (measured
 * in units of {@code 1/frameRate} seconds) each output signal yields the
 * value of the corresponding register in frame {@code t mod numFrames}.
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
 * }</pre>
 */
public final class YmFileProcessor {

    private YmFileProcessor() {}

    /**
     * Builds a jaust {@link Processor} for the given {@link YmFile}.
     *
     * @param ymFile the parsed YM file to replay
     * @return a zero-input, 14-output INT {@link Processor}
     */
    public static Processor of(YmFile ymFile) {
        Context ctx  = new DefaultContext(ymFile.frameRate());
        int numFrames = ymFile.numFrames();

        // One genI processor per register (R0 .. R13)
        Processor[] regs = new Processor[14];
        for (int r = 0; r < 14; r++) {
            final int reg = r;
            regs[r] = ctx.genI(time -> ymFile.registerAt((int) (time % numFrames), reg));
        }

        // Combine all 14 register processors in parallel (Faust-style ',')
        return ctx.par(regs);
    }
}
