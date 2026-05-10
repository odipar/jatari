package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.dsp.LowPassFilter;
import org.jatari.ym.Ym2149Processor;
import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;
import org.jatari.ym.format.YmFileProcessor;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
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

/**
 * Plays YM files at 44.1 kHz using a cycle-accurate YM2149 simulation.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * YmFileProcessor (frameRate Hz, 15 signals)
 *   → Ym2149Processor (2 MHz, 3 INT signals: chA / chB / chC)
 *     → YmMixer (2 MHz, 1 INT signal: mixed)
 *       → box-filter downsample → 44100 Hz downsampled INT signal
 *         → [optional] LowPassFilter (jaust IIR, 1 INT)
 *           → javax.sound.sampled SourceDataLine (16-bit LE mono signed PCM)
 * </pre>
 *
 * <p>Playback runs on a background daemon thread.  Call {@link #play(Path)} to
 * start and {@link #stop()} to end playback.  A {@link Listener} can be
 * registered to receive progress and stop events for UI updates.
 *
 * <h2>Low-pass filter</h2>
 * <p>An optional first-order IIR low-pass filter ({@link LowPassFilter}) can be
 * applied at 44 100 Hz after the box-filter downsample.  Select the desired
 * cutoff with {@link #setLpfOption(LpfOption)}.
 *
 * <h2>WAV export</h2>
 * <p>Use {@link #exportWav(YmFile, Path)} to render a full song (one loop) to
 * a 16-bit mono 44 100 Hz WAV file.
 */
public class YmPlayer {

    /** Output sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;

    /** Number of 16-bit output samples per audio-buffer write. */
    private static final int BUFFER_SAMPLES = 2048;

    // -----------------------------------------------------------------------
    // Low-pass filter options
    // -----------------------------------------------------------------------

    /**
     * Selectable cutoff frequencies for the optional IIR low-pass filter.
     */
    public enum LpfOption {
        OFF    ("No filter",   0),
        F4KHZ  ( "4 kHz",  4_000),
        F6KHZ  ( "6 kHz",  6_000),
        F8KHZ  ( "8 kHz",  8_000),
        F10KHZ ("10 kHz", 10_000),
        F12KHZ ("12 kHz", 12_000),
        F16KHZ ("16 kHz", 16_000);

        /** Human-readable label shown in the UI. */
        public final String label;
        /** Cutoff frequency in Hz; 0 means filter is disabled. */
        public final int    cutoffHz;

        LpfOption(String label, int cutoffHz) {
            this.label    = label;
            this.cutoffHz = cutoffHz;
        }

        @Override public String toString() { return label; }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private volatile boolean    playing       = false;
    private volatile boolean    stopRequested = false;
    private volatile long       positionSamples = 0;

    private volatile Thread        playerThread;
    private volatile SourceDataLine audioLine;

    private volatile LpfOption lpfOption = LpfOption.OFF;
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
         * @param durationSeconds  total song duration in seconds
         */
        void onProgress(double positionSeconds, double durationSeconds);

        /** Called when playback stops (song finished or {@link #stop()} called). */
        void onStopped();
    }

    /** Registers a listener that receives progress / stopped callbacks. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /** Returns the currently selected {@link LpfOption}. */
    public LpfOption getLpfOption() { return lpfOption; }

    /**
     * Sets the low-pass filter option applied during playback and WAV export.
     * Takes effect on the next call to {@link #play(Path)} or
     * {@link #exportWav(YmFile, Path)}.
     */
    public void setLpfOption(LpfOption option) {
        this.lpfOption = option;
    }

    // -----------------------------------------------------------------------
    // Playback control
    // -----------------------------------------------------------------------

