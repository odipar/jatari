package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.ym.Ym2149Processor;
import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;
import org.jatari.ym.format.YmFileProcessor;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Plays YM files at 44.1 kHz using a cycle-accurate YM2149 simulation.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * YmFileProcessor (frameRate Hz, 15 signals)
 *   → Ym2149Processor (2 MHz, 3 INT signals: chA / chB / chC)
 *     → YmMixer (2 MHz, 1 INT signal: mixed)
 *       → [optional] LowPassFilter  (2 MHz, IIR, 1 INT)
 *         → [optional] HighPassFilter (2 MHz, IIR, 1 INT)
 *           → resample (box-filter) → 44 100 Hz INT signal
 *             → javax.sound.sampled SourceDataLine (16-bit LE mono signed PCM)
 * </pre>
 *
 * <p>Playback runs on a background daemon thread.  Call {@link #play(Path)} to
 * start and {@link #stop()} to end playback.  Filter settings can be changed
 * in real-time via {@link #setLpfOption} / {@link #setHpfOption}.
 *
 * <h2>WAV export</h2>
 * <p>Use {@link #exportWav(YmFile, Path)} to render a full song (one loop) to
 * a 16-bit mono 44 100 Hz WAV file.
 */
public class YmPlayer extends AbstractPlayer<YmFile> {

    // -----------------------------------------------------------------------
    // AbstractPlayer extension points
    // -----------------------------------------------------------------------

    @Override
    protected YmFile parse(Path path) throws IOException {
        return YmFileParser.parse(path);
    }

    @Override
    protected String threadName() { return "ym-player"; }

    @Override
    protected void runPlayback(YmFile ym) throws LineUnavailableException {
        Signal outputSignal  = buildOutputSignal(ym,
                () -> lpfOption.cutoffHz, () -> hpfOption.cutoffHz);
        double durationSeconds = (double) ym.numFrames() / ym.frameRate();
        runAudioLine(outputSignal, totalSamples(ym), durationSeconds, true);
    }

    // -----------------------------------------------------------------------
    // Pipeline builder
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Pipeline:
     * <pre>
     *   YmFileProcessor → Ym2149Processor → YmMixer
     *     → [LowPassFilter] → [HighPassFilter] → resample (box-filter) downsample
     * </pre>
     */
    @Override
    protected Signal buildOutputSignal(YmFile ym, IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {
        Processor   fileProc  = YmFileProcessor.of(ym);
        Processor   ymProc    = Ym2149Processor.of(fileProc);
        SignalArray ymOut     = ymProc.apply();

        DefaultContext ctx2m  = new DefaultContext(Ym2149Processor.YM_CLOCK);
        YmMixer mixer  = new YmMixer(ctx2m);
        Signal         mixSig = mixer.apply(ymOut).at(0);

        return buildFilterChain(mixSig, ctx2m, lpfCutoffHz, hpfCutoffHz);
    }

    @Override
    protected long totalSamples(YmFile ym) {
        return (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
    }
}
