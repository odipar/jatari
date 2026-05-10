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
import java.nio.file.Files;
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
 *           → box-filter downsample → 44 100 Hz INT signal
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
        long   samplesPerLoop  = (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
        double durationSeconds = (double) ym.numFrames() / ym.frameRate();
        runAudioLine(outputSignal, samplesPerLoop, durationSeconds, true);
    }

    // -----------------------------------------------------------------------
    // WAV export
    // -----------------------------------------------------------------------

    /**
     * Renders a full song loop to a 16-bit mono 44 100 Hz WAV file using the
     * current filter settings.  Blocks until rendering is complete.
     *
     * @param ym      parsed YM file to render
     * @param wavPath destination WAV file path (created or overwritten)
     * @throws IOException on file I/O errors
     */
    public void exportWav(YmFile ym, Path wavPath) throws IOException {
        int lpfHz = lpfOption.cutoffHz;
        int hpfHz = hpfOption.cutoffHz;
        exportWav(ym, wavPath, () -> lpfHz, () -> hpfHz);
    }

    /**
     * Renders a full song loop to a 16-bit mono 44 100 Hz WAV file using the
     * given filter options.
     *
     * @param ym      parsed YM file to render
     * @param wavPath destination WAV file path (created or overwritten)
     * @param lpfOpt  low-pass filter option
     * @param hpfOpt  high-pass filter option
     * @throws IOException on file I/O errors
     */
    public void exportWav(YmFile ym, Path wavPath, LpfOption lpfOpt, HpfOption hpfOpt)
            throws IOException {
        exportWav(ym, wavPath, () -> lpfOpt.cutoffHz, () -> hpfOpt.cutoffHz);
    }

    private void exportWav(YmFile ym, Path wavPath,
                           IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz)
            throws IOException {
        Signal outputSignal = buildOutputSignal(ym, lpfCutoffHz, hpfCutoffHz);
        long   totalSamples = (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
        try (var out = Files.newOutputStream(wavPath)) {
            writeWavHeader(out, totalSamples * 2L);
            writeWavData(outputSignal, totalSamples, out);
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline builder
    // -----------------------------------------------------------------------

    /**
     * Builds the full output signal chain for a given YM file.
     *
     * <p>Pipeline:
     * <pre>
     *   YmFileProcessor → Ym2149Processor → YmMixer
     *     → [LowPassFilter] → [HighPassFilter] → box-filter downsample
     * </pre>
     *
     * @return a {@link Signal} at {@value SAMPLE_RATE} Hz (INT, 16-bit range)
     */
    /* package-private for testability */
    Signal buildOutputSignal(YmFile ym, IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {
        Processor   fileProc  = YmFileProcessor.of(ym);
        Processor   ymProc    = Ym2149Processor.of(fileProc);
        SignalArray ymOut     = ymProc.apply();

        DefaultContext ctx2m  = new DefaultContext(Ym2149Processor.YM_CLOCK);
        YmMixer        mixer  = new YmMixer(ctx2m);
        Signal         mixSig = mixer.apply(ymOut).at(0);

        return buildFilterChain(mixSig, ctx2m, Ym2149Processor.YM_CLOCK, lpfCutoffHz, hpfCutoffHz);
    }
}
