package org.jatari.ym.format;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.context.DefaultContext;

/**
 * Creates a jaust {@link Processor} that replays a {@link YmFile}.
 *
 * <h2>Output signals</h2>
 * <p>The processor has <b>zero inputs</b> and <b>15 outputs</b>:
 * <ul>
 *   <li>Signals 0–13: INT values for YM2149 registers R0–R13</li>
 *   <li>Signal 14: BOOL write-enable — always {@code true} at the source
 *       frame rate, indicating that the registers are being written at every
 *       frame.  When this processor is upsampled to a higher rate (e.g. the
 *       250 kHz YM2149 clock) the write-enable is {@code false} for all
 *       interpolated samples and {@code true} only at the original frame
 *       boundaries.</li>
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
 * // Write-enable is always true in the source domain:
 * boolean we = proc.apply().at(14).boolAt(100); // true
 * }</pre>
 */
public final class YmFileProcessor {

    private YmFileProcessor() {}

    /**
     * Builds a jaust {@link Processor} for the given {@link YmFile}.
     *
     * @param ymFile the parsed YM file to replay
     * @return a zero-input, 15-output {@link Processor} (14 INT registers + 1 BOOL write-enable)
     */
    public static Processor of(YmFile ymFile) {
        Context ctx  = new DefaultContext(ymFile.frameRate());
        int numFrames = ymFile.numFrames();

        // One genI processor per register (R0 .. R13) + one genB write-enable
        Processor[] procs = new Processor[15];
        for (int r = 0; r < 14; r++) {
            final int reg = r;
            procs[r] = ctx.genI(time -> ymFile.registerAt((int) (time % numFrames), reg));
        }
        // Write-enable: always true in the source (50 Hz) domain.
        // When upsampled, only the aligned sample positions carry true.
        procs[14] = ctx.genB(time -> true);

        // Combine all 15 processors in parallel (Faust-style ',')
        return ctx.par(procs);
    }
}
