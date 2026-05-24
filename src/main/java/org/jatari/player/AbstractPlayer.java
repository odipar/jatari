package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.filter.ButterworthLowPass;
import org.jaust.filter.IirHighPass;
import org.jaust.filter.IirLowPass;
import org.jaust.signal.DoubleSignal;
import org.jaust.signal.IntSignal;
import org.jaust.signal.array.DefaultArray;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Abstract base for YM2149 audio players ({@link YmPlayer}, {@link PsgPlayer}).
 *
 * <p>Encapsulates the shared audio pipeline (IIR LPF / HPF + box-filter
 * downsample to 44.1 kHz), WAV export, playback-thread lifecycle, and filter
 * configuration.  Concrete subclasses supply a file parser and a
 * format-specific signal-chain builder.
 *
 * @param <T> the parsed audio-data type (e.g. {@code YmFile} or {@code PsgCapture})
 */
public abstract class AbstractPlayer<T> {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Output sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;

    /** Number of 16-bit output samples per audio-buffer write (~46 ms). */
    static final int BUFFER_SAMPLES = 2048;

    // -----------------------------------------------------------------------
    // Filter options
    // -----------------------------------------------------------------------

    /** Selectable cutoff frequencies for the optional IIR low-pass filter. */
    public enum LpfOption {
        OFF    ("No filter",   0),
        F1KHZ  ( "100 Hz",  100),
        F2KHZ  ( "2 kHz",  2_000),
        F4KHZ  ( "4 kHz",  4_000),
        F6KHZ  ( "6 kHz",  6_000),
        F8KHZ  ( "8 kHz",  8_000),
        F10KHZ ("10 kHz", 10_000),
        F12KHZ ("12 kHz", 12_000),
        F16KHZ ("16 kHz", 16_000),
        F20KHZ ("20 kHz", 20_000);

        /** Human-readable label shown in the UI. */
        public final String label;
        /** Cutoff frequency in Hz; 0 means filter is disabled. */
        public final int    cutoffHz;

        LpfOption(String label, int cutoffHz) { this.label = label; this.cutoffHz = cutoffHz; }

        @Override public String toString() { return label; }
    }

    /** Selectable cutoff frequencies for the optional IIR high-pass filter. */
    public enum HpfOption {
        OFF    ("No filter",   0),
        F4KHZ  ( "40 Hz",   40),
        F6KHZ  ( "60 Hz",   60),
        F8KHZ  ( "80 Hz",   80),
        F10KHZ ("100 Hz",  100),
        F12KHZ ("120 Hz",  120),
        F16KHZ ("160 Hz",  160),
        F20KHZ ("200 Hz",  200),
        F40KHZ ("4 kHz",  4000),
        F80KHZ ("8 kHz",  8000);

        /** Human-readable label shown in the UI. */
        public final String label;
        /** Cutoff frequency in Hz; 0 means filter is disabled. */
        public final int    cutoffHz;

        HpfOption(String label, int cutoffHz) { this.label = label; this.cutoffHz = cutoffHz; }

        @Override public String toString() { return label; }
    }

    // -----------------------------------------------------------------------
    // State (package-private so subclasses in the same package can read them)
    // -----------------------------------------------------------------------

    volatile boolean        playing         = false;
    volatile boolean        stopRequested   = false;
    volatile long           positionSamples = 0;
    volatile Thread         playerThread;
    volatile SourceDataLine audioLine;
    volatile LpfOption      lpfOption       = LpfOption.OFF;
    volatile HpfOption      hpfOption       = HpfOption.OFF;
    Listener                listener;

    // -----------------------------------------------------------------------
    // Listener
    // -----------------------------------------------------------------------

    /** Receives playback events from the player thread. */
    public interface Listener {
        /**
         * Called approximately every 100 ms during playback.
         *
         * @param positionSeconds current position in seconds
         * @param durationSeconds total duration in seconds
         */
        void onProgress(double positionSeconds, double durationSeconds);

        /** Called when playback stops (finished or {@link #stop()} called). */
        void onStopped();
    }

    /** Registers a listener for progress / stopped callbacks. */
    public void setListener(Listener listener) { this.listener = listener; }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public LpfOption getLpfOption() { return lpfOption; }
    public void setLpfOption(LpfOption option) { this.lpfOption = option; }

    public HpfOption getHpfOption() { return hpfOption; }
    public void setHpfOption(HpfOption option) { this.hpfOption = option; }

    // -----------------------------------------------------------------------
    // Playback control
    // -----------------------------------------------------------------------