    /**
     * Parses the YM file at {@code ymPath} and starts playback on a background
     * thread.  Any currently running playback is stopped first.
     *
     * @param ymPath path to a {@code .ym} file
     * @throws IOException if the file cannot be read or has an unsupported format
     */
    public synchronized void play(Path ymPath) throws IOException {
        stop();
        YmFile ym = YmFileParser.parse(ymPath);
        LpfOption opt = lpfOption;
        playerThread = new Thread(() -> {
            try {
                runPlayback(ym, opt);
            } catch (LineUnavailableException e) {
                System.err.println("Audio line unavailable: " + e.getMessage());
            }
        }, "ym-player");
        playerThread.setDaemon(true);
        playerThread.start();
    }

    /**
     * Stops playback and waits (up to 2 s) for the background thread to finish.
     */
    public synchronized void stop() {
        stopRequested = true;
        SourceDataLine l = this.audioLine;
        if (l != null) {
            l.stop();
            l.flush();
        }
        Thread t = playerThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playing = false;
    }

    /** Returns {@code true} if playback is currently running. */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * Returns the current playback position in seconds (updated every buffer
     * write, approximately every 46 ms at 44100 Hz / 2048 samples).
     */
    public double getPositionSeconds() {
        return positionSamples / (double) SAMPLE_RATE;
    }

    // -----------------------------------------------------------------------
    // WAV export
    // -----------------------------------------------------------------------

    /**
     * Renders a full song loop to a 16-bit mono 44 100 Hz WAV file.
     * The current {@link #getLpfOption()} is used for filtering.
     *
     * <p>This method blocks until rendering is complete.
     * It does <b>not</b> interact with the playback thread.
     *
     * @param ym      parsed YM file to render
     * @param wavPath destination WAV file path (created or overwritten)
     * @throws IOException on file I/O errors
     */
    public void exportWav(YmFile ym, Path wavPath) throws IOException {
        exportWav(ym, wavPath, lpfOption);
    }

