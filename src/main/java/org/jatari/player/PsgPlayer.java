package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.dsp.HighPassFilter;
import org.jatari.dsp.LowPassFilter;
import org.jatari.psg.PsgCaptureFile;
import org.jatari.psg.PsgCaptureParser;
import org.jatari.psg.PsgCaptureProcessor;
import org.jatari.ym.Ym2149Processor;
import org.jaut.Context;
import org.jaut.Processor;
import org.jaut.Signal;
import org.jaut.context.DefaultContext;
import org.jaut.signal.DoubleSignal;
import org.jaut.signal.IntSignal;
import org.jaut.signal.SignalArray;
import org.jaut.signal.array.DefaultArray;

import java.util.function.IntSupplier;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plays PSG register-capture files at 44.1 kHz using a cycle-accurate
 * YM2149 simulation.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * PsgCaptureProcessor (2 MHz, 3 signals: BOOL write / INT reg / INT value)
 *   → Ym2149Processor PSG mode (2 MHz, 3 INT signals: chA / chB / chC)
 *     → YmMixer (2 MHz, 1 INT signal: mixed)
 *       → [optional] LowPassFilter  (2 MHz, IIR, 1 INT)
 *         → [optional] HighPassFilter (2 MHz, IIR, 1 INT)
 *           → box-filter downsample → 44100 Hz downsampled INT signal
 *             → javax.sound.sampled SourceDataLine (16-bit LE mono signed PCM)
 * </pre>
 *
 * <p>Playback runs on a background daemon thread.  Call {@link #play(Path)} to
 * start and {@link #stop()} to end playback.  A {@link Listener} can be
 * registered to receive progress and stop events for UI updates.
 */
public class PsgPlayer {

    /** Output sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;

    /** Number of 16-bit output samples per audio-buffer write. */
    private static final int BUFFER_SAMPLES = 2048;

    // -----------------------------------------------------------------------
    // Filter options  (reuse from YmPlayer)
    // -----------------------------------------------------------------------

    /** Low-pass filter options (reuses {@link YmPlayer.LpfOption}). */
    public enum LpfOption {
        OFF    ("No filter",   0),
        F4KHZ  ( "4 kHz",  4_000),
        F6KHZ  ( "6 kHz",  6_000),
        F8KHZ  ( "8 kHz",  8_000),
        F10KHZ ("10 kHz", 10_000),
        F12KHZ ("12 kHz", 12_000),
        F16KHZ ("16 kHz", 16_000),
        F20KHZ ("20 kHz", 20_000);

        public final String label;
        public final int    cutoffHz;

        LpfOption(String label, int cutoffHz) { this.label = label; this.cutoffHz = cutoffHz; }
        @Override public String toString() { return label; }
    }

    /** High-pass filter options (reuses {@link YmPlayer.HpfOption}). */
    public enum HpfOption {
        OFF    ("No filter",   0),
        F40HZ  ( "40 Hz",   40),
        F60HZ  ( "60 Hz",   60),
        F80HZ  ( "80 Hz",   80),
        F100HZ ("100 Hz",  100),
        F120HZ ("120 Hz",  120),
        F160HZ ("160 Hz",  160),
        F200HZ ("200 Hz",  200);

        public final String label;
        public final int    cutoffHz;

        HpfOption(String label, int cutoffHz) { this.label = label; this.cutoffHz = cutoffHz; }
        @Override public String toString() { return label; }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private volatile boolean playing         = false;
    private volatile boolean stopRequested   = false;
    private volatile long    positionSamples = 0;

    private volatile Thread        playerThread;
    private volatile SourceDataLine audioLine;

    private volatile LpfOption lpfOption = LpfOption.OFF;
    private volatile HpfOption hpfOption = HpfOption.OFF;
    private Listener           listener;

    // -----------------------------------------------------------------------
    // Listener interface
    // -----------------------------------------------------------------------

    /** Receives playback events from the player thread. */
    public interface Listener {
        /**
         * Called periodically during playback (approximately every 100 ms).
         *
         * @param positionSeconds  current playback position in seconds
         * @param durationSeconds  total capture duration in seconds
         */
        void onProgress(double positionSeconds, double durationSeconds);

        /** Called when playback stops. */
        void onStopped();
    }

    /** Registers a listener that receives progress / stopped callbacks. */
    public void setListener(Listener listener) { this.listener = listener; }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public LpfOption getLpfOption()              { return lpfOption; }
    public void      setLpfOption(LpfOption opt) { this.lpfOption = opt; }

    public HpfOption getHpfOption()              { return hpfOption; }
    public void      setHpfOption(HpfOption opt) { this.hpfOption = opt; }

    // -----------------------------------------------------------------------
    // Playback control
    // -----------------------------------------------------------------------

    /**
     * Parses the PSG capture file at {@code capturePath} and starts playback
     * on a background thread.  Any currently running playback is stopped first.
     *
     * @param capturePath path to a {@code .csv.zip} file
     * @throws IOException if the file cannot be read
     */
    public synchronized void play(Path capturePath) throws IOException {
        stop();
        PsgCaptureFile capture = PsgCaptureParser.parse(capturePath);
        playerThread = new Thread(() -> {
            try {
                runPlayback(capture);
            } catch (LineUnavailableException e) {
                System.err.println("Audio line unavailable: " + e.getMessage());
            }
        }, "psg-player");
        playerThread.setDaemon(true);
        playerThread.start();
    }

    /** Stops playback and waits (up to 2 s) for the background thread to finish. */
    public synchronized void stop() {
        stopRequested = true;
        SourceDataLine l = this.audioLine;
        if (l != null) { l.stop(); l.flush(); }
        Thread t = playerThread;
        if (t != null && t.isAlive()) {
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        playing = false;
    }

    /** Returns {@code true} if playback is currently running. */
    public boolean isPlaying() { return playing; }

    /**
     * Returns the current playback position in seconds (updated every buffer
     * write, approximately every 46 ms at 44100 Hz / 2048 samples).
     */
    public double getPositionSeconds() { return positionSamples / (double) SAMPLE_RATE; }

    // -----------------------------------------------------------------------
    // WAV export
    // -----------------------------------------------------------------------

    /**
     * Renders the full capture to a 16-bit mono 44 100 Hz WAV file.
     *
     * @param capture  parsed PSG capture to render
     * @param wavPath  destination WAV file path
     * @throws IOException on file I/O errors
     */
    public void exportWav(PsgCaptureFile capture, Path wavPath) throws IOException {
        int lpfHz = lpfOption.cutoffHz;
        int hpfHz = hpfOption.cutoffHz;
        exportWav(capture, wavPath, () -> lpfHz, () -> hpfHz);
    }

    private void exportWav(PsgCaptureFile capture, Path wavPath,
                           IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz)
            throws IOException {
        Signal outputSignal = buildOutputSignal(capture, lpfCutoffHz, hpfCutoffHz);
        long totalSamples = durationSamples(capture);
        long dataBytes    = totalSamples * 2L;

        try (OutputStream out = Files.newOutputStream(wavPath)) {
            writeWavHeader(out, dataBytes);

            byte[] buf    = new byte[BUFFER_SAMPLES * 2];
            int    bufIdx = 0;

            for (long t = 0; t < totalSamples; t++) {
                int sample = outputSignal.intAt(t);
                buf[bufIdx++] = (byte)  (sample        & 0xFF);
                buf[bufIdx++] = (byte) ((sample >> 8)  & 0xFF);
                if (bufIdx >= buf.length) {
                    out.write(buf, 0, bufIdx);
                    bufIdx = 0;
                }
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
     *   PsgCaptureProcessor → Ym2149Processor(PSG) → YmMixer
     *     → [LowPassFilter] → [HighPassFilter] → box-filter downsample
     * </pre>
     *
     * @return a {@link Signal} at {@value SAMPLE_RATE} Hz (INT, 16-bit range)
     */
    /* package-private for testability */
    Signal buildOutputSignal(PsgCaptureFile capture,
                             IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {
        // ---- Build 2 MHz DSP pipeline ----------------------------------------
        Processor psgProc  = PsgCaptureProcessor.of(capture);
        Processor ymProc   = Ym2149Processor.ofPsg(psgProc);
        SignalArray ymOut  = ymProc.apply();

        DefaultContext ctx2m = new DefaultContext(Ym2149Processor.YM_CLOCK);
        YmMixer mixer     = new YmMixer(ctx2m);
        SignalArray mixOut = mixer.apply(ymOut);
        Signal mixSignal  = mixOut.at(0);

        // ---- Optional IIR low-pass filter at 2 MHz ---------------------------
        DoubleSignal lpfCutoff = new DoubleSignal() {
            @Override public Context context() { return ctx2m; }
            @Override public double doubleAt(long t) { return lpfCutoffHz.getAsInt(); }
        };
        LowPassFilter lpf = new LowPassFilter(ctx2m);
        Signal lpfSignal = lpf.apply(DefaultArray.a(mixSignal, lpfCutoff)).at(0);

        // ---- Optional IIR high-pass filter at 2 MHz --------------------------
        DoubleSignal hpfCutoff = new DoubleSignal() {
            @Override public Context context() { return ctx2m; }
            @Override public double doubleAt(long t) { return hpfCutoffHz.getAsInt(); }
        };
        HighPassFilter hpf = new HighPassFilter(ctx2m);
        Signal hpfSignal = hpf.apply(DefaultArray.a(lpfSignal, hpfCutoff)).at(0);

        // ---- Box-filter downsample to 44 100 Hz --------------------------------
        final long ymClock    = Ym2149Processor.YM_CLOCK;
        DefaultContext ctx44k = new DefaultContext(SAMPLE_RATE);

        final var filteredSignal = hpfSignal;

        IntSignal downsampled = new IntSignal() {
            @Override public Context context() { return ctx44k; }
            @Override public int intAt(long t) {
                long tymStart = t * ymClock / SAMPLE_RATE;
                long tymEnd   = (t + 1) * ymClock / SAMPLE_RATE;
                long sum   = 0;
                long count = tymEnd - tymStart;
                for (long tym = tymStart; tym < tymEnd; tym++) {
                    sum += filteredSignal.intAt(tym);
                }
                return (count > 0) ? (int) (sum / count) : 0;
            }
        };

        return downsampled;
    }

    // -----------------------------------------------------------------------
    // Internal playback loop
    // -----------------------------------------------------------------------

    private void runPlayback(PsgCaptureFile capture) throws LineUnavailableException {
        Signal outputSignal = buildOutputSignal(capture,
                () -> lpfOption.cutoffHz,
                () -> hpfOption.cutoffHz);

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            this.audioLine = line;
            line.open(format, BUFFER_SAMPLES * 2 * 2);
            line.start();

            final long   samplesPerLoop  = durationSamples(capture);
            final double durationSeconds = samplesPerLoop / (double) SAMPLE_RATE;

            byte[] buffer = new byte[BUFFER_SAMPLES * 2];
            int    bufIdx = 0;

            stopRequested   = false;
            playing         = true;
            positionSamples = 0;

            final long notifyEvery = SAMPLE_RATE / 10;

            long t44k = 0;
            while (!stopRequested) {
                int sample = outputSignal.intAt(t44k % samplesPerLoop);

                buffer[bufIdx++] = (byte)  (sample        & 0xFF);
                buffer[bufIdx++] = (byte) ((sample >> 8)  & 0xFF);

                if (bufIdx >= buffer.length) {
                    line.write(buffer, 0, bufIdx);
                    bufIdx = 0;
                }

                t44k++;
                positionSamples = t44k % samplesPerLoop;

                if (listener != null && (t44k % notifyEvery) == 0) {
                    final double pos = positionSamples / (double) SAMPLE_RATE;
                    listener.onProgress(pos, durationSeconds);
                }
            }

            if (bufIdx > 0) line.write(buffer, 0, bufIdx);
            line.drain();
            line.stop();
        }

        playing       = false;
        this.audioLine = null;
        if (listener != null) listener.onStopped();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Total output samples (at {@value SAMPLE_RATE} Hz) for the full capture duration.
     */
    private static long durationSamples(PsgCaptureFile capture) {
        long ticks2mhz = (capture.durationClocks() + PsgCaptureProcessor.ATARI_RATIO - 1)
                         / PsgCaptureProcessor.ATARI_RATIO;
        return ticks2mhz * SAMPLE_RATE / Ym2149Processor.YM_CLOCK;
    }

    /**
     * Writes a standard 44-byte PCM WAV header for 16-bit mono 44 100 Hz audio.
     */
    private static void writeWavHeader(OutputStream out, long dataBytes) throws IOException {
        long riffSize    = Math.min(dataBytes + 36L, 0xFFFFFFFFL);
        long clampedData = Math.min(dataBytes,       0xFFFFFFFFL);

        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put((byte)'R').put((byte)'I').put((byte)'F').put((byte)'F');
        hdr.putInt((int) riffSize);
        hdr.put((byte)'W').put((byte)'A').put((byte)'V').put((byte)'E');
        hdr.put((byte)'f').put((byte)'m').put((byte)'t').put((byte)' ');
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) 1);
        hdr.putInt(SAMPLE_RATE);
        hdr.putInt(SAMPLE_RATE * 2);
        hdr.putShort((short) 2);
        hdr.putShort((short) 16);
        hdr.put((byte)'d').put((byte)'a').put((byte)'t').put((byte)'a');
        hdr.putInt((int) clampedData);
        out.write(hdr.array());
    }
}