    /**
     * Parses the file at {@code path} and starts playback on a background
     * thread.  Any running playback is stopped first.
     *
     * @throws IOException if the file cannot be read or is unsupported
     */
    public synchronized void play(Path path) throws IOException {
        stop();
        T data = parse(path);
        playerThread = new Thread(() -> {
            try {
                runPlayback(data);
            } catch (LineUnavailableException e) {
                System.err.println(threadName() + " audio line unavailable: " + e.getMessage());
            }
        }, threadName());
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
    // Abstract extension points
    // -----------------------------------------------------------------------

    /** Parses the audio file at {@code path} into the domain model. */
    protected abstract T parse(Path path) throws IOException;

    /** Runs the audio playback loop on the player thread. */
    protected abstract void runPlayback(T data) throws LineUnavailableException;

    /** Name for the player daemon thread, e.g. {@code "ym-player"}. */
    protected abstract String threadName();

    // -----------------------------------------------------------------------
    // Shared signal-pipeline helper
    // -----------------------------------------------------------------------

    /**
     * Applies IIR LPF → IIR HPF → box-filter downsample to {@code mixSig}
     * and returns a {@value SAMPLE_RATE} Hz output {@link Signal}.
     *
     * @param mixSig      mixed mono signal at {@code ymClock} Hz
     * @param ctx2m       context running at {@code ymClock} Hz
     * @param ymClock     YM2149 clock frequency in Hz
     * @param lpfCutoffHz supplier of the LPF cutoff (0 = bypass)
     * @param hpfCutoffHz supplier of the HPF cutoff (0 = bypass)
     */
    protected final Signal buildFilterChain(
            Signal mixSig, DefaultContext ctx2m, long ymClock,
            IntSupplier lpfCutoffHz, IntSupplier hpfCutoffHz) {

        DoubleSignal mixSig3 = new DoubleSignal() {
            public Context context() { return ctx2m; }
            public double doubleAt(long l) {
                return mixSig.intAt(l)/32767.0;
            }
        };
        
        DoubleSignal lpfCutoff = new DoubleSignal() {
            public Context context()        { return ctx2m; }
            public double  doubleAt(long t) { return lpfCutoffHz.getAsInt(); }
        };
        
        //Signal lpfSig = (new IirLowPass(ctx2m)).apply(DefaultArray.a(mixSig3, lpfCutoff)).at(0);
        
        Signal lpfSig = (new ButterworthLowPass(ctx2m)).apply(DefaultArray.a(mixSig3, lpfCutoff)).at(0);
        
        DoubleSignal hpfCutoff = new DoubleSignal() {
            public Context context()        { return ctx2m; }
            public double  doubleAt(long t) { return hpfCutoffHz.getAsInt(); }
        };
        
        Signal hpfSig = (new IirHighPass(ctx2m)).apply(DefaultArray.a(lpfSig, hpfCutoff)).at(0);

        DefaultContext ctx44k = new DefaultContext(SAMPLE_RATE);
        final Signal   filt   = hpfSig;
        
        return new IntSignal() {
            public Context context() { return ctx44k; }
            public int intAt(long t) {
                long start = t * ymClock / SAMPLE_RATE;
                long end   = (t + 1) * ymClock / SAMPLE_RATE;
                long sum = 0, count = end - start;
                for (long i = start; i < end; i++) sum += (int)(filt.doubleAt(i)*32767.0);
                return (count > 0) ? (int) (sum / count) : 0;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Shared audio-line playback loop
    // -----------------------------------------------------------------------

    /**
     * Opens a 16-bit LE mono 44 100 Hz {@link SourceDataLine} and feeds it
     * from {@code outputSignal} until stopped or all samples are consumed.
     *
     * @param outputSignal signal at {@value SAMPLE_RATE} Hz
     * @param totalSamples total samples for one loop / one play
     * @param durationSecs total duration passed to progress callbacks
     * @param loop         {@code true} wraps the sample index and plays until
     *                     {@link #stop()} is called; {@code false} stops
     *                     automatically after all samples have been played
     */
    protected final void runAudioLine(
            Signal outputSignal, long totalSamples, double durationSecs, boolean loop)
            throws LineUnavailableException {

        AudioFormat   format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info   = new DataLine.Info(SourceDataLine.class, format);

        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            this.audioLine = line;
            line.open(format, BUFFER_SAMPLES * 2 * 2);
            line.start();

            byte[] buffer = new byte[BUFFER_SAMPLES * 2];
            int    bufIdx = 0;

            stopRequested   = false;
            playing         = true;
            positionSamples = 0;

            final long notifyEvery = SAMPLE_RATE / 10;
            long t44k = 0;

            while (!stopRequested && (loop || t44k < totalSamples)) {
                int sample = outputSignal.intAt(loop ? t44k % totalSamples : t44k);

                buffer[bufIdx++] = (byte)  (sample       & 0xFF);
                buffer[bufIdx++] = (byte) ((sample >> 8) & 0xFF);

                if (bufIdx >= buffer.length) {
                    line.write(buffer, 0, bufIdx);
                    bufIdx = 0;
                }

                t44k++;
                positionSamples = loop ? t44k % totalSamples : t44k;
                if (listener != null && (t44k % notifyEvery) == 0) {
                    listener.onProgress(positionSamples / (double) SAMPLE_RATE, durationSecs);
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
    // Shared WAV helpers
    // -----------------------------------------------------------------------

    /**
     * Writes {@code totalSamples} samples from {@code outputSignal} as
     * 16-bit little-endian PCM to {@code out}.
     */
    protected final void writeWavData(Signal outputSignal, long totalSamples, OutputStream out)
            throws IOException {
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

    /**
     * Writes a standard 44-byte PCM WAV header for 16-bit mono 44 100 Hz audio.
     *
     * @param out       output stream positioned at byte 0
     * @param dataBytes number of PCM data bytes that follow
     */
    protected static void writeWavHeader(OutputStream out, long dataBytes) throws IOException {
        long riffSize    = Math.min(dataBytes + 36L, 0xFFFFFFFFL);
        long clampedData = Math.min(dataBytes,        0xFFFFFFFFL);

        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put((byte)'R').put((byte)'I').put((byte)'F').put((byte)'F');
        hdr.putInt((int) riffSize);
        hdr.put((byte)'W').put((byte)'A').put((byte)'V').put((byte)'E');
        hdr.put((byte)'f').put((byte)'m').put((byte)'t').put((byte)' ');
        hdr.putInt(16);
        hdr.putShort((short) 1);      // PCM
        hdr.putShort((short) 1);      // mono
        hdr.putInt(SAMPLE_RATE);
        hdr.putInt(SAMPLE_RATE * 2);  // byte rate
        hdr.putShort((short) 2);      // block align
        hdr.putShort((short) 16);     // bits per sample
        hdr.put((byte)'d').put((byte)'a').put((byte)'t').put((byte)'a');
        hdr.putInt((int) clampedData);
        out.write(hdr.array());
    }
}