    /**
     * Renders a full song loop to a 16-bit mono 44 100 Hz WAV file using the
     * given {@link LpfOption}.
     *
     * @param ym      parsed YM file to render
     * @param wavPath destination WAV file path (created or overwritten)
     * @param opt     low-pass filter option
     * @throws IOException on file I/O errors
     */
    public void exportWav(YmFile ym, Path wavPath, LpfOption opt) throws IOException {
        Signal outputSignal = buildOutputSignal(ym, opt);

        long totalSamples = (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
        long dataBytes    = totalSamples * 2L;  // 16-bit = 2 bytes per sample

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
            if (bufIdx > 0) {
                out.write(buf, 0, bufIdx);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline builder
    // -----------------------------------------------------------------------

    /**
     * Builds the full output signal chain for a given YM file and LPF option.
     *
     * <p>Pipeline:
     * <pre>
     *   YmFileProcessor → Ym2149Processor → YmMixer → box-filter downsample
     *     → [LowPassFilter]
     * </pre>
     *
     * @return a {@link Signal} at {@value SAMPLE_RATE} Hz (INT, 16-bit range)
     */
    /* package-private for testability */ Signal buildOutputSignal(YmFile ym, LpfOption opt) {
        // ---- Build 2 MHz DSP pipeline ----------------------------------------
        Processor fileProc = YmFileProcessor.of(ym);
        Processor ymProc   = Ym2149Processor.of(fileProc);
        SignalArray ymOut  = ymProc.apply();

        YmMixer mixer     = new YmMixer(new DefaultContext(Ym2149Processor.YM_CLOCK));
        SignalArray mixOut = mixer.apply(ymOut);
        Signal mixSignal  = mixOut.at(0);   // single INT at 2 MHz

        // ---- Box-filter downsample to 44 100 Hz --------------------------------
        final long ymClock    = Ym2149Processor.YM_CLOCK;
        DefaultContext ctx44k = new DefaultContext(SAMPLE_RATE);

        IntSignal downsampled = new IntSignal() {
            @Override public org.jaust.Context context() { return ctx44k; }
            @Override public int intAt(long t) {
                long tymStart = t * ymClock / SAMPLE_RATE;
                long tymEnd   = (t + 1) * ymClock / SAMPLE_RATE;
                long sum   = 0;
                long count = tymEnd - tymStart;
                for (long tym = tymStart; tym < tymEnd; tym++) {
                    sum += mixSignal.intAt(tym);
                }
                return (count > 0) ? (int) (sum / count) : 0;
            }
        };

        // ---- Optional jaust IIR low-pass filter --------------------------------
        if (opt != LpfOption.OFF) {
            LowPassFilter lpf    = new LowPassFilter(ctx44k, opt.cutoffHz);
            SignalArray   lpfOut = lpf.apply(DefaultArray.a(downsampled));
            return lpfOut.at(0);
        }
        return downsampled;
    }

    // -----------------------------------------------------------------------
    // Internal playback loop
    // -----------------------------------------------------------------------

    private void runPlayback(YmFile ym, LpfOption opt) throws LineUnavailableException {
        Signal outputSignal = buildOutputSignal(ym, opt);

        // ---- Audio line -------------------------------------------------
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            this.audioLine = line;
            line.open(format, BUFFER_SAMPLES * 2 * 2); // 2 bytes/sample × 2× buffer
            line.start();

            // Song duration expressed as number of 44.1 kHz output samples for
            // one complete pass through the file.
            final long   samplesPerLoop  =
                    (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
            final double durationSeconds = (double) ym.numFrames() / ym.frameRate();

            byte[] buffer = new byte[BUFFER_SAMPLES * 2]; // 16-bit LE
            int    bufIdx = 0;

            stopRequested = false;
            playing       = true;
            positionSamples = 0;

            // Notification interval: roughly every 100 ms
            final long notifyEvery = SAMPLE_RATE / 10;

            long t44k = 0;
            while (!stopRequested) {
                int sample = outputSignal.intAt(t44k % samplesPerLoop);

                // 16-bit little-endian
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

            // Flush remaining partial buffer
            if (bufIdx > 0) {
                line.write(buffer, 0, bufIdx);
            }
            line.drain();
            line.stop();
        }

        playing = false;
        this.audioLine = null;
        if (listener != null) {
            listener.onStopped();
        }
    }

    // -----------------------------------------------------------------------
    // WAV header writer
    // -----------------------------------------------------------------------

    /**
     * Writes a standard 44-byte PCM WAV header for 16-bit mono 44 100 Hz audio.
     *
     * @param out       output stream positioned at byte 0
     * @param dataBytes number of PCM data bytes that follow
     */
    private static void writeWavHeader(OutputStream out, long dataBytes) throws IOException {
        // Clamp to 32-bit unsigned max for the RIFF size fields
        long riffSize    = Math.min(dataBytes + 36L, 0xFFFFFFFFL);
        long clampedData = Math.min(dataBytes, 0xFFFFFFFFL);

        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF chunk descriptor
        hdr.put((byte)'R').put((byte)'I').put((byte)'F').put((byte)'F');
        hdr.putInt((int) riffSize);    // file size - 8
        hdr.put((byte)'W').put((byte)'A').put((byte)'V').put((byte)'E');
        // fmt sub-chunk
        hdr.put((byte)'f').put((byte)'m').put((byte)'t').put((byte)' ');
        hdr.putInt(16);                // sub-chunk size (PCM)
        hdr.putShort((short) 1);       // AudioFormat = PCM
        hdr.putShort((short) 1);       // NumChannels = 1 (mono)
        hdr.putInt(SAMPLE_RATE);       // SampleRate
        hdr.putInt(SAMPLE_RATE * 2);   // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        hdr.putShort((short) 2);       // BlockAlign = NumChannels * BitsPerSample/8
        hdr.putShort((short) 16);      // BitsPerSample
        // data sub-chunk
        hdr.put((byte)'d').put((byte)'a').put((byte)'t').put((byte)'a');
        hdr.putInt((int) clampedData); // sub-chunk data size
        out.write(hdr.array());
    }
}
