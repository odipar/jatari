package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.psg.PsgCapture;
import org.jatari.psg.PsgCaptureParser;
import org.jatari.psg.PsgCaptureProcessor;
import org.jatari.psg.PsgYm2149Processor;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Plays PSG register-capture files ({@code *.csv.zip}) at 44.1 kHz using a
 * cycle-accurate YM2149 simulation.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * PsgCaptureProcessor (2 MHz, 3 signals: BOOL write / INT reg / INT value)
 *   → PsgYm2149Processor (2 MHz, 3 INT signals: chA / chB / chC)
 *     → YmMixer (2 MHz, 1 INT signal: mixed)
 *       → [optional] LowPassFilter  (2 MHz, IIR, 1 INT)
 *         → [optional] HighPassFilter (2 MHz, IIR, 1 INT)
 *           → box-filter downsample → 44 100 Hz INT signal
 *             → javax.sound.sampled SourceDataLine (16-bit LE mono signed PCM)
 * </pre>
 *
 * <p>Playback runs on a background daemon thread.  Call {@link #play(Path)} to
 * start and {@link #stop()} to end it.  The capture plays once and then stops.
 *
 * <h2>WAV export</h2>
 * <p>Use {@link #exportWav(PsgCapture, Path)} to render the capture to a
 * 16-bit mono 44 100 Hz WAV file.
 */
public class PsgPlayer extends AbstractPlayer<PsgCapture> {

    // -----------------------------------------------------------------------
    // AbstractPlayer extension points
    // -----------------------------------------------------------------------

    @Override
    protected PsgCapture parse(Path path) throws IOException {
        return PsgCaptureParser.parse(path);
    }

    @Override
    protected String threadName() { return "psg-player"; }

    @Override
    protected void runPlayback(PsgCapture capture) throws LineUnavailableException {
        Signal outputSignal  = buildOutputSignal(capture,
                () -> lpfOption.cutoffHz, () -> hpfOption.cutoffHz);
        runAudioLine(outputSignal, totalSamples(capture), capture.durationSeconds(), false);
    }

    // -----------------------------------------------------------------------
    // Pipeline builder
    // -----------------------------------------------------------------------

    /**
     * Builds the full output signal chain for a given PSG capture.
     *
     * <p>Pipeline:
     * <pre>
     *   PsgCaptureProcessor → PsgYm2149Processor → YmMixer
     *     → [LowPassFilter] → [HighPassFilter] → box-filter downsample
     * </pre>
     *
     * @return a {@link Signal} at {@value SAMPLE_RATE} Hz (INT, 16-bit range)
     */
    @Override
    protected Signal buildOutputSignal(PsgCapture capture, IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {
        Processor   captureProc = PsgCaptureProcessor.of(capture);
        Processor   ymProc      = PsgYm2149Processor.of(captureProc);
        SignalArray ymOut       = ymProc.apply();

        DefaultContext ctx2m  = new DefaultContext(PsgYm2149Processor.YM_CLOCK);
        YmMixer        mixer  = new YmMixer(ctx2m);
        Signal         mixSig = mixer.apply(ymOut).at(0);

        return buildFilterChain(mixSig, ctx2m, PsgYm2149Processor.YM_CLOCK, lpfCutoffHz, hpfCutoffHz);
    }

    @Override
    protected long totalSamples(PsgCapture capture) {
        return (long) ((double) capture.durationYmTicks() * SAMPLE_RATE
                / PsgYm2149Processor.YM_CLOCK);
    }
}
