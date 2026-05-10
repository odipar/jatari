package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.dsp.HighPassFilter;
import org.jatari.dsp.LowPassFilter;
import org.jatari.psg.PsgCapture;
import org.jatari.psg.PsgCaptureParser;
import org.jatari.psg.PsgCaptureProcessor;
import org.jatari.psg.PsgYm2149Processor;
import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
 * start and {@link #stop()} to end it.  A {@link Listener} can be registered
 * for progress and stop events.  The capture plays once and then stops.
 *
 * <h2>Filters</h2>
 * <p>Optional IIR low-pass and high-pass filters operate at 2 MHz before the
 * box-filter downsample.  Select cutoffs with
 * {@link #setLpfOption(YmPlayer.LpfOption)} / {@link #setHpfOption(YmPlayer.HpfOption)}.
 * Changes take effect immediately without restarting playback.
 *
 * <h2>WAV export</h2>
 * <p>Use {@link #exportWav(PsgCapture, Path)} to render the capture to a
 * 16-bit mono 44 100 Hz WAV file.
 */
public class PsgPlayer {

    /** Output sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;

    /** Number of 16-bit output samples per audio-buffer write. */
    private static final int BUFFER_SAMPLES = 2048;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private volatile boolean         playing       = false;
    private volatile boolean         stopRequested = false;
    private volatile long            positionSamples = 0;
    private volatile Thread          playerThread;
    private volatile SourceDataLine  audioLine;
    private volatile YmPlayer.LpfOption lpfOption = YmPlayer.LpfOption.OFF;
    private volatile YmPlayer.HpfOption hpfOption = YmPlayer.HpfOption.OFF;
    private Listener listener;

    // -----------------------------------------------------------------------
    // Listener
    // -----------------------------------------------------------------------

    /** Receives playback events from the player thread. */
    public interface Listener {
        /**
         * Called approximately every 100 ms during playback.
         *
         * @param positionSeconds current position in seconds
         * @param durationSeconds total capture duration in seconds
         */
        void onProgress(double positionSeconds, double durationSeconds);

        /** Called when playback stops (capture finished or {@link #stop()} called). */
        void onStopped();
    }

    /** Registers a listener for progress / stopped callbacks. */
    public void setListener(Listener listener) { this.listener = listener; }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public YmPlayer.LpfOption getLpfOption() { return lpfOption; }

    public void setLpfOption(YmPlayer.LpfOption option) { this.lpfOption = option; }

    public YmPlayer.HpfOption getHpfOption() { return hpfOption; }

    public void setHpfOption(YmPlayer.HpfOption option) { this.hpfOption = option; }

    // -----------------------------------------------------------------------
    // Playback control
    // -----------------------------------------------------------------------

    /**
     * Parses the PSG capture at {@code path} and starts playback on a
     * background thread.  Any running playback is stopped first.
     *
     * @param path path to a {@code *.csv.zip} capture file
     * @throws IOException if the file cannot be read or contains no events
     */
    public synchronized void play(Path path) throws IOException {
        stop();
        PsgCapture capture = PsgCaptureParser.parse(path);
        playerThread = new Thread(() -> {
            try {
                runPlayback(capture);
            } catch (LineUnavailableException e) {
                System.err.println("PSG audio line unavailable: " + e.getMessage());
            }
        }, "psg-player");
        playerThread.setDaemon(true);
        playerThread.start();
    }

    /**
     * Stops playback and waits (up to 2 s) for the background thread to finish.
     */
    public synchronized void stop() {
        stopRequested = true;
        SourceDataLine l = this.audioLine;
        if (l != null) { l.stop(); l.flush(); }
        Thread t = playerThread;
        if (t != null && t.isAlive()) {
            try { t.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        playing = false;
    }

    /** Returns {@code true} if playback is currently running. */
    public boolean isPlaying() { return playing; }

    /**
     * Returns the current playback position in seconds (updated every buffer
     * write, approximately every 46 ms at 44 100 Hz / 2048 samples).
     */
    public double getPositionSeconds() { return positionSamples / (double) SAMPLE_RATE; }

    // -----------------------------------------------------------------------
    // WAV export
    // -----------------------------------------------------------------------

    /**
     * Renders the capture to a 16-bit mono 44 100 Hz WAV file using the
     * current filter settings.  Blocks until rendering is complete.
     *
     * @param capture parsed PSG capture to render
     * @param wavPath destination WAV file path (created or overwritten)
     * @throws IOException on file I/O errors
     */
    public void exportWav(PsgCapture capture, Path wavPath) throws IOException {
        int lpfHz = lpfOption.cutoffHz;
        int hpfHz = hpfOption.cutoffHz;
        exportWav(capture, wavPath, () -> lpfHz, () -> hpfHz);
    }

    private void exportWav(PsgCapture capture, Path wavPath,
                           IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz)
            throws IOException {
        Signal outputSignal  = buildOutputSignal(capture, lpfCutoffHz, hpfCutoffHz);
        long   totalSamples  = totalSamples(capture);
        long   dataBytes     = totalSamples * 2L;

        try (OutputStream out = Files.newOutputStream(wavPath)) {
            writeWavHeader(out, dataBytes);

            byte[] buf    = new byte[BUFFER_SAMPLES * 2];
            int    bufIdx = 0;

            for (long t = 0; t < totalSamples; t++) {
                int sample = outputSignal.intAt(t);
                buf[bufIdx++] = (byte)  (sample       & 0xFF);
                buf[bufIdx++] = (byte) ((sample >> 8) & 0xFF);
                if (bufIdx >= buf.length) { out.write(buf, 0, bufIdx); bufIdx = 0; }
            }
            if (bufIdx > 0) out.write(buf, 0, bufIdx);
        }
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
    /* package-private for testability */
    Signal buildOutputSignal(PsgCapture capture, IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {
        // ---- PSG → YM2149 → mixer at 2 MHz ---------------------------------
        Processor   captureProc = PsgCaptureProcessor.of(capture);
        Processor   ymProc      = PsgYm2149Processor.of(captureProc);
        SignalArray ymOut       = ymProc.apply();

        DefaultContext ctx2m  = new DefaultContext(PsgYm2149Processor.YM_CLOCK);
        YmMixer        mixer  = new YmMixer(ctx2m);
        Signal         mixSig = mixer.apply(ymOut).at(0);

        // ---- Optional IIR low-pass filter at 2 MHz --------------------------
        DoubleSignal lpfCutoff = new DoubleSignal() {
            public Context context()         { return ctx2m; }
            public double  doubleAt(long t)  { return lpfCutoffHz.getAsInt(); }
        };
        LowPassFilter lpf    = new LowPassFilter(ctx2m);
        Signal        lpfSig = lpf.apply(DefaultArray.a(mixSig, lpfCutoff)).at(0);

        // ---- Optional IIR high-pass filter at 2 MHz -------------------------
        DoubleSignal hpfCutoff = new DoubleSignal() {
            public Context context()        { return ctx2m; }
            public double  doubleAt(long t) { return hpfCutoffHz.getAsInt(); }
        };
        HighPassFilter hpf    = new HighPassFilter(ctx2m);
        Signal         hpfSig = hpf.apply(DefaultArray.a(lpfSig, hpfCutoff)).at(0);

        // ---- Box-filter downsample to 44 100 Hz -----------------------------
        final long   ymClock       = PsgYm2149Processor.YM_CLOCK;
        DefaultContext ctx44k      = new DefaultContext(SAMPLE_RATE);
        final Signal   filteredSig = hpfSig;

        return new IntSignal() {
            public Context context() { return ctx44k; }
            public int intAt(long t) {
                long tymStart = t * ymClock / SAMPLE_RATE;
                long tymEnd   = (t + 1) * ymClock / SAMPLE_RATE;
                long sum = 0, count = tymEnd - tymStart;
                for (long tym = tymStart; tym < tymEnd; tym++) {
                    sum += filteredSig.intAt(tym);
                }
                return (count > 0) ? (int) (sum / count) : 0;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Internal playback loop
    // -----------------------------------------------------------------------

    private void runPlayback(PsgCapture capture) throws LineUnavailableException {
        Signal outputSignal = buildOutputSignal(capture,
                () -> lpfOption.cutoffHz,
                () -> hpfOption.cutoffHz);

        AudioFormat    format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info  info   = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            this.audioLine = line;
            line.open(format, BUFFER_SAMPLES * 2 * 2);
            line.start();

            final long   totalSamples   = totalSamples(capture);
            final double durationSeconds = capture.durationSeconds();

            byte[] buffer = new byte[BUFFER_SAMPLES * 2];
            int    bufIdx = 0;

            stopRequested = false;
            playing       = true;
            positionSamples = 0;

            final long notifyEvery = SAMPLE_RATE / 10; // ~10 progress events/sec

            long t44k = 0;
            while (!stopRequested && t44k < totalSamples) {
                int sample = outputSignal.intAt(t44k);

                buffer[bufIdx++] = (byte)  (sample       & 0xFF);
                buffer[bufIdx++] = (byte) ((sample >> 8) & 0xFF);

                if (bufIdx >= buffer.length) {
                    line.write(buffer, 0, bufIdx);
                    bufIdx = 0;
                }

                positionSamples = t44k;
                t44k++;

                if (listener != null && (t44k % notifyEvery) == 0) {
                    final double pos = positionSamples / (double) SAMPLE_RATE;
                    listener.onProgress(pos, durationSeconds);
                }
            }

            if (bufIdx > 0) line.write(buffer, 0, bufIdx);
            line.drain();
            line.stop();
        }

        playing        = false;
        this.audioLine = null;
        if (listener != null) listener.onStopped();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static long totalSamples(PsgCapture capture) {
        return (long) ((double) capture.durationYmTicks() * SAMPLE_RATE
                / PsgYm2149Processor.YM_CLOCK);
    }

    /**
     * Writes a standard 44-byte PCM WAV header for 16-bit mono 44 100 Hz audio.
     */
    private static void writeWavHeader(OutputStream out, long dataBytes) throws IOException {
        long riffSize    = Math.min(dataBytes + 36L, 0xFFFFFFFFL);
        long clampedData = Math.min(dataBytes,        0xFFFFFFFFL);

        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        hdr.putInt((int) riffSize);
        hdr.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        hdr.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        hdr.putInt(16);
        hdr.putShort((short) 1);            // PCM
        hdr.putShort((short) 1);            // mono
        hdr.putInt(SAMPLE_RATE);
        hdr.putInt(SAMPLE_RATE * 2);        // byte rate
        hdr.putShort((short) 2);            // block align
        hdr.putShort((short) 16);           // bits per sample
        hdr.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        hdr.putInt((int) clampedData);
        out.write(hdr.array());
    }
}
